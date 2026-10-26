package school.magiccodex.client;

import java.util.*;

/**
 * Alchemy window geometry in the 1672x941 reference space of textures/gui/alchemy/panel.png (no Minecraft types, so the
 * no-overlap rules are unit-tested). Left frame inner area 148..1030 x 106..872, right frame 1045..1527 x 106..872.
 *
 * Left: the circle (static art; its six beams end where the six slots sit), the result ring in the middle, then the
 * message line, the experience line and the 합성 button underneath. Right: 레시피 header, search, the "보유 재료로 제작
 * 가능" checkbox with the discovered count, six recipe rows (scrolls), and the footer hint.
 */
final class AlchemyLayout {
    private AlchemyLayout() {}

    record Box(float x, float y, float w, float h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
        float cx() { return x + w / 2; }
        float cy() { return y + h / 2; }
        float right() { return x + w; }
        float bottom() { return y + h; }
        boolean overlaps(Box o) { return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom(); }
        static Box centered(float cx, float cy, float w, float h) { return new Box(cx - w / 2, cy - h / 2, w, h); }
    }

    /** One texture of textures/gui/alchemy/ with the box of its visible frame (glow margins excluded). */
    record Art(String name, int width, int height, int frameX, int frameY, int frameW, int frameH) {}
    static final Art SLOT = new Art("slot", 232, 214, 3, 4, 226, 208), SLOT_HOVER = new Art("slot_hover", 266, 254, 19, 23, 228, 211),
            RING = new Art("result_ring", 266, 260, 4, 4, 258, 252),
            ROW = new Art("row", 526, 113, 2, 4, 521, 105), ROW_LOCKED = new Art("row_locked", 514, 111, 3, 3, 510, 104),
            ROW_SELECTED = new Art("row_selected", 546, 139, 13, 15, 522, 108),
            SEARCH = new Art("search_field", 564, 114, 2, 2, 560, 110),
            BUTTON = new Art("button", 554, 141, 2, 6, 551, 131), BUTTON_HOVER = new Art("button_hover", 599, 184, 21, 22, 559, 137),
            CHECK_OFF = new Art("checkbox_off", 60, 59, 1, 0, 58, 58), CHECK_ON = new Art("checkbox_on", 74, 72, 7, 7, 59, 58),
            LOCK = new Art("lock_icon", 58, 70, 2, 1, 55, 67);

    static final float W = 1672, H = 941;
    static final Box LEFT = new Box(148, 106, 882, 766), RIGHT = new Box(1045, 106, 482, 766);

    // ---- header (title sits in the panel art's top plaque, the X is baked into the art at 1506,84)
    static final float TITLE_X = 836, TITLE_Y = 58;
    static final Box CLOSE = Box.centered(1506, 84, 56, 56);

    // ---- left: circle, slots, result
    static final float CIRCLE_X = 589, CIRCLE_Y = 398, CIRCLE_SIZE = 560;
    /** Slot frames sit on the art's beams: 55 degrees above/below the horizontal and on the horizontal itself. */
    static final float SLOT_RADIUS = 228, SLOT_W = 114, SLOT_H = 105;
    static final float[] SLOT_ANGLES = {125, 55, 0, -55, -125, 180};
    static final Box RESULT = Box.centered(CIRCLE_X, CIRCLE_Y, 176, 172);
    static final float RESULT_ITEM = 76;
    static final float MESSAGE_Y = 708, EXP_Y = 748;
    static final Box SYNTH = Box.centered(CIRCLE_X, 812, 300, 71);

    /** Slot frame for 1..8 slots: the art's six positions when six, otherwise evenly round from the top. */
    static Box slot(int index, int count) {
        double angle = count == 6 ? SLOT_ANGLES[index] : 90 - 360.0 * index / count;
        float cx = CIRCLE_X + (float) (SLOT_RADIUS * Math.cos(Math.toRadians(angle)));
        float cy = CIRCLE_Y - (float) (SLOT_RADIUS * Math.sin(Math.toRadians(angle)));
        return Box.centered(cx, cy, SLOT_W, SLOT_H);
    }
    /** Where the beam from a slot meets the result ring (for the glow line). */
    static float[] beamEnd(int index, int count) {
        var s = slot(index, count);
        double dx = s.cx() - CIRCLE_X, dy = s.cy() - CIRCLE_Y, len = Math.hypot(dx, dy);
        float r = 86;
        return new float[]{CIRCLE_X + (float) (dx / len * r), CIRCLE_Y + (float) (dy / len * r)};
    }

    // ---- material picker (modal over the circle)
    static final Box PICKER = new Box(230, 150, 718, 540);
    static final int PICK_COLUMNS = 8, PICK_ROWS = 5, PICK_CELL = 76, PICK_STRIDE = 84;
    static final float PICK_TITLE_Y = 186, PICK_HINT_Y = 664;
    static final Box PICK_CLOSE = Box.centered(912, 186, 44, 44);
    static Box pickCell(int index) {
        int col = index % PICK_COLUMNS, row = index / PICK_COLUMNS;
        float left = PICKER.cx() - (PICK_COLUMNS * PICK_STRIDE - (PICK_STRIDE - PICK_CELL)) / 2f;
        return new Box(left + col * PICK_STRIDE, 218 + row * PICK_STRIDE, PICK_CELL, PICK_CELL);
    }

    // ---- right: recipes
    static final float HEADER_Y = 150;
    static final Box SEARCH_BOX = new Box(1072, 182, 428, 50);
    static final Box CHECK = new Box(1074, 252, 28, 28), CHECK_HIT = new Box(1068, 246, 286, 40);
    static final float CHECK_LABEL_X = 1114, COUNT_RIGHT = 1500;
    static final int ROWS = 6;
    static final float ROW_X = 1068, ROW_Y = 298, ROW_W = 418, ROW_H = 72, ROW_STRIDE = 82;
    static final Box LIST = new Box(ROW_X, ROW_Y, ROW_W + 20, ROWS * ROW_STRIDE - (ROW_STRIDE - ROW_H));
    static final Box SCROLL = new Box(1494, ROW_Y, 4, LIST.h());
    static Box row(int visibleIndex) { return new Box(ROW_X, ROW_Y + visibleIndex * ROW_STRIDE, ROW_W, ROW_H); }
    static final float FOOTER_LINE_Y = 806, FOOTER_Y = 836;

    /** Named boxes that must never overlap each other (checked by AlchemyLayoutTest). */
    static Map<String, Box> parts(int slots) {
        var map = new LinkedHashMap<String, Box>();
        for (int i = 0; i < slots; i++) map.put("slot" + i, slot(i, slots));
        map.put("result", RESULT);
        map.put("message", Box.centered(CIRCLE_X, MESSAGE_Y, 840, 26));
        map.put("exp", Box.centered(CIRCLE_X, EXP_Y, 840, 30));
        map.put("synth", SYNTH);
        map.put("header", Box.centered(RIGHT.cx(), HEADER_Y, 440, 40));
        map.put("search", SEARCH_BOX);
        map.put("check", CHECK_HIT);
        map.put("count", new Box(1380, 252, COUNT_RIGHT - 1380, 28));
        for (int i = 0; i < ROWS; i++) map.put("row" + i, row(i));
        map.put("scroll", SCROLL);
        map.put("footer", Box.centered(RIGHT.cx(), FOOTER_Y, 440, 24));
        map.put("close", CLOSE);
        return map;
    }
}
