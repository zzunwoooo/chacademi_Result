package kr.chacademy.story.quest;

// [기능:subquest-hud-20261031 시작] 진행 중인 의뢰(서브 퀘스트) HUD. 파일 전체가 새 기능.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 진행 중인 의뢰(MagicCodex 의뢰 게시판에서 받은 것, 최대 4개)를 스토리 퀘스트창과 같은 모양(금색 장식선·책 아이콘·목표 진행도)으로
 * 퀘스트창 바로 아래에 쌓아 그린다. 던전 단계창이 있으면 그 아래. 서버(MagicCodexBridge QuestHudPush)가 바뀔 때마다 목록 전체를 보낸다.
 * MagicCodex HUD 편집 id "quest_sub" 로 그려 옮기기·크기 바꾸기가 된다. 접기는 채팅창을 연 상태에서 첫 의뢰의 −/+ 클릭 (스토리창 접기와 따로).
 */
final class SubquestHud {
    private SubquestHud() {}

    static final String HUD_ID = "quest_sub";
    private static final float WIDTH = 158, TOGGLE = 13, GAP = 7;
    private static final float CHAPTER_SIZE = 7.2f, TITLE_SIZE = 9.6f, COND_SIZE = 7.4f;
    private static final int WHITE = 0xFFFFFFFF, CHAPTER = 0xFFF4EBD2, COND = 0xFFEDEDED, COND_DONE = 0xFFB7B7B7, GOLD = 0xFFFFE08A;
    private static final Path PREFS = FabricLoader.getInstance().getConfigDir().resolve("chaca_story").resolve("subquest_hud.properties");

    record Goal(String label, int current, int required) {}
    record Quest(String title, String rank, boolean main, boolean done, List<Goal> goals) {}

