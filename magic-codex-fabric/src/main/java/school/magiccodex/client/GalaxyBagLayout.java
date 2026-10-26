package school.magiccodex.client;

/**
 * 아공간 배낭 inventory layout (2026-10-24). No Minecraft types: placement, animation and hit tests only.
 *
 * Owner rule (2026-10-24): exactly the reference picture (1672x941, bag + crafting panel open). Every position and
 * size is measured from it; the two border PNGs are laid on the reference outlines (visible box to visible box).
 * The bag sits top-centre; when the crafting panel opens the bag slides left and the panel slides in on its right,
 * ending exactly at the reference layout.
 */
final class GalaxyBagLayout {
    private GalaxyBagLayout() {}

    static final int CANVAS_W = 1735, CANVAS_H = 941;

    // ---- bag border (bag_galaxy_border.png 1817x866, visible 13..1804 x 33..842) placed on the reference bag
    static final int BAG_PNG_W = 1817, BAG_PNG_H = 866;
    /**
     * The provided PNG's outline is shaped a little differently from the reference picture (dips at top/bottom centre,
     * narrower top-right). At this uniform scale every slot ring sits on the dark glass and the two round buttons and
     * the pager sit inside the outline, as in the reference (found from the PNG alpha). Slots keep reference size.
     */
    static final float BAG_SX = 0.71f, BAG_SY = 0.71f, BAG_X = -4, BAG_Y = 2;
    static final float BAG_W = BAG_PNG_W * BAG_SX, BAG_H = BAG_PNG_H * BAG_SY;
    /** Visible outline of the bag on the canvas (PNG visible bbox at this scale). */
    static final float BAG_LEFT = 13 * BAG_SX + BAG_X, BAG_RIGHT = 1804 * BAG_SX + BAG_X, BAG_TOP = 33 * BAG_SY + BAG_Y, BAG_BOTTOM = 842 * BAG_SY + BAG_Y;
    /** Shift that centres the bag alone (crafting closed): canvas centre minus the bag's visible centre. */
    static final float BAG_ALONE_SHIFT = CANVAS_W / 2f - (BAG_LEFT + BAG_RIGHT) / 2f;

    // ---- crafting panel (craft_panel_border.png 1108x1420, visible 34..1075 x 43..1380)
    static final int PANEL_PNG_W = 1108, PANEL_PNG_H = 1420;
    /** Reference panel (1185..1630 x 52..630) moved right by PANEL_DX so it sits beside the bag as in the reference. */
    static final float PANEL_DX = 100;
    static final float PANEL_SX = 445f / 1041f, PANEL_SY = 578f / 1337f, PANEL_X = 1185 + PANEL_DX - 34 * PANEL_SX, PANEL_Y = 52 - 43 * PANEL_SY;
    static final float PANEL_W = PANEL_PNG_W * PANEL_SX, PANEL_H = PANEL_PNG_H * PANEL_SY;
    static final float PANEL_LEFT = 1185 + PANEL_DX, PANEL_RIGHT = 1630 + PANEL_DX, PANEL_TOP = 52, PANEL_BOTTOM = 630;
    /** Panel enters from the right by this much while it fades in. */
    static final float PANEL_SLIDE = 48;
    static final float OPEN_SECONDS = 0.24f;

