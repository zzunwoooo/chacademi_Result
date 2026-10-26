package kr.chacademy.story.quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * 스토리 단계 표시 (서버가 보내는 것만 그린다, 본인에게만 보임).
 * <ul>
 *   <li>HUD: 오른쪽 중간, 장 이름 · 설명 · 조건 진행도 · 목표 완료 ({@link QuestHud})</li>
 *   <li>월드: 준비 완료 시 목표 지점 포탈 / 말 걸 NPC 머리 위 징표 ({@link QuestWorld})</li>
 * </ul>
 * 애니메이션은 모두 클라이언트 시계로 돌린다 (서버는 값이 바뀔 때만 보낸다).
 */
public final class QuestClient {
    private QuestClient() {}

    /** 지금 받은 단계. step 이 비어 있으면 표시 없음. */
    static final class View {
        int session;
        String step = "";
        int run;
        String chapter = "", description = "";
        boolean ready;
        final List<QuestPackets.Cond> conds = new ArrayList<>();
        int targetType;
        String world = "";
        double x, y, z, radius;
        UUID npc;
        /** 화면 시계(ms): 단계를 받은 때 / 준비 완료가 된 때. */
        long shownAt, readyAt;
        int[] doneAt = new int[0];
        /** 목표 지점 여러 개 (상호작용 블록, PointsS2C). 호출 목표와 따로 그린다. */
        final List<QuestPackets.Point> points = new ArrayList<>();

        boolean active() { return !step.isEmpty(); }
    }

    static View view = new View();
    /** 길잡이 별 화살표: 켜진 단계 session (0 = 꺼짐), 켠 시각, 끄기 시작한 시각 (0 = 켜져 있음). */
    static int guideSession;
    static long guideAt, guideEndAt;

    /** 길잡이 별이 따라가는 NPC (2026-10-26 메모 #93, GuideTargetS2C). 서버가 보낸 마지막 위치·상태. null = 고정 목표 (단계 패킷 좌표). */
    static final class Follow {
        int session, state;
        boolean moving;
        String world = "";
        double x, y, z;
        UUID entity;
        /** 끄기(session 0)를 받은 뒤의 것: 사라지는 연출에만 쓰고, 다음에 켤 때는 버린다. */
        boolean stale;
    }
    static Follow follow;
    /** 방금 끝난 포탈 (들어가는 연출용). */
    static View vanishing;
    static long vanishAt;
    static boolean collapsed;

    private static final Path PREFS = FabricLoader.getInstance().getConfigDir().resolve("chaca_story").resolve("quest_hud.properties");

    public static void install() {
        PayloadTypeRegistry.playS2C().register(QuestPackets.StepS2C.TYPE, QuestPackets.StepS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(QuestPackets.UpdateS2C.TYPE, QuestPackets.UpdateS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(QuestPackets.StepS2C.TYPE, (p, ctx) -> { if (p != QuestPackets.StepS2C.INVALID) ctx.client().execute(() -> onStep(p)); });
        ClientPlayNetworking.registerGlobalReceiver(QuestPackets.UpdateS2C.TYPE, (p, ctx) -> { if (p != QuestPackets.UpdateS2C.INVALID) ctx.client().execute(() -> onUpdate(p)); });
        PayloadTypeRegistry.playS2C().register(QuestPackets.GuideS2C.TYPE, QuestPackets.GuideS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(QuestPackets.GuideS2C.TYPE, (p, ctx) -> { if (p != QuestPackets.GuideS2C.INVALID) ctx.client().execute(() -> onGuide(p)); });
        PayloadTypeRegistry.playS2C().register(QuestPackets.PointsS2C.TYPE, QuestPackets.PointsS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(QuestPackets.PointsS2C.TYPE, (p, ctx) -> { if (p != QuestPackets.PointsS2C.INVALID) ctx.client().execute(() -> onPoints(p)); });
        PayloadTypeRegistry.playS2C().register(QuestPackets.GuideTargetS2C.TYPE, QuestPackets.GuideTargetS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(QuestPackets.GuideTargetS2C.TYPE, (p, ctx) -> { if (p != QuestPackets.GuideTargetS2C.INVALID) ctx.client().execute(() -> onGuideTarget(p)); });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> { view = new View(); vanishing = null; guideSession = 0; guideAt = guideEndAt = 0; follow = null; QuestWorld.reset(); }));
        // 프록시로 서버를 옮길 때 (학교 → 야생, 둘 다 minecraft:overworld): 예전 서버 좌표의 포탈·징표·화살표가 새 서버 세계에 그려지지 않게
        // 목표만 지운다 (HUD 글은 남김 — 스토리 서버로 돌아오면 서버가 단계를 다시 보낸다). TravelClient 와 같은 JOIN 시점.
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(() -> {
            view.targetType = QuestPackets.TARGET_NONE; view.ready = false; view.npc = null; view.points.clear();
            vanishing = null; guideSession = 0; guideAt = guideEndAt = 0; follow = null; QuestWorld.reset();
        }));
        HudRenderCallback.EVENT.register(QuestHud::render);
        // 물·유리·반투명 블록을 다 그린 뒤에 그린다 (AFTER_ENTITIES 면 뒤의 물·유리가 글자·포탈 위에 덮여 흐려 보였음)
        WorldRenderEvents.AFTER_TRANSLUCENT.register(QuestWorld::render);
        ClientTickEvents.END_CLIENT_TICK.register(QuestWorld::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!hudScreen(screen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
                if (button == 0 && QuestHud.hitToggle(mx, my)) { toggle(); return false; }
                return true;
            });
        });
        try {
            if (Files.isRegularFile(PREFS)) collapsed = Files.readString(PREFS).contains("collapsed=true");
        } catch (Exception ignored) { }
    }

