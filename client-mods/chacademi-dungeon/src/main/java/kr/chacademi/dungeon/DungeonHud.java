package kr.chacademi.dungeon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 던전 안 HUD 두 개 (서버가 던전 상태를 보내는 동안만):
 * <ul>
 *   <li>던전 메뉴 (오른쪽 위): 던전 이름 · 모래시계 + 경과 시간 · 나가기 버튼 — hud-panel 9-slice</li>
 *   <li>던전 단계창: 스토리 퀘스트창과 같은 모양을 은색으로 (장식선·책 아이콘·단계·목표·달성 수치). 스토리 퀘스트는 그대로 따로 보인다.</li>
 * </ul>
 * 둘 다 MagicCodex HUD 편집 id("dungeon_menu", "dungeon_quest")로 그려 사용자가 옮길 수 있다.
 */
final class DungeonHud {
    private DungeonHud() {}

    static final String MENU_ID = "dungeon_menu", QUEST_ID = "dungeon_quest";
    private static final float MENU_W = 132, MENU_H = 46;
    private static final float EXIT_W = 44, EXIT_H = 15;
    private static final float QW = 158, LINE_H = 14, DESC = 10.5f, CHAPTER = 7.2f, COND = 7.4f;
    private static final int WHITE = 0xFFFFFFFF, SILVER_TEXT = 0xFFE3E8EF, COND_TEXT = 0xFFEDEDED, DONE = 0xFFB7B7B7, SILVER_HI = 0xFFF2F6FA, WARN = 0xFFF0A8A8;
    /** 나가기 버튼 (메뉴 원래 좌표). */
    private static float exitX = -100, exitY = -100;

    static boolean hitExit(double mx, double my) {
        if (!DungeonClient.run.active() || HudLink.editing()) return false;
        var p = HudLink.pointer(MENU_ID, mx, my);
        return p[0] >= exitX && p[0] <= exitX + EXIT_W && p[1] >= exitY && p[1] <= exitY + EXIT_H;
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        var mc = Minecraft.getInstance();
        var run = DungeonClient.run;
        if (!run.active() || mc.player == null || mc.options.hideGui) { exitX = -100; return; }
        if (mc.screen != null && !DungeonClient.hudScreen(mc.screen) && !(mc.screen instanceof UiScreens.ExitConfirm)) { exitX = -100; return; }
        float sw = g.guiWidth(), sh = g.guiHeight();
        float[] quest = HudLink.box("quest"), party = HudLink.box("party");
        float[] menu = HudPlacement.menu(sw, sh, MENU_W, MENU_H, quest, party);
        drawMenu(g, mc, menu[0], menu[1]);
        var lines = questLines(g);
        float h = questHeight(lines);
        float[] actualMenu = HudLink.box(MENU_ID);
        float[] q = HudPlacement.quest(sw, sh, QW, h, quest, actualMenu != null ? actualMenu : menu, party);
        drawQuest(g, q[0], q[1], h, lines);
    }

    private static void drawMenu(GuiGraphics g, Minecraft mc, float x, float y) {
        var run = DungeonClient.run;
        try (var scope = HudLink.begin(g, MENU_ID, x, y, MENU_W, MENU_H)) {
            Art.nine(g, "panels/hud-panel", x, y, MENU_W, MENU_H, 28, 7, 0xF2FFFFFF);
            Art.text(g, Art.fit(g, run.name(), 8, MENU_W - 16), x + 8, y + 11, 8, SILVER_HI);
            Art.ui(g, "icons/hourglass", x + 7, y + 22, 13, 13, 0xFFFFFFFF);
            String time = Net.time(DungeonClient.elapsed());
            Art.text(g, run.timing() ? time : "준비 중", x + 22, y + 29, 10, run.timing() ? 0xFFFFE08A : 0xFFB9C2CE);
            exitX = x + MENU_W - EXIT_W - 6;
            exitY = y + 22;
            double[] p = mc.screen != null ? HudLink.pointer(MENU_ID, mouseX(mc), mouseY(mc)) : new double[]{-1, -1};
            boolean hover = mc.screen != null && p[0] >= exitX && p[0] <= exitX + EXIT_W && p[1] >= exitY && p[1] <= exitY + EXIT_H;
            Art.hslice(g, "buttons/danger", exitX, exitY, EXIT_W, EXIT_H, 48, hover ? 0xFFFFFFFF : 0xE6D8D8D8);
            Art.ui(g, "icons/exit", exitX + 5, exitY + 3.5f, 8, 8, 0xFFFFFFFF);
            Art.text(g, "나가기", exitX + 15, exitY + EXIT_H / 2 + .5f, 7, WHITE);
        }
    }