    // ---- bag contents (item sizes: >= 72 device px at 1920x1080 so 256px art shrinks cleanly, owner 2026-10-24)
    static final float MAIN_X0 = 234, MAIN_STEP_X = 97.6f, MAIN_Y0 = 163.5f, MAIN_STEP_Y = 87.75f, MAIN_R = 39, MAIN_ITEM = 66;
    static final float HOTBAR_X0 = 272.5f, HOTBAR_STEP = 89f, HOTBAR_Y = 444, HOTBAR_R = 35, HOTBAR_ITEM = 64, HOTBAR_NUMBER_Y = 494;
    static final float DIVIDER_Y = 392.5f, DIVIDER_X0 = 194, DIVIDER_X1 = 1062.5f;
    static final float PAGER_Y = 539.5f, PAGER_PREV_X = 532.5f, PAGER_NEXT_X = 713.5f, PAGER_LABEL_X = 623, PAGER_R = 22.5f;
    static final float EQUIP_X = 1059, EQUIP_Y = 106, CRAFT_BTN_X = 1115, CRAFT_BTN_Y = 150, ROUND_BTN_R = 29;
    // locked page (owner's lock reference). 2026-10-26 오너: 자물쇠·"잠긴 공간"·안내 문구 묶음은 27칸 영역(핫바 제외)의
    // 가로·세로 정중앙, 핫바 줄은 잠긴 쪽에서도 그대로. 제목 "아공간 배낭"은 한 번만 — 테두리 선과 첫 줄 링 사이 가운데라
    // 링·자물쇠와 겹치지 않는다 (예전 y=103 은 첫 줄 링 바로 위에 붙어 겹쳐 보였다).
    static final float LOCK_ICON = 120;
    /** locked_page_padlock.png(384) 의 실제 그림(알파) 범위 y 54..335 를 120 크기로 — 빈 여백 말고 그림 기준으로 가운데. */
    static final float LOCK_INK_TOP = 54 / 384f * LOCK_ICON, LOCK_INK_H = (335 - 54) / 384f * LOCK_ICON;
    /** 자물쇠 그림 아래 → "잠긴 공간", "잠긴 공간" → 안내 문구 사이 간격 (예전 배치의 간격 그대로). */
    static final float LOCK_GAP_TEXT = 28, LOCK_GAP_HINT = 12;
    /** 테두리 PNG 가운데 열의 위쪽 선(PNG 행 60)이 놓이는 캔버스 y. */
    static final float BORDER_TOP_Y = 60 * BAG_SY + BAG_Y;

    /** 27칸 링이 차지하는 영역 (링 바깥 끝 기준, 핫바 제외): {left, top, right, bottom}. */
    static float[] mainArea() {
        return new float[]{MAIN_X0 - MAIN_R, MAIN_Y0 - MAIN_R, MAIN_X0 + 8 * MAIN_STEP_X + MAIN_R, MAIN_Y0 + 2 * MAIN_STEP_Y + MAIN_R};
    }

    /** 잠긴 쪽 제목 "아공간 배낭"의 세로 가운데: 테두리 위쪽 선과 첫 줄 링 위끝 사이 가운데. */
    static float lockTitleY() { return (BORDER_TOP_Y + mainArea()[1]) / 2; }

    /** 잠긴 쪽 묶음 위치. iconY = 자물쇠 그림 상자(LOCK_ICON)의 가운데, textY/hintY = 글자 잉크의 가운데. */
    record LockStack(float cx, float iconY, float textY, float hintY, float top, float bottom) {}

    /**
     * 자물쇠 그림 + 간격 + "잠긴 공간"(잉크 높이 textH) + 간격 + 안내 문구(잉크 높이 hintH) 를 한 덩어리로 보고
     * 그 덩어리의 가운데를 27칸 영역의 가운데에 맞춘다. top/bottom = 덩어리의 실제 위·아래 끝.
     */
    static LockStack lockStack(float textH, float hintH) {
        float[] a = mainArea();
        float cx = (a[0] + a[2]) / 2, cy = (a[1] + a[3]) / 2;
        float total = LOCK_INK_H + LOCK_GAP_TEXT + textH + LOCK_GAP_HINT + hintH;
        float top = cy - total / 2;
        float iconY = top - LOCK_INK_TOP + LOCK_ICON / 2;
        float textY = top + LOCK_INK_H + LOCK_GAP_TEXT + textH / 2;
        float hintY = textY + textH / 2 + LOCK_GAP_HINT + hintH / 2;
        return new LockStack(cx, iconY, textY, hintY, top, top + total);
    }