    /** Same policy for HUD visibility and fold-button input, without a hard MagicCodex dependency. */
    static boolean hudScreen(net.minecraft.client.gui.screens.Screen screen) {
        return QuestHudScreenPolicy.visible(screen instanceof ChatScreen,screen==null?"":screen.getClass().getName());
    }

    static void toggle() {
        collapsed = !collapsed;
        try {
            Files.createDirectories(PREFS.getParent());
            Files.writeString(PREFS, "collapsed=" + collapsed + "\n");
        } catch (Exception ignored) { }
    }

    static long now() { return net.minecraft.Util.getMillis(); }

    /** 길잡이 별: session 0 = 끄기 (서서히 사라짐), 지금 단계 session = 켜기. 다른 session 은 무시. */
    private static void onGuide(QuestPackets.GuideS2C g) {
        if (g.session() == 0) {
            if (guideSession != 0 && guideEndAt == 0) guideEndAt = now();
            if (follow != null) follow.stale = true;
            return;
        }
        if (follow != null && (follow.stale || follow.session != g.session())) follow = null;
        // 의뢰 NPC 목표 (#93): 진행 중인 스토리 단계가 없어도, 같은 session 의 NPC 위치를 먼저 받았으면 켠다
        boolean npcTarget = follow != null && g.session() > 0 && g.session() == view.session;
        if (!QuestGuidePolicy.accepts(view.active(), view.session, g.session()) && !npcTarget) return;
        guideSession = g.session();
        guideAt = now();
        guideEndAt = 0;
    }

    /** 길잡이 별이 따라가는 NPC 의 새 위치 (#93): 지금 session 것만. 이미 따라가는 중이면 값만 바꾼다 (화살표는 부드럽게 옮겨감). */
    private static void onGuideTarget(QuestPackets.GuideTargetS2C t) {
        if (t.session() != view.session) return;
        Follow f = follow != null && !follow.stale && follow.session == t.session() ? follow : new Follow();
        f.session = t.session();
        f.state = t.state();
        f.moving = t.moving();
        f.world = t.world();
        f.x = t.x(); f.y = t.y(); f.z = t.z();
        try { f.entity = t.entity().isEmpty() ? null : UUID.fromString(t.entity()); } catch (IllegalArgumentException e) { f.entity = null; }
        follow = f;
    }

    /** 목표 지점: 지금 단계 session 것만 (단계 패킷 바로 뒤에 온다). */
    private static void onPoints(QuestPackets.PointsS2C p) {
        if (!view.active() || p.session() != view.session) return;
        view.points.clear();
        view.points.addAll(p.points());
    }

    private static void onStep(QuestPackets.StepS2C p) {
        View old = view;
        // 포탈로 들어가 다음 단계가 됨: 예전 포탈을 잠깐 사라지는 연출로 남긴다
        if (old.active() && old.ready && old.targetType == QuestPackets.TARGET_LOCATION && !old.step.equals(p.step()) && nearPortal(old)) {
            vanishing = old;
            vanishAt = now();
        }
        View v = new View();
        v.session = p.session();
        v.step = p.step();
        v.run = p.run();
        v.chapter = p.chapter();
        v.description = p.description();
        v.ready = p.ready();
        v.conds.addAll(p.conds());
        v.targetType = p.targetType();
        v.world = p.world();
        v.x = p.x(); v.y = p.y(); v.z = p.z(); v.radius = p.radius();
        try { v.npc = p.npcEntity().isEmpty() ? null : UUID.fromString(p.npcEntity()); } catch (IllegalArgumentException e) { v.npc = null; }
        long t = now();
        boolean same = old.active() && old.step.equals(v.step) && old.run == v.run;
        v.shownAt = same ? old.shownAt : t;
        v.readyAt = v.ready ? (same && old.ready ? old.readyAt : t) : 0;
        v.doneAt = new int[v.conds.size()];
        view = v;
        follow = null;
        QuestWorld.reset();
    }

    private static void onUpdate(QuestPackets.UpdateS2C u) {
        View v = view;
        if (!v.active() || u.session() != v.session || !u.step().equals(v.step) || u.run() != v.run) return;
        for (int i = 0; i < Math.min(u.current().length, v.conds.size()); i++) {
            var c = v.conds.get(i);
            v.conds.set(i, new QuestPackets.Cond(c.label(), u.current()[i], c.required()));
        }
        // 포탈에 들어가 호출됨 (대화를 기다리는 동안 준비 완료가 꺼짐): 포탈이 빛나며 사라지는 연출
        if (v.ready && !u.ready() && v.targetType == QuestPackets.TARGET_LOCATION && nearPortal(v)) {
            View gone = new View();
            gone.world = v.world; gone.x = v.x; gone.y = v.y; gone.z = v.z; gone.radius = v.radius; gone.ready = true;
            gone.targetType = v.targetType; gone.step = v.step;
            vanishing = gone;
            vanishAt = now();
        }
        if (u.ready() && !v.ready) v.readyAt = now();
        if (!u.ready()) v.readyAt = 0;
        v.ready = u.ready();
    }

    private static boolean nearPortal(View v) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double dx = mc.player.getX() - v.x, dz = mc.player.getZ() - v.z;
        return dx * dx + dz * dz <= (v.radius + 2) * (v.radius + 2) && Math.abs(mc.player.getY() - v.y) < 4;
    }
}
