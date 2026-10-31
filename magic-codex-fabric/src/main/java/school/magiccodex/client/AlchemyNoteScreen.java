package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.AlchemyLayout.Box;
import school.magiccodex.protocol.AlchemyNoteProtocol;

// [기능:alchemy-overhaul 시작] 연금 수첩 창 (2026-10-31, 새 파일)
/**
 * 연금 수첩. 오너 승인 그림 textures/gui/alchemy_note (남색 창·목록 줄·등급별 쪽지 4장). 1672x941 기준 좌표(연금술 창과 같은 맞춤).
 * 왼쪽: 모은 쪽지 목록(휠로 넘김). 오른쪽: 고른 쪽지를 그 등급 쪽지 그림 위에 펼쳐 보여 준다. 글은 서버가 만든 그대로.
 */
final class AlchemyNoteScreen extends Screen {
    record Art(String name, int width, int height, int frameX, int frameY, int frameW, int frameH) {}
    // 그림마다 캔버스 크기·여백이 달라서 manifest.json 의 보이는 영역(visible_bounds) 기준으로 맞춘다
    static final Art PANEL = new Art("notebook_panel", 1448, 1086, 34, 87, 1379, 907),
            ROW = new Art("entry_normal", 2172, 724, 187, 236, 1799, 249), ROW_SELECTED = new Art("entry_selected", 2172, 724, 190, 239, 1789, 244),
            CLOSE_ART = new Art("close", 1254, 1254, 390, 405, 474, 474), ICON = new Art("icon_note", 1254, 1254, 336, 245, 584, 788);
    static final Art[] NOTES = {
            new Art("note_1", 1134, 1387, 94, 54, 945, 1279), new Art("note_2", 1134, 1387, 83, 50, 968, 1270),
            new Art("note_3", 1134, 1387, 89, 41, 954, 1298), new Art("note_4", 1135, 1386, 122, 44, 892, 1291)};
    static final String[] GRADE = {"", "일반", "고급", "유일", "전설"};
    static final int[] GRADE_COLOR = {0, 0xFFD8CDB8, 0xFF8FC1FF, 0xFFC9A2FF, 0xFFFFD27A};
    static final int INK = 0xFF3A2A1C, INK_SOFT = 0xFF6A5640;

    static final Box FRAME = new Box(152, 20, 1368, 900);
    static final float TITLE_X = 206, TITLE_Y = 60;
    static final Box CLOSE = Box.centered(1462, 64, 46, 46);
    static final int ROWS = 9;
    static final float ROW_X = 196, ROW_Y = 130, ROW_W = 500, ROW_H = 69, ROW_STRIDE = 81;
    static final Box LIST = new Box(ROW_X, ROW_Y, ROW_W, ROWS * ROW_STRIDE - (ROW_STRIDE - ROW_H));
    static final Box NOTE = Box.centered(1124, 500, 556, 752);

    private final Screen parent;
    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private AlchemyNoteProtocol.State state;
    private int scroll, selected;
    private long openedAt, pickedAt;

    AlchemyNoteScreen(Screen parent, AlchemyNoteProtocol.State state) {
        super(Text.literal("연금 수첩"));
        this.parent = parent; update(state);
    }

    void update(AlchemyNoteProtocol.State s) {
        state = s;
        selected = 0;
        if (!s.focus().isEmpty()) for (int i = 0; i < s.entries().size(); i++) if (s.entries().get(i).id().equals(s.focus())) selected = i;
        scroll = Math.clamp(selected - ROWS / 2, 0, Math.max(0, s.entries().size() - ROWS));
        pickedAt = Util.getMeasuringTimeMs();
    }

    static Identifier tex(String name) { return Identifier.of("magiccodex", "textures/gui/alchemy_note/" + name + ".png"); }