    // ---- crafting panel contents
    static final float CRAFT_TITLE_X = 1250 + PANEL_DX, CRAFT_TITLE_Y = 100, BOOK_X = 1361 + PANEL_DX, BOOK_Y = 96, BOOK_SIZE = 52;
    static final float CLOSE_X = 1579 + PANEL_DX, CLOSE_Y = 100, CLOSE_R = 23;
    static final float TITLE_RULE_Y = 127, TITLE_RULE_X0 = 1217 + PANEL_DX, TITLE_RULE_X1 = 1325 + PANEL_DX;
    static final float[][] INPUTS = {{1273 + PANEL_DX, 177}, {1358 + PANEL_DX, 177}, {1273 + PANEL_DX, 262}, {1358 + PANEL_DX, 262}};
    static final float INPUT_R = 37.5f, INPUT_ITEM = 64;
    static final float RESULT_X = 1542 + PANEL_DX, RESULT_Y = 217, RESULT_R = 51, RESULT_ITEM = 76;
    static final float ARROW_X = 1444 + PANEL_DX, ARROW_Y = 217, ARROW_LEN = 55;
    static final float SECTION_RULE_Y = 315, SECTION_RULE_X0 = 1217 + PANEL_DX, SECTION_RULE_X1 = 1605 + PANEL_DX;
    static final float RECIPE_TITLE_X = 1252 + PANEL_DX, RECIPE_TITLE_Y = 352;
    static final float SEARCH_X0 = 1315 + PANEL_DX, SEARCH_X1 = 1465 + PANEL_DX, SEARCH_Y0 = 332, SEARCH_Y1 = 367;
    static final float CRAFTABLE_LABEL_X = 1515 + PANEL_DX, CRAFTABLE_LABEL_Y = 351;
    static final float TOGGLE_X0 = 1564 + PANEL_DX, TOGGLE_X1 = 1610 + PANEL_DX, TOGGLE_Y0 = 340, TOGGLE_Y1 = 364;
    static final float[] CARD_X = {1227 + PANEL_DX, 1355 + PANEL_DX, 1482 + PANEL_DX}, CARD_Y = {386, 487};
    static final float CARD_W = 116, CARD_H = 88, CARD_ITEM = 68;
    static final int CARDS_PER_PAGE = 6;
    static final float RECIPE_PAGER_Y = 600, RECIPE_PREV_X = 1345 + PANEL_DX, RECIPE_NEXT_X = 1475 + PANEL_DX, RECIPE_LABEL_X = 1410 + PANEL_DX, RECIPE_PAGER_R = 17;

    // ---- PlayerScreenHandler slot ids
    static final int RESULT_SLOT = 0, INPUT_START = 1, MAIN_START = 9, HOTBAR_START = 36;

    enum Kind { RESULT, INPUT, MAIN, HOTBAR }
    record Element(int slotId, Kind kind, float cx, float cy, float radius, float itemSize) {
        boolean inPanel() { return kind == Kind.RESULT || kind == Kind.INPUT; }
    }

    private static final Element[] BY_SLOT = new Element[46];
    static {
        BY_SLOT[RESULT_SLOT] = new Element(RESULT_SLOT, Kind.RESULT, RESULT_X, RESULT_Y, RESULT_R, RESULT_ITEM);
        for (int i = 0; i < 4; i++) BY_SLOT[INPUT_START + i] = new Element(INPUT_START + i, Kind.INPUT, INPUTS[i][0], INPUTS[i][1], INPUT_R, INPUT_ITEM);
        for (int i = 0; i < 27; i++)
            BY_SLOT[MAIN_START + i] = new Element(MAIN_START + i, Kind.MAIN, MAIN_X0 + (i % 9) * MAIN_STEP_X, MAIN_Y0 + (i / 9) * MAIN_STEP_Y, MAIN_R, MAIN_ITEM);
        for (int i = 0; i < 9; i++) BY_SLOT[HOTBAR_START + i] = new Element(HOTBAR_START + i, Kind.HOTBAR, HOTBAR_X0 + i * HOTBAR_STEP, HOTBAR_Y, HOTBAR_R, HOTBAR_ITEM);
    }

    /** Element for a handler slot id, or null (armour 5..8 and offhand 45 are not shown). */
    static Element element(int slotId) { return slotId >= 0 && slotId < BY_SLOT.length ? BY_SLOT[slotId] : null; }