    /** 서버 → 클라: 진행 중 의뢰 전체 (빈 목록 = 숨김). 바이트 형식은 플러그인 QuestHudPush 와 같아야 한다. */
    record ListS2C(List<Quest> quests) implements CustomPacketPayload {
        static final Type<ListS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("magiccodex", "quest_hud"));
        static final ListS2C INVALID = new ListS2C(List.of());
        static final StreamCodec<RegistryFriendlyByteBuf, ListS2C> CODEC = SafeCodec.failSafe("quest_hud", StreamCodec.of((buf, v) -> {
            buf.writeVarInt(1); buf.writeVarInt(v.quests().size());
            for (var q : v.quests()) {
                buf.writeUtf(q.title()); buf.writeUtf(q.rank()); buf.writeBoolean(q.main()); buf.writeBoolean(q.done()); buf.writeVarInt(q.goals().size());
                for (var g : q.goals()) { buf.writeUtf(g.label()); buf.writeVarInt(g.current()); buf.writeVarInt(g.required()); }
            }
        }, buf -> {
            if (buf.readVarInt() != 1) throw new IllegalArgumentException("unknown quest_hud format");
            int n = buf.readVarInt();
            if (n < 0 || n > 8) throw new IllegalArgumentException("too many quests: " + n);
            var quests = new ArrayList<Quest>(n);
            for (int i = 0; i < n; i++) {
                String title = buf.readUtf(256), rank = buf.readUtf(8);
                boolean main = buf.readBoolean(), done = buf.readBoolean();
                int k = buf.readVarInt();
                if (k < 0 || k > 8) throw new IllegalArgumentException("too many goals: " + k);
                var goals = new ArrayList<Goal>(k);
                for (int j = 0; j < k; j++) goals.add(new Goal(buf.readUtf(256), buf.readVarInt(), Math.max(1, buf.readVarInt())));
                quests.add(new Quest(title, rank, main, done, List.copyOf(goals)));
            }
            return new ListS2C(List.copyOf(quests));
        }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static List<Quest> quests = List.of();
    private static long shownAt;
    private static boolean collapsed;
    /** 접기 버튼 클릭 판정 (편집 전 원래 좌표). */
    private static float toggleX = -100, toggleY = -100;

    static void init() {
        PayloadTypeRegistry.playS2C().register(ListS2C.TYPE, ListS2C.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ListS2C.TYPE, (p, ctx) -> { if (p != ListS2C.INVALID) ctx.client().execute(() -> show(p.quests())); });
        // 서버를 옮기면 새 서버가 다시 보낸다
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> quests = List.of()));
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(() -> quests = List.of()));
        HudRenderCallback.EVENT.register(SubquestHud::render);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!QuestClient.hudScreen(screen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
                if (button == 0 && hitToggle(mx, my)) { toggle(); return false; }
                return true;
            });
        });
        try { if (Files.isRegularFile(PREFS)) collapsed = Files.readString(PREFS).contains("collapsed=true"); } catch (Exception ignored) { }
    }

    private static void show(List<Quest> next) {
        if (quests.isEmpty() && !next.isEmpty()) shownAt = QuestClient.now();
        quests = next;
    }

    private static void toggle() {
        collapsed = !collapsed;
        try { Files.createDirectories(PREFS.getParent()); Files.writeString(PREFS, "collapsed=" + collapsed + "\n"); } catch (Exception ignored) { }
    }

    private static boolean hitToggle(double mx, double my) {
        if (quests.isEmpty() || HudEditing.editing()) return false;
        var p = pointer(mx, my);
        return p[0] >= toggleX - 2 && p[0] <= toggleX + TOGGLE + 2 && p[1] >= toggleY - 2 && p[1] <= toggleY + TOGGLE + 2;
    }

    /** 한 의뢰를 그릴 줄들 (높이 계산과 그리기에 같이 쓴다). */
    private record GoalLines(List<String> lines, boolean ok) {}
    private record Block(String chapter, List<String> title, List<GoalLines> goals, boolean done) {}

    static void render(GuiGraphics g, DeltaTracker dt) {
        var mc = Minecraft.getInstance();
        var list = quests;
        if (list.isEmpty() || mc.player == null || mc.options.hideGui) { toggleX = -100; return; }
        if (mc.screen != null && !QuestClient.hudScreen(mc.screen)) { toggleX = -100; return; }
        int a = Math.round(255 * Mth.clamp((QuestClient.now() - shownAt) / 350f, 0, 1));
        if (a <= 2) return;

        float sw = g.guiWidth(), sh = g.guiHeight();
        float textW = WIDTH - TOGGLE - 5;
        var blocks = new ArrayList<Block>();
        float height = 0;
        for (var q : list) {
            String chapter = (q.main() ? "메인 의뢰" : "의뢰") + " · " + q.rank() + "등급";
            if (collapsed) chapter = q.title();
            var title = collapsed ? List.<String>of() : wrap(g, q.title(), TITLE_SIZE, textW, 2);
            var goals = new ArrayList<GoalLines>();
            if (!collapsed && !q.done()) for (var c : q.goals()) {
                boolean ok = c.current() >= c.required();
                goals.add(new GoalLines(wrap(g, c.label() + " " + Math.min(c.current(), c.required()) + "/" + c.required(), COND_SIZE, textW - (ok ? 11 : 0), 2), ok));
            }
            var b = new Block(chapter, title, goals, q.done());
            blocks.add(b);
            height += blockHeight(b) + GAP;
        }
        height -= GAP;

        // 기본 자리: 스토리 퀘스트창(옮겼으면 옮긴 곳)·던전 단계창 중 아래쪽 것의 바로 밑, 오른쪽 끝 맞춤. 둘 다 없으면 퀘스트창 자리.
        float right = sw - 8, top = Math.round(sh * 0.30f) - 9;
        float bottom = -1;
        for (String id : new String[]{"quest", "dungeon_quest"}) {
            float[] b = HudEditing.box(id);
            if (b != null && b[1] + b[3] > bottom) { bottom = b[1] + b[3]; right = b[0] + b[2]; }
        }
        if (bottom >= 0) top = bottom + 6;
        else top = QuestHudPlacement.defaultTop(top + 9, right - WIDTH, right, height, HudEditing.box("party")) - 9;
        float x0 = right - WIDTH;
        try (var scope = begin(g, x0, top, WIDTH, height)) {
            float y = top + 9;
            boolean first = true;
            for (var b : blocks) {
                y = drawBlock(g, b, x0, right, y, a, first) + GAP;
                first = false;
            }
        }
    }

    private static float blockHeight(Block b) {
        float h = TOGGLE + 5;
        h += b.title().size() * TITLE_SIZE * 1.32f + (b.title().isEmpty() ? 0 : 2);
        float ch = COND_SIZE * 1.55f;
        if (b.done() && !collapsed) h += ch;
        for (var goal : b.goals()) h += goal.lines().size() * ch;
        return h + 9;
    }

    /** 의뢰 하나: 장식선 · (첫 칸만) 접기 버튼 · 책 · "의뢰 · C등급" / 제목 / 목표. 다음 칸의 y 를 돌려준다. */
    private static float drawBlock(GuiGraphics g, Block b, float x0, float right, float y0, int a, boolean first) {
        QuestArt.image("line").ifPresent(t -> {
            float lx0 = x0 + TOGGLE * 0.72f, ly0 = y0 - 9, ly1 = ly0 + 14;
            float k = 14f / t.height();
            float leftW = 60 * k, rightW = 64 * k;
            int c = argb(a, 0xFFFFFF);
            float u1 = 60f / t.width(), u2 = (t.width() - 64f) / t.width();
            QuestArt.quad(g, t, lx0, ly0, lx0 + leftW, ly1, 0, 0, u1, 1, c, c, c, c);
            QuestArt.quad(g, t, lx0 + leftW, ly0, right - rightW, ly1, u1, 0, u2, 1, c, c, c, c);
            QuestArt.quad(g, t, right - rightW, ly0, right, ly1, u2, 0, 1, 1, c, c, c, c);
        });
        if (first) {
            toggleX = x0; toggleY = y0;
            QuestArt.image(g, collapsed ? "toggle_plus" : "toggle_minus", x0, y0, TOGGLE, TOGGLE, argb(a, 0xFFFFFF));
        }
        float cx = x0 + TOGGLE + 5, row = y0 + TOGGLE / 2f + 1, book = 11;
        QuestArt.image(g, "book", cx, row - book / 2f, book, book * 0.94f, argb(a, 0xFFFFFF));
        if (b.done()) QuestArt.image(g, "glint", cx - 3.6f, row - book / 2f - 4.4f, 8, 8, argb(Math.round(a * 0.6f), 0xFFFFFF));
        QuestArt.textLeft(g, b.chapter(), cx + book + 4, row, CHAPTER_SIZE, argb(a, CHAPTER));
        float y = row + book / 2f + 5;
        if (collapsed) return y0 + blockHeight(b);
        for (String line : b.title()) {
            float lh = TITLE_SIZE * 1.32f;
            QuestArt.textLeft(g, line, cx, y + lh / 2f, TITLE_SIZE, argb(a, WHITE));
            y += lh;
        }
        y += 2;
        float ch = COND_SIZE * 1.55f;
        if (b.done()) {
            float icon = 9;
            QuestArt.image(g, "check", cx, y + ch / 2f - icon / 2f, icon, icon, argb(a, 0xFFFFFF));
            QuestArt.textLeft(g, "목표 완료 · 의뢰 창에서 보상 받기", cx + icon + 3, y + ch / 2f, COND_SIZE + 0.2f, argb(a, GOLD));
            return y0 + blockHeight(b);
        }
        for (var goal : b.goals()) {
            boolean ok = goal.ok();
            float tx = cx;
            if (ok) {
                float icon = 8;
                QuestArt.image(g, "check", cx, y + ch / 2f - icon / 2f, icon, icon, argb(a, 0xFFFFFF));
                tx += icon + 3;
            }
            for (String line : goal.lines()) {
                QuestArt.textLeft(g, line, tx, y + ch / 2f, COND_SIZE, argb(a, ok ? COND_DONE : COND));
                y += ch;
            }
        }
        return y0 + blockHeight(b);
    }

    private static HudEditing.Scope begin(GuiGraphics g, float x, float y, float w, float h) {
        try {
            var scope = (AutoCloseable) Class.forName("school.magiccodex.client.HudCustomization").getMethod("beginExternal", Object.class, String.class, float.class, float.class, float.class, float.class)
                    .invoke(null, g, HUD_ID, x, y, w, h);
            return () -> { try { scope.close(); } catch (Exception ignored) { } };
        } catch (ReflectiveOperationException | LinkageError e) { return () -> { }; }
    }

    private static double[] pointer(double x, double y) {
        try { return (double[]) Class.forName("school.magiccodex.client.HudCustomization").getMethod("pointerExternal", String.class, double.class, double.class).invoke(null, HUD_ID, x, y); }
        catch (ReflectiveOperationException | LinkageError e) { return new double[]{x, y}; }
    }

    private static int argb(int a, int rgb) { return (Mth.clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    /** 공백 기준 줄바꿈, 긴 단어는 글자 단위로. 최대 max 줄 (넘치면 …). */
    private static List<String> wrap(GuiGraphics g, String text, float size, float maxW, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (QuestArt.width(g, trial, size) <= maxW) { line.setLength(0); line.append(trial); continue; }
            if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
            if (QuestArt.width(g, word, size) <= maxW) { line.append(word); continue; }
            for (int i = 0; i < word.length(); i++) {
                String t = line.toString() + word.charAt(i);
                if (QuestArt.width(g, t, size) > maxW && !line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                line.append(word.charAt(i));
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > max) {
            List<String> cut = new ArrayList<>(out.subList(0, max));
            cut.set(max - 1, cut.get(max - 1) + "…");
            return cut;
        }
        return out;
    }
}
// [기능:subquest-hud-20261031 끝]
