package school.magiccodex.client;

/**
 * Reference-space geometry (1672 x 941, same space as main_panel.png and CodexLayout) shared by drawing and input.
 * Positions follow the approved artifact codex reference image.
 */
final class ArtifactCodexLayout {
    private ArtifactCodexLayout() {}

    record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
        float cx() { return x + w / 2f; }
        float cy() { return y + h / 2f; }
        int right() { return x + w; }
        int bottom() { return y + h; }
    }

    static final int COLUMNS = 5, ROWS = 3, PAGE_SIZE = COLUMNS * ROWS;

    // Header
    // Header band of main_panel.png: inner border line at y=39, header divider at y=137 -> centre y=88.
    // Compass and X sit on that centre line, mirrored 100 px in from the panel edges.
    static final float TITLE_X = 836, TITLE_Y = 88;
    static final Rect COMPASS = new Rect(64, 52, 72, 72);
    static final Rect CLOSE = new Rect(1539, 55, 66, 66);

    // Filter row (just under the header line at y=137)
    static final Rect SEARCH = new Rect(62, 152, 446, 46);
    private static final int[][] TABS = {{538, 142}, {692, 126}, {830, 128}, {970, 132}, {1113, 132}};
    static Rect tab(int i) { return new Rect(TABS[i][0], 152, TABS[i][1], 46); }
    static final float COUNT_RIGHT = 1602, ROW1_Y = 175;

    // Sort + owned-only row
    static final Rect SORT = new Rect(848, 209, 138, 42);
    static Rect sortOption(int i) { return new Rect(SORT.x(), SORT.bottom() + 2 + i * 40, SORT.w(), 40); }
    static final Rect OWNED_BOX = new Rect(1014, 217, 26, 26);
    static final Rect OWNED_HIT = new Rect(1008, 209, 152, 42);

    // Card grid
    static Rect card(int slot) {
        int col = slot % COLUMNS, row = slot / COLUMNS;
        return new Rect(60 + col * 213, 264 + row * 185, 196, 167);
    }

    // Pager
    static final Rect PREVIOUS = new Rect(478, 824, 44, 44), NEXT = new Rect(666, 824, 44, 44);
    static final float PAGE_X = 594, PAGE_Y = 846;

    // Detail panel
    static final Rect DETAIL = new Rect(1176, 205, 446, 671);
    static final float ICON_X = 1399, ICON_Y = 388;
    static final int ICON_SIZE = 270;
    static final float TEXT_LEFT = 1206, TEXT_RIGHT = 1594, TEXT_WIDTH = TEXT_RIGHT - TEXT_LEFT;
    // Simplified panel (2026-10-15 reference): name + grade, one divider, description; the full effect is a hover tooltip.
    static final float NAME_Y = 570, DIVIDER_Y = 609, DESCRIPTION_Y = 651;
    static final float DESCRIPTION_LINE = 34;
    static final int DESCRIPTION_LINES = 3;
    static final Rect DESCRIPTION_HOVER = new Rect(1196, 626, 410, 118);
    static final Rect EQUIP = new Rect(1199, 772, 193, 62), UNEQUIP = new Rect(1407, 772, 191, 62);
    static final float NOTICE_Y = 856;

    // Tooltip (left of the detail panel, over the grid's right edge)
    static final float TOOLTIP_RIGHT = 1244, TOOLTIP_TOP = 618, TOOLTIP_WIDTH = 380;
}