    static java.util.List<Element> elements() {
        var out = new java.util.ArrayList<Element>();
        for (Element e : BY_SLOT) if (e != null) out.add(e);
        return out;
    }

    // ---- window fit: whole canvas, uniform, top aligned (the bag is a top-centre window)

    record Fit(float x, float y, float scale) {
        double localX(double screenX) { return (screenX - x) / scale; }
        double localY(double screenY) { return (screenY - y) / scale; }
    }

    static Fit fit(int width, int height) {
        float scale = Math.min((float) width / CANVAS_W, (float) height / CANVAS_H);
        if (!(scale > 0)) scale = 1;
        return new Fit((width - CANVAS_W * scale) / 2f, 0, scale);
    }

    // ---- crafting panel animation

    static float approach(float open, boolean wanted, float dt) {
        float step = dt / OPEN_SECONDS;
        return wanted ? Math.min(1, open + step) : Math.max(0, open - step);
    }

    /** Smooth step 0..1. */
    static float ease(float t) { t = Math.clamp(t, 0f, 1f); return t * t * (3 - 2 * t); }

    /** Horizontal offset of everything in the bag for panel progress t (0 closed .. 1 open). */
    static float bagShift(float t) { return BAG_ALONE_SHIFT * (1 - ease(t)); }

    /** Horizontal offset of the panel (slides in from the right). */
    static float panelShift(float t) { return PANEL_SLIDE * (1 - ease(t)); }

    /** Panel slots take clicks only once the panel is mostly in place. */
    static boolean panelInteractive(float t) { return t > 0.85f; }

    static float shiftOf(Element e, float t) { return e.inPanel() ? panelShift(t) : bagShift(t); }

    // ---- hit tests (canvas coordinates; mx, my already converted with Fit)

    static boolean circle(double mx, double my, float cx, float cy, float r) {
        double dx = mx - cx, dy = my - cy;
        return dx * dx + dy * dy <= (double) r * r;
    }

    static boolean rect(double mx, double my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    /** Slot hit: a slightly generous circle so the gaps between rings are not dead zones on small windows. */
    static boolean hit(Element e, double mx, double my, float t) {
        return circle(mx, my, e.cx() + shiftOf(e, t), e.cy(), e.radius() + 4);
    }

    /** Inside the bag outline (rounded box of the visible border, a little inside the glow). */
    static boolean insideBag(double mx, double my, float t) {
        float s = bagShift(t);
        return roundedBox(mx - s, my, BAG_LEFT + 10, BAG_TOP + 10, BAG_RIGHT - 10, BAG_BOTTOM - 10, 150);
    }

    static boolean insidePanel(double mx, double my, float t) {
        if (t <= 0.01f) return false;
        float s = panelShift(t);
        return roundedBox(mx - s, my, PANEL_LEFT, PANEL_TOP, PANEL_RIGHT, PANEL_BOTTOM, 40);
    }

    static boolean roundedBox(double x, double y, float x0, float y0, float x1, float y1, float r) {
        if (x < x0 || x > x1 || y < y0 || y > y1) return false;
        double cx = Math.clamp(x, x0 + r, x1 - r), cy = Math.clamp(y, y0 + r, y1 - r);
        double dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= (double) r * r;
    }

    /** Recipe card index 0..5 under the point, or -1. */
    static int card(double mx, double my, float t) {
        float s = panelShift(t);
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            float x = CARD_X[i % 3] + s, y = CARD_Y[i / 3];
            if (rect(mx, my, x, y, x + CARD_W, y + CARD_H)) return i;
        }
        return -1;
    }

    /** "1 / 3" style pager text. */
    static String pager(int page, int pages) { return page + " / " + Math.max(1, pages); }

    /** Number of pages for n recipes (at least 1). */
    static int recipePages(int n) { return Math.max(1, (n + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE); }

    /** Vanilla count text: nothing for a single item. */
    static String count(int n) { return n <= 1 ? "" : Integer.toString(n); }
}