    private static double mouseX(Minecraft mc) { return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth(); }
    private static double mouseY(Minecraft mc) { return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight(); }

    private record Lines(String chapter, List<String> desc, List<String> cond, boolean done, boolean locked) {}

    private static Lines questLines(GuiGraphics g) {
        var r = DungeonClient.run;
        String chapter = "던전 · " + r.stage() + " / " + r.stages() + " 단계";
        String title = r.stageName().isBlank() ? r.name() : r.stageName();
        float textW = QW - 13 - 5;
        String cond = r.goal() + (r.required() > 0 ? " " + Math.min(r.current(), r.required()) + "/" + r.required() : "");
        boolean done = r.required() > 0 && r.current() >= r.required();
        return new Lines(chapter, wrap(g, title, DESC, textW), wrap(g, cond, COND, textW - (done ? 11 : 0)), done, r.locked());
    }

    private static float questHeight(Lines l) {
        return 13 + 12 + l.desc().size() * DESC * 1.32f + 2 + l.cond().size() * COND * 1.55f + (l.locked() ? COND * 1.55f : 0) + 4;
    }

    /** 스토리 퀘스트창(QuestHud)과 같은 배치, 색만 은색. */
    private static void drawQuest(GuiGraphics g, float x0, float top, float h, Lines l) {
        float y0 = top + 9, right = x0 + QW, toggle = 13;
        try (var scope = HudLink.begin(g, QUEST_ID, x0, top, QW, h)) {
            Art.quest("line", true).ifPresent(t -> {
                float lx0 = x0 + toggle * 0.72f, ly0 = y0 - 9, ly1 = ly0 + LINE_H;
                float k = LINE_H / t.height();
                float leftW = 60 * k, rightW = 64 * k;
                float u1 = 60f / t.width(), u2 = (t.width() - 64f) / t.width();
                Art.quad(g, t, lx0, ly0, lx0 + leftW, ly1, 0, 0, u1, 1, WHITE);
                Art.quad(g, t, lx0 + leftW, ly0, right - rightW, ly1, u1, 0, u2, 1, WHITE);
                Art.quad(g, t, right - rightW, ly0, right, ly1, u2, 0, 1, 1, WHITE);
            });
            // 스토리 창의 접기 버튼 자리: 던전 표시(룬)
            Art.ui(g, "icons/rune", x0, y0, toggle, toggle, 0xFFE6ECF4);
            float cx = x0 + toggle + 5, row = y0 + toggle / 2f + 1, book = 11;
            Art.image(g, Art.quest("book", true), cx, row - book / 2f, book, book * 0.94f, WHITE);
            Art.text(g, l.chapter(), cx + book + 4, row, CHAPTER, SILVER_TEXT);
            float y = row + book / 2f + 5;
            for (String line : l.desc()) { float lh = DESC * 1.32f; Art.text(g, line, cx, y + lh / 2f, DESC, WHITE); y += lh; }
            y += 2;
            float ch = COND * 1.55f, tx = cx;
            if (l.done()) { Art.image(g, Art.quest("check", true), cx, y + ch / 2f - 4, 8, 8, WHITE); tx += 11; }
            for (String line : l.cond()) { Art.text(g, line, tx, y + ch / 2f, COND, l.done() ? DONE : COND_TEXT); y += ch; }
            if (l.locked()) Art.text(g, "출구가 봉인되었어요", cx, y + ch / 2f, COND, WARN);
        }
    }

    private static List<String> wrap(GuiGraphics g, String text, float size, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (Art.width(g, trial, size) <= maxW) { line.setLength(0); line.append(trial); continue; }
            if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
            if (Art.width(g, word, size) <= maxW) { line.append(word); continue; }
            for (int i = 0; i < word.length(); i++) {
                String t = line.toString() + word.charAt(i);
                if (Art.width(g, t, size) > maxW && !line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                line.append(word.charAt(i));
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > 3) { var cut = new ArrayList<>(out.subList(0, 3)); cut.set(2, cut.get(2) + "…"); return cut; }
        return out;
    }
}