    @Override protected void init() {
        if (openedAt == 0) { openedAt = Util.getMeasuringTimeMs(); sound(SoundEvents.ITEM_BOOK_PAGE_TURN, .9f, .6f); }
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { if (client != null) client.setScreen(parent); }

    @Override public void tick() { if (client == null || client.player == null || !client.player.isAlive()) close(); }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        c.fill(0, 0, width, height, 0x88070D13);
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            draw(c, PANEL, FRAME, AlchemyScreen.argb(.9f, 0xFFFFFF));
            AlchemyScreen.text(c, "연금 수첩", TITLE_X, TITLE_Y, 38, AlchemyScreen.TITLE_GOLD, AlchemyScreen.TITLE, false);
            var entries = state.entries();
            String count = "모은 쪽지 " + entries.size() + "장";
            AlchemyScreen.text(c, count, ROW_X + ROW_W - UiResources.text().width(count, 21, AlchemyScreen.LABEL), TITLE_Y + 10, 21, AlchemyScreen.MUTED, AlchemyScreen.LABEL, false);
            draw(c, CLOSE_ART, CLOSE, CLOSE.contains(mx, my) ? 0xFFFFFFFF : 0xD8FFFFFF);
            list(c, mx, my, entries);
            if (entries.isEmpty()) {
                AlchemyScreen.text(c, "아직 모은 쪽지가 없습니다.", NOTE.cx(), NOTE.cy() - 20, 26, AlchemyScreen.IVORY, AlchemyScreen.BOLD, true);
                AlchemyScreen.text(c, "연금 쪽지를 손에 들고 우클릭하면 이곳에 적힙니다.", NOTE.cx(), NOTE.cy() + 20, 21, AlchemyScreen.MUTED, AlchemyScreen.BODY, true);
            } else note(c, entries.get(Math.clamp(selected, 0, entries.size() - 1)));
        } finally { m.pop(); images.endFrame(); }
        entrance.draw(c, width, height);
    }

    private void list(DrawContext c, double mx, double my, List<AlchemyNoteProtocol.Entry> entries) {
        scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() - ROWS));
        for (int v = 0; v < ROWS; v++) {
            int i = scroll + v;
            if (i >= entries.size()) break;
            var e = entries.get(i); var box = row(v);
            boolean sel = i == selected, hover = box.contains(mx, my);
            draw(c, sel ? ROW_SELECTED : ROW, box, sel ? 0xFFFFFFFF : hover ? 0xF0FFFFFF : 0xC8FFFFFF);
            draw(c, ICON, Box.centered(box.x() + 36, box.cy(), 24, 32), argbGrade(e.grade()));
            float size = AlchemyScreen.fit(e.title(), 24, AlchemyScreen.BOLD, ROW_W - 190);
            AlchemyScreen.text(c, e.title(), box.x() + 64, box.cy() - size / 2 - 1, size, e.discovered() ? AlchemyScreen.IVORY : AlchemyScreen.TEXT, AlchemyScreen.BOLD, false);
            String tag = e.discovered() ? "발견" : GRADE[e.grade()];
            float tw = UiResources.text().width(tag, 19, AlchemyScreen.LABEL);
            AlchemyScreen.text(c, tag, box.right() - 26 - tw, box.cy() - 10, 19, e.discovered() ? AlchemyScreen.EXP : GRADE_COLOR[e.grade()], AlchemyScreen.LABEL, false);
        }
        if (entries.size() > ROWS) {
            float h = LIST.h() * ROWS / entries.size(), y = LIST.y() + (LIST.h() - h) * scroll / Math.max(1, entries.size() - ROWS);
            HudMesh.line(c, LIST.right() + 14, y, LIST.right() + 14, y + h, 3, 0x90E7C77E);
        }
    }

    private void note(DrawContext c, AlchemyNoteProtocol.Entry e) {
        float t = Math.min(1, (Util.getMeasuringTimeMs() - pickedAt) / 220f);
        var art = NOTES[e.grade() - 1];
        var m = c.getMatrices(); m.push();
        try {
            m.translate(NOTE.cx(), NOTE.cy() + (1 - t) * 18, 0);
            m.translate(-NOTE.cx(), -NOTE.cy(), 0);
            draw(c, art, NOTE, AlchemyScreen.argb(t, 0xFFFFFF));
            // 테두리·봉인을 피한 안쪽 (쪽지 그림 기준 대략 가로 17%~83%, 세로 17%~80%)
            float left = NOTE.x() + NOTE.w() * .17f, right = NOTE.x() + NOTE.w() * .83f, top = NOTE.y() + NOTE.h() * .17f, max = right - left;
            float y = top;
            float ts = AlchemyScreen.fit(e.title(), 30, AlchemyScreen.TITLE, max);
            AlchemyScreen.text(c, e.title(), NOTE.cx(), y, ts, AlchemyScreen.argb(t, INK), AlchemyScreen.TITLE, true);
            y += ts + 10;
            AlchemyScreen.text(c, GRADE[e.grade()] + " 쪽지", NOTE.cx(), y, 18, AlchemyScreen.argb(t, INK_SOFT), AlchemyScreen.LABEL, true);
            y += 38;
            float bottom = NOTE.y() + NOTE.h() * .80f;
            for (String line : e.lines()) for (String part : wrap(line, 21, max)) {
                if (y + 22 > bottom) break;
                AlchemyScreen.text(c, part, left, y, 21, AlchemyScreen.argb(t, INK), AlchemyScreen.BODY, false);
                y += 30;
            }
            if (!e.firstBy().isEmpty() && y + 22 <= bottom)
                AlchemyScreen.text(c, "최초 발견: " + e.firstBy(), NOTE.cx(), bottom - 6, 18, AlchemyScreen.argb(t, INK_SOFT), AlchemyScreen.LABEL, true);
        } finally { m.pop(); }
    }

    /** 글자 단위 줄바꿈 (한국어 문장이라 띄어쓰기 우선, 안 되면 글자에서 자른다). */
    static List<String> wrap(String value, float size, float max) {
        var out = new ArrayList<String>();
        String rest = value.strip();
        while (!rest.isEmpty()) {
            if (UiResources.text().width(rest, size, AlchemyScreen.BODY) <= max) { out.add(rest); break; }
            int cut = rest.length();
            while (cut > 1 && UiResources.text().width(rest.substring(0, cut), size, AlchemyScreen.BODY) > max) cut--;
            int space = rest.lastIndexOf(' ', cut);
            if (space > cut / 2) cut = space;
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        return out;
    }

    private static int argbGrade(int grade) { return GRADE_COLOR[Math.clamp(grade, 1, 4)]; }
    static Box row(int v) { return new Box(ROW_X, ROW_Y + v * ROW_STRIDE, ROW_W, ROW_H); }

    /** 그림 전체를 보이는 영역이 target 에 꼭 맞게 늘려 그린다 (AlchemyScreen.drawArt 와 같은 규칙). */
    static void draw(DrawContext c, Art a, Box target, int tint) {
        float sx = target.w() / a.frameW(), sy = target.h() / a.frameH();
        int x = Math.round(target.x() - a.frameX() * sx), y = Math.round(target.y() - a.frameY() * sy);
        int w = Math.round(a.width() * sx), h = Math.round(a.height() * sy);
        UiResources.images().drawTexture(c, tex(a.name()), x, y, 0, 0, w, h, a.width(), a.height(), a.width(), a.height(), tint);
    }

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double x, double y, int button) {
        var l = CodexLayout.codexFit(width, height);
        double mx = l.localX(x), my = l.localY(y);
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (CLOSE.contains(mx, my)) { sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.1f, .25f); close(); return true; }
        var entries = state.entries();
        for (int v = 0; v < ROWS; v++) {
            int i = scroll + v;
            if (i < entries.size() && row(v).contains(mx, my) && i != selected) {
                selected = i; pickedAt = Util.getMeasuringTimeMs();
                sound(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.2f, .5f);
                return true;
            }
        }
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) scroll = Math.max(0, scroll - (vertical > 0 ? 1 : -1));
        return true;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sound(SoundEvent event, float pitch, float volume) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
    }
}
// [기능:alchemy-overhaul 끝]
