package kr.chacademi.dungeon;

import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/**
 * 1인 던전 화면 (서버 ChacaDungeon 이 보내는 것만 그린다). 판정은 모두 서버: 여기서는 보여 주고 버튼 눌림만 보낸다.
 * <ul>
 *   <li>입장창 (포탈에 다가가면) · 대기/입장 준비 창 · 나가기 확인 · 결과창</li>
 *   <li>던전 안: 오른쪽 위 던전 메뉴 (이름·경과 시간·나가기) + 은색 던전 단계창 (스토리 퀘스트창 옆, 겹치지 않게)</li>
 *   <li>월드: 입장 포탈 홀로그램</li>
 * </ul>
 * 다른 창이 열려 있으면 창을 덮지 않고 기다렸다가 비었을 때 연다 (Ctrl 포인터 화면·채팅은 HUD 로 보고 열어도 됨).
 * HUD 버튼(나가기)은 채팅이나 Ctrl 포인터 화면에서 누른다 (평소 커서를 붙잡지 않는다).
 */
public final class DungeonClient implements ClientModInitializer {
    static DungeonProtocol.Entry entry;
    static DungeonProtocol.Queue queue = new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0);
    static long queueAt;
    static DungeonProtocol.Run run = DungeonProtocol.Run.NONE;
    static long runAt;
    static List<DungeonProtocol.Portal> portals = List.of();

    private static DungeonProtocol.Entry pendingEntry;
    private static long pendingEntryAt;
    private static boolean pendingReady;
    private static DungeonProtocol.Result pendingResult;
    private static long pendingResultAt;
    private static int lastEntrySession, lastReadySession;

    @Override public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(Net.ToClient.TYPE, Net.ToClient.CODEC);
        PayloadTypeRegistry.playC2S().register(Net.ToServer.TYPE, Net.ToServer.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Net.ToClient.TYPE, (p, ctx) -> {
            DungeonProtocol.ToClient m;
            try { m = DungeonProtocol.decodeToClient(p.data()); }
            catch (Exception e) { org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("던전 메시지 무시 (서버 플러그인 버전 확인): {}", e.toString()); return; }
            ctx.client().execute(() -> receive(m));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(DungeonClient::clear));
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(DungeonClient::clear));
        HudRenderCallback.EVENT.register(DungeonHud::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PortalRender::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldDraw::render);
        Placing.install();
        ClientTickEvents.END_CLIENT_TICK.register(DungeonClient::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!hudScreen(screen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
                if (button == 0 && DungeonHud.hitExit(mx, my)) { open(new UiScreens.ExitConfirm()); return false; }
                return true;
            });
        });
    }

    private static void clear() {
        entry = null; pendingEntry = null; pendingReady = false; pendingResult = null;
        queue = new DungeonProtocol.Queue(DungeonProtocol.Q_NONE, "", 0, 0, 0, 0, 0, 0);
        run = DungeonProtocol.Run.NONE;
        portals = List.of();
        EditorState.clear();
        Art.reset();
    }

    static long now() { return Util.getMillis(); }

    /** 스토리 퀘스트 HUD 와 같은 규칙: 채팅·MagicCodex Ctrl 포인터 화면 위에서는 HUD 를 그리고 누를 수 있다. */
    static boolean hudScreen(Screen screen) {
        return screen instanceof ChatScreen || screen != null && "school.magiccodex.client.HudCursorScreen".equals(screen.getClass().getName());
    }

    private static boolean canOpen() {
        var s = Minecraft.getInstance().screen;
        return s == null || hudScreen(s) || s instanceof UiScreens.Base;
    }

    static void open(Screen screen) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // MagicCodex 의 떠 있는 창(대화 등)을 먼저 닫는 규칙 (있을 때만)
        try { Class.forName("school.magiccodex.client.MagicCodexClient").getMethod("dismiss").invoke(null); } catch (Throwable ignored) { }
        mc.setScreen(screen);
    }

    private static void receive(DungeonProtocol.ToClient m) {
        var mc = Minecraft.getInstance();
        switch (m) {
            case DungeonProtocol.EntryMsg e -> {
                entry = e.entry();
                if (mc.screen instanceof UiScreens.EntryScreen s && s.session() == e.entry().session()) return; // 열린 창 갱신
                if (e.entry().session() == lastEntrySession) return; // 닫은 창은 다시 열지 않는다 (포탈에서 멀어졌다 오면 새 번호)
                lastEntrySession = e.entry().session();
                pendingEntry = e.entry();
                pendingEntryAt = now();
            }
            case DungeonProtocol.QueueMsg q -> {
                var before = queue;
                queue = q.queue();
                queueAt = now();
                if (q.queue().state() == DungeonProtocol.Q_READY && q.queue().session() != lastReadySession) {
                    lastReadySession = q.queue().session();
                    pendingReady = true;
                }
                if (q.queue().state() == DungeonProtocol.Q_NONE && before.state() != DungeonProtocol.Q_NONE && mc.screen instanceof UiScreens.QueueScreen) mc.setScreen(null);
            }
            case DungeonProtocol.RunMsg r -> { run = r.run(); runAt = now(); }
            case DungeonProtocol.ResultMsg r -> { pendingResult = r.result(); pendingResultAt = now(); }
            case DungeonProtocol.PortalsMsg p -> portals = p.portals();
            case DungeonProtocol.JsonMsg j -> EditorState.receive(j.kind(), j.json());
            case DungeonProtocol.CloseMsg c -> {
                pendingEntry = null;
                if (mc.screen instanceof UiScreens.EntryScreen || mc.screen instanceof UiScreens.QueueScreen) mc.setScreen(null);
            }
        }
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        long now = now();
        if (pendingResult != null) {
            if (canOpen() && !(mc.screen instanceof UiScreens.ResultScreen)) { open(new UiScreens.ResultScreen(pendingResult)); pendingResult = null; }
            else if (now - pendingResultAt > 120_000) pendingResult = null;
            return;
        }
        if (pendingReady) {
            if (queue.state() != DungeonProtocol.Q_READY) pendingReady = false;
            else if (canOpen() && !(mc.screen instanceof UiScreens.ExitConfirm)) { open(new UiScreens.QueueScreen()); pendingReady = false; }
            return;
        }
        if (pendingEntry != null) {
            if (now - pendingEntryAt > 15_000) pendingEntry = null;
            else if (canOpen() && !(mc.screen instanceof UiScreens.QueueScreen) && !(mc.screen instanceof UiScreens.ExitConfirm)) {
                open(new UiScreens.EntryScreen(pendingEntry.session()));
                pendingEntry = null;
            }
        }
    }

    /** 서버 기준 경과 시간을 받은 뒤 흐른 만큼 더한다 (서버가 5초마다·바뀔 때마다 다시 맞춘다). */
    static long elapsed() {
        if (!run.active() || !run.timing()) return 0;
        return run.elapsedMs() + Math.max(0, now() - runAt);
    }
}
