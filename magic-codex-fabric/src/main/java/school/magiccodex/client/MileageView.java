package school.magiccodex.client;

import java.util.*;

/**
 * 마일리지 상점 layout, drawing order and hit testing (2026-10-24, owner reference "마일리지 상점" + shop_panel.png).
 * No Minecraft types: {@link MileageScreen} supplies a game canvas, the preview tool a Java2D one, so the same code is checked.
 *
 * Coordinates are shop_panel.png pixels (1706 x 922). The panel's own lines (header y 137, left column x 301,
 * detail panel x 1186..1660, frame inner edge x 44..1665 / y 27..884) were measured from the PNG; every element of the
 * reference was mapped onto them. All text goes through {@link #fit}: it shrinks a little, then ends with "…",
 * so nothing crosses its box. Amounts are never cut (they only shrink).
 */
final class MileageView {
    static final int W = 1706, H = 922;
    static final String PANEL = "textures/gui/mileage/shop_panel.png", BUTTON = "textures/gui/mileage/purchase_button.png", COIN = "textures/gui/mileage/mileage_coin.png";
    static final int PANEL_W = 1706, PANEL_H = 922, BUTTON_W = 2172, BUTTON_H = 724, COIN_W = 1254, COIN_H = 1254;
    static final String[] TABS = {"편의", "이벤트", "이용권"};
    static final int PER_PAGE = 6;

    enum Face { TITLE, SECTION, BODY, LABEL, BOLD }

    interface Canvas {
        void image(String texture, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint);
        /** left x (or center x when centered); cy = vertical centre of the glyphs. */
        void text(String value, float x, float cy, float size, int color, Face face, boolean centered);
        float width(String value, float size, Face face);
        /** Glyph ink top/bottom relative to the baseline (negative = above). */
        float[] ink(String value, float size, Face face);
        void gradient(float x, float y, float w, float h, int left, int right);
        void line(float ax, float ay, float bx, float by, float thickness, int color);
        /** Convex polygon (x0,y0,x1,y1,…). */
        void polygon(float[] points, int color);
        /** Product icon ("#slot:N" = the admin's inventory slot N). Nothing when there is no stack. */
        void item(String key, float x, float y, float size);
        /** Text input boxes owned by the screen: "qty", "name", "desc", "price". */
        void field(String key, float x, float y, float w, float h);
        void pushLayer(float z);
        void popLayer();
    }

    /** One card on the current page. add = the admin "new product" card. */
    record Card(String id, String name, String price, boolean enabled, boolean add) {}
    record Model(boolean admin, String balance, int productCount, int tab, List<Card> cards, int page, int pages, String selected,
                 String detailName, String detailText, String total, boolean minus, boolean plus, boolean actionEnabled, String actionLabel,
                 int editCategory, boolean editEnabled, boolean editExisting, boolean deleteArmed, boolean moveEarlier, boolean moveLater,
                 boolean picker, boolean[] filled, int picked, String empty, boolean busy, double mx, double my, boolean pressed) {}

    enum Kind { NONE, CLOSE, REFRESH, TAB, CARD, PREV, NEXT, MINUS, PLUS, QUANTITY, ACTION,
        NAME, DESC, PRICE, CATEGORY, ENABLED, MOVE, REPLACE, DELETE, PICK, PICK_CLOSE }
    record Hit(Kind kind, int index) { static final Hit NONE = new Hit(Kind.NONE, -1); }

    // ---------------------------------------------------------------- colours
    static final int CREAM = 0xFFF1E3BE, TITLE_GOLD = 0xFFEAD7A8, GOLD = 0xFFD9B871, GOLD_LINE = 0xFFB8975A, SUB_GOLD = 0xFFB89D6A,
            WHITE = 0xFFE9EEF5, SOFT = 0xFFDCE3EC, MUTED = 0xFF9DABBD, DIM = 0xFF66758A, BUTTON_TEXT = 0xFF3A2911,
            CARD_FILL = 0xFF13213A, CARD_FILL_LOW = 0xFF0F1A2D, CARD_EDGE = 0xFF2F3E57, CARD_HOVER = 0xFF4C607E, SELECT = 0xFFE4C47C;

    // ---------------------------------------------------------------- layout (shop_panel.png pixels)
    /** Header band = frame inner edge y27 .. header line y138; everything in it is centred on HEAD_CY. */
    static final float HEAD_CY = 82.5f;
    static final float EMBLEM_X = 112, EMBLEM_Y = HEAD_CY, EMBLEM_R = 32;
    static final float TITLE_X = 168, TITLE_Y = HEAD_CY, TITLE_SIZE = 44;
    static final float BALANCE_RIGHT = 1458, COIN_D = 36;
    static final float REFRESH_X = 1508, REFRESH_Y = HEAD_CY - 22, REFRESH_S = 44, HEAD_DIVIDER_X = 1572, CLOSE_X = 1614, CLOSE_Y = HEAD_CY;
    static final float TAB_X = 44, TAB_W = 239, TAB_Y = 162, TAB_H = 84, TAB_STEP = 90, TAB_ICON_X = 92, TAB_LABEL_X = 132;
    static final float CARD_ICON = 176, CARD_ICON_Y = 30, DETAIL_ICON = 240, DETAIL_ICON_Y = 180;
    static final float GRID_X = 321, GRID_Y = 162, CARD_W = 270, CARD_H = 306, COL_STEP = 288, ROW_STEP = 324;
    static final float PAGER_Y = 818, PAGER_S = 47, PREV_X = 616, NEXT_X = 799, PAGE_X = 731;
    static final float DETAIL_X = 1186, DETAIL_R = 1660, DETAIL_CX = 1423, ROW_L = 1227, ROW_R = 1622;
    static final float BUY_X = 1215, BUY_Y = 780, BUY_W = 408, BUY_H = 75;
    static final float QTY_X = 1411, QTY_Y = 648, QTY_W = 211, QTY_H = 47, QTY_CELL = 47;
    static final float FIELD_X = 1312, FIELD_W = 310, FIELD_H = 44;
    static final float[] EDIT_ROWS = {404, 456, 508, 560, 612, 664, 716};
    static final String[] EDIT_LABELS = {"이름", "설명", "가격", "분류", "판매", "순서", "아이템"};

    static float tabY(int i) { return TAB_Y + TAB_STEP * i; }
    static float cardX(int i) { return GRID_X + COL_STEP * (i % 3); }
    static float cardY(int i) { return GRID_Y + ROW_STEP * (i / 3); }
    static boolean in(double x, double y, float rx, float ry, float w, float h) { return x >= rx && x < rx + w && y >= ry && y < ry + h; }

    // ---------------------------------------------------------------- text fitting

    record Fitted(String text, float size) {}
    /** Shrinks to at most minRatio of size, then cuts with "…" — the result is never wider than max. */
    static Fitted fit(Canvas c, String s, float size, Face face, float max, float minRatio) {
        if (s == null) s = "";
        float w = c.width(s, size, face);
        if (w <= max) return new Fitted(s, size);
        float shrunk = size * max / w;
        if (shrunk >= size * minRatio) return new Fitted(s, shrunk);
        float small = size * minRatio;
        String cut = s;
        while (!cut.isEmpty() && c.width(cut + "…", small, face) > max) cut = cut.substring(0, cut.length() - 1);
        return new Fitted(cut.isEmpty() ? "" : cut.stripTrailing() + "…", small);
    }
    /** Amounts: only shrink (down to 45%), never cut. */
    static float amountSize(Canvas c, String s, float size, Face face, float max) {
        float w = c.width(s, size, face);
        return w <= max ? size : Math.max(size * .45f, size * max / w);
    }
    static void textIn(Canvas c, String s, float x, float cy, float size, int color, Face face, float max, boolean centered) {
        var f = fit(c, s, size, face, max, .82f);
        if (!f.text().isEmpty()) c.text(f.text(), x, cy, f.size(), color, face, centered);
    }
    /** Word wrap to at most `lines` lines inside max; the last line ends with "…" when text is left over. */
    static List<String> wrap(Canvas c, String s, float size, Face face, float max, int lines) {
        var out = new ArrayList<String>();
        String rest = s == null ? "" : s.strip();
        while (!rest.isEmpty() && out.size() < lines) {
            if (c.width(rest, size, face) <= max) { out.add(rest); rest = ""; break; }
            int end = rest.length();
            while (end > 1 && c.width(rest.substring(0, end), size, face) > max) end--;
            int space = rest.lastIndexOf(' ', end);
            int cut = space > end / 2 ? space : end;
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            String last = out.removeLast();
            while (!last.isEmpty() && c.width(last + "…", size, face) > max) last = last.substring(0, last.length() - 1);
            out.add(last.stripTrailing() + "…");
        }
        return out;
    }

    // ---------------------------------------------------------------- shapes

    static float[] round(float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) / 2);
        int seg = 6;
        float[] p = new float[4 * (seg + 1) * 2];
        float[][] corners = {{x + w - r, y + r, -90}, {x + w - r, y + h - r, 0}, {x + r, y + h - r, 90}, {x + r, y + r, 180}};
        int k = 0;
        for (float[] cr : corners)
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(cr[2] + 90.0 * i / seg);
                p[k++] = cr[0] + (float) Math.cos(a) * r;
                p[k++] = cr[1] + (float) Math.sin(a) * r;
            }
        return p;
    }
    static void stroke(Canvas c, float[] p, float t, int color) {
        for (int i = 0; i < p.length; i += 2) {
            int j = (i + 2) % p.length;
            c.line(p[i], p[i + 1], p[j], p[j + 1], t, color);
        }
    }
    static void box(Canvas c, float x, float y, float w, float h, float r, int fill, int edge, float t) {
        if ((fill >>> 24) != 0) c.polygon(round(x, y, w, h, r), fill);
        if ((edge >>> 24) != 0) stroke(c, round(x, y, w, h, r), t, edge);
    }
    static void circle(Canvas c, float cx, float cy, float r, float t, int color) {
        int n = 40;
        float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) { double a = 2 * Math.PI * i / n; p[2 * i] = cx + (float) Math.cos(a) * r; p[2 * i + 1] = cy + (float) Math.sin(a) * r; }
        stroke(c, p, t, color);
    }
    static void diamond(Canvas c, float cx, float cy, float rx, float ry, int color) { c.polygon(new float[]{cx, cy - ry, cx + rx, cy, cx, cy + ry, cx - rx, cy}, color); }
    /** Four-point sparkle. */
    static void sparkle(Canvas c, float cx, float cy, float r, int color) {
        float k = r * .22f;
        c.polygon(new float[]{cx, cy - r, cx + k, cy, cx, cy + r, cx - k, cy}, color);
        c.polygon(new float[]{cx - r, cy, cx, cy - k, cx + r, cy, cx, cy + k}, color);
    }
    static void coin(Canvas c, float cx, float cy, float d) { c.image(COIN, cx - d / 2, cy - d / 2, d, d, 0, 0, COIN_W, COIN_H, COIN_W, COIN_H, 0xFFFFFFFF); }

    // ---------------------------------------------------------------- drawing

    static void draw(Canvas c, Model m) {
        c.image(PANEL, 0, 0, W, H, 0, 0, PANEL_W, PANEL_H, PANEL_W, PANEL_H, 0xFFFFFFFF);
        header(c, m);
        tabs(c, m);
        grid(c, m);
        if (m.admin()) editor(c, m); else detail(c, m);
        if (m.admin() && m.picker()) picker(c, m);
    }

    private static void header(Canvas c, Model m) {
        emblem(c, EMBLEM_X, EMBLEM_Y, EMBLEM_R);
        float cy = HEAD_CY;

        // Balance "wallet" (owner 2026-10-24: no capsule/box frames). Same language as the selected tab: a soft gold wash
        // with hairlines that fade out at both ends, coin on the left, small label over a large number.
        String label = m.admin() ? "등록 상품" : "보유 마일리지";
        String value = m.admin() ? m.productCount() + "개" : m.balance();
        float size = amountSize(c, value, 34, Face.TITLE, 260);
        float labelW = Math.min(c.width(label, 18, Face.LABEL), 160), valueW = c.width(value, size, Face.TITLE);
        float textW = Math.max(labelW, valueW), coinD = m.admin() ? 0 : 48, gap = m.admin() ? 0 : 16;
        float textX = BALANCE_RIGHT - textW, left = textX - gap - coinD;
        float bandL = left - 44, bandR = BALANCE_RIGHT + 44, bandT = cy - 36, bandH = 72, half = (bandR - bandL) / 2;
        c.gradient(bandL, bandT, half, bandH, 0x00AE8A52, 0x2EAE8A52);
        c.gradient(bandL + half, bandT, half, bandH, 0x2EAE8A52, 0x00AE8A52);
        c.gradient(bandL, bandT, half, 1.2f, 0x00C9A86A, 0xCCC9A86A);
        c.gradient(bandL + half, bandT, half, 1.2f, 0xCCC9A86A, 0x00C9A86A);
        c.gradient(bandL, bandT + bandH - 1.2f, half, 1.2f, 0x00C9A86A, 0xCCC9A86A);
        c.gradient(bandL + half, bandT + bandH - 1.2f, half, 1.2f, 0xCCC9A86A, 0x00C9A86A);
        diamond(c, bandL + half, bandT, 3.5f, 3.5f, 0xFFE3C789);
        diamond(c, bandL + half, bandT + bandH, 3.5f, 3.5f, 0xFFE3C789);
        if (!m.admin()) coin(c, left + coinD / 2, cy, coinD);
        textIn(c, label, textX, cy - 15, 18, 0xFFC9B486, Face.LABEL, 160, false);
        amount(c, value, textX, cy + 14, size, CREAM, false);

        // title alone, centred on the band like the emblem (차카데미 글씨·안내 문구 삭제)
        var title = fit(c, m.admin() ? "마일리지 상점 관리" : "마일리지 상점", TITLE_SIZE, Face.TITLE, bandL - 30 - TITLE_X, .7f);
        c.text(title.text(), TITLE_X, TITLE_Y, title.size(), TITLE_GOLD, Face.TITLE, false);

        // refresh: icon only, a soft gold glow on hover instead of a frame
        boolean hr = in(m.mx(), m.my(), REFRESH_X, REFRESH_Y, REFRESH_S, REFRESH_S);
        float rx = REFRESH_X + REFRESH_S / 2, ry = REFRESH_Y + REFRESH_S / 2;
        if (hr) { disk(c, rx, ry, 22, 0x26E4C47C); disk(c, rx, ry, 15, 0x1FE4C47C); }
        refreshIcon(c, rx, ry + 2, 12, hr ? CREAM : 0xFFB9C3D0);
        fadedVLine(c, HEAD_DIVIDER_X, cy - 30, 60, 0xB8975A);
        boolean hc = in(m.mx(), m.my(), CLOSE_X - 22, CLOSE_Y - 22, 44, 44);
        if (hc) disk(c, CLOSE_X, CLOSE_Y, 22, 0x26E4C47C);
        int xc = hc ? CREAM : 0xFFC9C0A6;
        c.line(CLOSE_X - 12, CLOSE_Y - 12, CLOSE_X + 12, CLOSE_Y + 12, 2.2f, xc);
        c.line(CLOSE_X - 12, CLOSE_Y + 12, CLOSE_X + 12, CLOSE_Y - 12, 2.2f, xc);
    }
    static void disk(Canvas c, float cx, float cy, float r, int color) {
        int n = 32; float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) { double a = 2 * Math.PI * i / n; p[2 * i] = cx + (float) Math.cos(a) * r; p[2 * i + 1] = cy + (float) Math.sin(a) * r; }
        c.polygon(p, color);
    }
    /** Vertical hairline that fades out at both ends (rgb = 0xRRGGBB). */
    static void fadedVLine(Canvas c, float x, float y, float h, int rgb) {
        int n = 12; float step = h / n;
        for (int i = 0; i < n; i++) {
            float t = (i + .5f) / n, a = 1 - Math.abs(t - .5f) * 2;
            c.gradient(x, y + i * step, 1.4f, step, ((int) (a * 200) << 24) | rgb, ((int) (a * 200) << 24) | rgb);
        }
    }

    /** Compass emblem left of the title (reference): two rings, long north/south and shorter east/west points. */
    private static void emblem(Canvas c, float cx, float cy, float r) {
        circle(c, cx, cy, r, 1.6f, 0xFFC9A86A);
        circle(c, cx, cy, r * .74f, 1.0f, 0x99C9A86A);
        float n = r * 1.18f, e = r * .82f, k = r * .14f;
        c.polygon(new float[]{cx, cy - n, cx + k, cy, cx, cy + n, cx - k, cy}, 0xFFE6CC8E);
        c.polygon(new float[]{cx - e, cy, cx, cy - k, cx + e, cy, cx, cy + k}, 0xFFE6CC8E);
        float d = r * .5f, dk = r * .07f;
        c.polygon(new float[]{cx - d, cy - d, cx + dk, cy - dk, cx + d, cy + d, cx - dk, cy + dk}, 0xAAC9A86A);
        c.polygon(new float[]{cx + d, cy - d, cx + dk, cy + dk, cx - d, cy + d, cx - dk, cy - dk}, 0xAAC9A86A);
        diamond(c, cx, cy, 4, 4, 0xFF101B2D);
    }
    private static void refreshIcon(Canvas c, float cx, float cy, float r, int color) {
        float t = 2f;
        int n = 18; double from = Math.toRadians(-60), to = Math.toRadians(230);
        for (int i = 0; i < n; i++) {
            double a = from + (to - from) * i / n, b = from + (to - from) * (i + 1) / n;
            c.line(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, cx + (float) Math.cos(b) * r, cy + (float) Math.sin(b) * r, t, color);
        }
        float ax = cx + (float) Math.cos(from) * r, ay = cy + (float) Math.sin(from) * r;
        c.polygon(new float[]{ax + 6, ay - 1, ax - 2, ay - 7, ax - 3, ay + 4}, color);
    }

    private static void tabs(Canvas c, Model m) {
        for (int i = 0; i < TABS.length; i++) {
            float y = tabY(i);
            boolean sel = m.tab() == i, hover = !sel && in(m.mx(), m.my(), TAB_X, y, TAB_W, TAB_H);
            if (sel) {
                c.gradient(TAB_X, y, TAB_W, TAB_H, 0x70AE8A52, 0x08AE8A52);
                c.gradient(TAB_X, y, TAB_W, 1.5f, 0xFFC9A86A, 0x00C9A86A);
                c.gradient(TAB_X, y + TAB_H - 1.5f, TAB_W, 1.5f, 0xFFC9A86A, 0x00C9A86A);
            } else if (hover) c.gradient(TAB_X, y, TAB_W, TAB_H, 0x22FFFFFF, 0x00FFFFFF);
            int ic = sel ? 0xFFE3C789 : 0xFFA9B7C8;
            tabIcon(c, i, TAB_ICON_X, y + TAB_H / 2, ic);
            textIn(c, TABS[i], TAB_LABEL_X, y + TAB_H / 2, 26, sel ? 0xFFF3E6C4 : 0xFFC3CCD8, Face.LABEL, TAB_X + TAB_W - 14 - TAB_LABEL_X, false);
            if (i < TABS.length - 1) c.gradient(66, y + TAB_H + (TAB_STEP - TAB_H) / 2, 212, 1, 0x3AA0B0C8, 0x08A0B0C8);
        }
    }
    /** Simple line icons (README: drawn in code): box, star, ticket. */
    private static void tabIcon(Canvas c, int i, float cx, float cy, int color) {
        float t = 1.8f;
        switch (i) {
            case 0 -> {   // 편의: box (isometric cube)
                float w = 15, h = 8.5f, d = 16;
                float[] top = {cx, cy - h - d / 2, cx + w, cy - d / 2, cx, cy + h - d / 2, cx - w, cy - d / 2};
                stroke(c, new float[]{cx, cy - h - d / 2, cx + w, cy - d / 2, cx + w, cy - d / 2 + d, cx, cy + h + d / 2, cx - w, cy - d / 2 + d, cx - w, cy - d / 2}, t, color);
                c.line(top[4], top[5], top[2], top[3], t, color);
                c.line(top[4], top[5], top[6], top[7], t, color);
                c.line(top[4], top[5], cx, cy + h + d / 2, t, color);
            }
            case 1 -> {   // 이벤트: five-point star
                float[] p = new float[20];
                for (int k = 0; k < 10; k++) {
                    double a = -Math.PI / 2 + Math.PI * k / 5; float r = k % 2 == 0 ? 17 : 7.5f;
                    p[2 * k] = cx + (float) Math.cos(a) * r; p[2 * k + 1] = cy + 1.5f + (float) Math.sin(a) * r;
                }
                stroke(c, p, t, color);
            }
            default -> {  // 이용권: ticket with side notches and a plus
                float w = 34, h = 24, x = cx - w / 2, y = cy - h / 2, n = 4.5f;
                c.line(x + 3, y, x + w - 3, y, t, color);
                c.line(x + 3, y + h, x + w - 3, y + h, t, color);
                c.line(x, y + 3, x, cy - n, t, color); c.line(x, cy + n, x, y + h - 3, t, color);
                c.line(x + w, y + 3, x + w, cy - n, t, color); c.line(x + w, cy + n, x + w, y + h - 3, t, color);
                c.line(x, y + 3, x + 3, y, t, color); c.line(x + w - 3, y, x + w, y + 3, t, color);
                c.line(x, y + h - 3, x + 3, y + h, t, color); c.line(x + w - 3, y + h, x + w, y + h - 3, t, color);
                c.line(x, cy - n, x + n, cy, t, color); c.line(x + n, cy, x, cy + n, t, color);
                c.line(x + w, cy - n, x + w - n, cy, t, color); c.line(x + w - n, cy, x + w, cy + n, t, color);
                c.line(cx - 5.5f, cy, cx + 5.5f, cy, t, color); c.line(cx, cy - 5.5f, cx, cy + 5.5f, t, color);
            }
        }
    }

    private static void grid(Canvas c, Model m) {
        if (m.cards().isEmpty()) {
            textIn(c, m.empty(), GRID_X + (COL_STEP * 2 + CARD_W) / 2, GRID_Y + (ROW_STEP + CARD_H) / 2, 24, MUTED, Face.LABEL, COL_STEP * 2 + CARD_W - 40, true);
        }
        for (int i = 0; i < m.cards().size() && i < PER_PAGE; i++) card(c, m, m.cards().get(i), cardX(i), cardY(i));
        if (m.pages() > 1 || !m.cards().isEmpty()) {
            pagerButton(c, m, PREV_X, m.page() > 0, false);
            pagerButton(c, m, NEXT_X, m.page() < m.pages() - 1, true);
            String p = (m.page() + 1) + " / " + Math.max(1, m.pages());
            float size = amountSize(c, p, 25, Face.TITLE, NEXT_X - PREV_X - PAGER_S - 24);
            c.text(p, PAGE_X, PAGER_Y + PAGER_S / 2, size, 0xFFE0E6EE, Face.TITLE, true);
        }
    }
    private static void pagerButton(Canvas c, Model m, float x, boolean enabled, boolean next) {
        boolean hover = enabled && in(m.mx(), m.my(), x, PAGER_Y, PAGER_S, PAGER_S);
        box(c, x, PAGER_Y, PAGER_S, PAGER_S, 6, hover ? 0x33E4C47C : 0x660C1727, !enabled ? 0xFF2E3B4F : hover ? GOLD : 0xFF6A7A92, 1.4f);
        int col = !enabled ? 0xFF46546A : hover ? CREAM : 0xFFD5DCE5;
        float cx = x + PAGER_S / 2, cy = PAGER_Y + PAGER_S / 2, s = next ? 1 : -1;
        c.line(cx - 4 * s, cy - 9, cx + 4 * s, cy, 2f, col);
        c.line(cx + 4 * s, cy, cx - 4 * s, cy + 9, 2f, col);
    }
    private static void card(Canvas c, Model m, Card card, float x, float y) {
        boolean sel = card.id().equals(m.selected()), hover = in(m.mx(), m.my(), x, y, CARD_W, CARD_H);
        if (sel) { stroke(c, round(x - 3, y - 3, CARD_W + 6, CARD_H + 6, 11), 3, 0x30E4C47C); stroke(c, round(x - 6, y - 6, CARD_W + 12, CARD_H + 12, 14), 3, 0x14E4C47C); }
        c.polygon(round(x, y, CARD_W, CARD_H, 8), sel ? 0xFF182640 : CARD_FILL);
        stroke(c, round(x, y, CARD_W, CARD_H, 8), sel ? 2.2f : 1.3f, sel ? SELECT : hover ? CARD_HOVER : CARD_EDGE);
        float cx = x + CARD_W / 2, inner = CARD_W - 28;
        if (card.add()) {
            int col = hover ? CREAM : MUTED;
            float py = y + 112;
            box(c, cx - 38, py - 38, 76, 76, 38, 0x00000000, col, 1.6f);
            c.line(cx - 16, py, cx + 16, py, 2.2f, col);
            c.line(cx, py - 16, cx, py + 16, 2.2f, col);
            textIn(c, "새 상품 추가", cx, y + 222, 23, col, Face.LABEL, inner, true);
            textIn(c, "인벤토리에서 골라 등록", cx, y + 256, 18, DIM, Face.BODY, inner, true);
            return;
        }
        c.item(card.id(), cx - CARD_ICON / 2, y + CARD_ICON_Y, CARD_ICON);
        textIn(c, card.name(), cx, y + 236, 23, WHITE, Face.LABEL, inner, true);
        priceLine(c, card.price(), cx, y + 274, 25, 28, inner);
        if (!card.enabled()) {
            c.polygon(round(x, y, CARD_W, CARD_H, 8), 0x8A0A111D);
            float bw = Math.min(c.width("숨김", 17, Face.LABEL) + 24, inner);
            box(c, x + 12, y + 12, bw, 28, 14, 0xE0263449, 0xFF66758A, 1.2f);
            textIn(c, "숨김", x + 12 + bw / 2, y + 26, 17, SOFT, Face.LABEL, bw - 12, true);
        }
    }
    /** coin + "300", centred on cx, shrunk (never cut) to max. */
    static void priceLine(Canvas c, String s, float cx, float cy, float size, float coin, float max) {
        float gap = coin * .3f;
        float sz = amountSize(c, s, size, Face.TITLE, max - coin - gap);
        float w = coin + gap + c.width(s, sz, Face.TITLE), left = cx - w / 2;
        coin(c, left + coin / 2, cy, coin);
        amount(c, s, left + coin + gap, cy, sz, CREAM, false);
    }
    /**
     * Number whose digits are centred on cy. Plain centring uses the ink box, and a comma hangs below the baseline,
     * which would lift "12,500" above "500" and above the label next to it; so the digits alone decide the height.
     */
    static void amount(Canvas c, String s, float x, float cy, float size, int color, boolean centered) {
        if (s.isEmpty()) return;
        String digits = s.replace(",", "").replace(".", "");
        float[] d = c.ink(digits.isEmpty() ? s : digits, size, Face.TITLE), f = c.ink(s, size, Face.TITLE);
        float baseline = cy - (d[0] + d[1]) / 2;
        c.text(s, x, baseline + (f[0] + f[1]) / 2, size, color, Face.TITLE, centered);
    }

    /** Player detail panel: large icon, name, ornament, description, quantity, total, buy button. */
    private static void detail(Canvas c, Model m) {
        if (m.detailName().isEmpty()) {
            textIn(c, "상품을 선택하세요", DETAIL_CX, 518, 24, MUTED, Face.LABEL, 400, true);
        } else {
            c.item(m.selected(), DETAIL_CX - DETAIL_ICON / 2, DETAIL_ICON_Y, DETAIL_ICON);
            textIn(c, m.detailName(), DETAIL_CX, 518, 32, 0xFFEBD08E, Face.TITLE, 410, true);
            ornament(c, DETAIL_CX, 556, 108);
            var lines = wrap(c, m.detailText(), 21, Face.BODY, 410, 2);
            float first = lines.size() > 1 ? 583 : 597;
            for (int i = 0; i < lines.size(); i++) c.text(lines.get(i), DETAIL_CX, first + 27 * i, 21, SOFT, Face.BODY, true);
        }
        c.gradient(ROW_L - 4, 634, ROW_R - ROW_L + 4, 1, 0x40A8B4C8, 0x40A8B4C8);
        textIn(c, "수량", ROW_L, QTY_Y + QTY_H / 2, 22, 0xFFB4C0CF, Face.LABEL, QTY_X - ROW_L - 12, false);
        quantity(c, m);
        c.gradient(ROW_L - 4, 709, ROW_R - ROW_L + 4, 1, 0x40A8B4C8, 0x40A8B4C8);
        textIn(c, "합계", ROW_L, 742, 22, 0xFFB4C0CF, Face.LABEL, 80, false);
        if (!m.total().isEmpty()) {
            String s = m.total();
            float size = amountSize(c, s, 30, Face.TITLE, ROW_R - (ROW_L + 80) - 40);
            float w = c.width(s, size, Face.TITLE);
            amount(c, s, ROW_R - w, 742, size, CREAM, false);
            coin(c, ROW_R - w - 10 - 15, 742, 30);
        }
        actionButton(c, m, m.actionLabel(), m.actionEnabled());
    }
    private static void ornament(Canvas c, float cx, float cy, float half) {
        c.gradient(cx - half, cy - .6f, half - 12, 1.2f, 0x00C9A86A, 0xCCC9A86A);
        c.gradient(cx + 12, cy - .6f, half - 12, 1.2f, 0xCCC9A86A, 0x00C9A86A);
        sparkle(c, cx, cy, 9, 0xFFE3C789);
    }
    private static void quantity(Canvas c, Model m) {
        box(c, QTY_X, QTY_Y, QTY_W, QTY_H, 6, 0x990A1424, 0xFF52637C, 1.3f);
        c.gradient(QTY_X + QTY_CELL, QTY_Y + 1, 1.2f, QTY_H - 2, 0xFF52637C, 0xFF52637C);
        c.gradient(QTY_X + QTY_W - QTY_CELL, QTY_Y + 1, 1.2f, QTY_H - 2, 0xFF52637C, 0xFF52637C);
        float cy = QTY_Y + QTY_H / 2;
        boolean hm = m.minus() && in(m.mx(), m.my(), QTY_X, QTY_Y, QTY_CELL, QTY_H), hp = m.plus() && in(m.mx(), m.my(), QTY_X + QTY_W - QTY_CELL, QTY_Y, QTY_CELL, QTY_H);
        int mc = !m.minus() ? 0xFF46546A : hm ? CREAM : 0xFFD5DCE5, pc = !m.plus() ? 0xFF46546A : hp ? CREAM : 0xFFD5DCE5;
        float mx = QTY_X + QTY_CELL / 2, px = QTY_X + QTY_W - QTY_CELL / 2;
        c.line(mx - 9, cy, mx + 9, cy, 2f, mc);
        c.line(px - 9, cy, px + 9, cy, 2f, pc);
        c.line(px, cy - 9, px, cy + 9, 2f, pc);
        c.field("qty", QTY_X + QTY_CELL + 2, QTY_Y + 2, QTY_W - QTY_CELL * 2 - 4, QTY_H - 4);
    }
    /** purchase_button.png in three slices: both ornamented ends keep their shape, the plain middle stretches. */
    private static void actionButton(Canvas c, Model m, String label, boolean enabled) {
        boolean hover = enabled && in(m.mx(), m.my(), BUY_X, BUY_Y, BUY_W, BUY_H);
        int tint = !enabled ? 0xFF7C7C7C : hover && m.pressed() ? 0xFFCFCFCF : hover ? 0xFFFFFFFF : 0xFFE6E6E6;
        // opaque plate of the PNG: x 92..2080, y 178..526 (the rest is soft shadow); caps end just inside the sparkle + dot
        int left = 92, top = 178, sh = 348, cap = 328, mid = 2080 - left - cap * 2;
        float s = BUY_H / sh, capW = cap * s;
        c.image(BUTTON, BUY_X, BUY_Y, capW, BUY_H, left, top, cap, sh, BUTTON_W, BUTTON_H, tint);
        c.image(BUTTON, BUY_X + capW, BUY_Y, BUY_W - capW * 2, BUY_H, left + cap, top, mid, sh, BUTTON_W, BUTTON_H, tint);
        c.image(BUTTON, BUY_X + BUY_W - capW, BUY_Y, capW, BUY_H, left + cap + mid, top, cap, sh, BUTTON_W, BUTTON_H, tint);
        textIn(c, label, BUY_X + BUY_W / 2, BUY_Y + BUY_H / 2, 30, enabled ? BUTTON_TEXT : 0xFF3E3E3E, Face.SECTION, BUY_W - capW * 2 + 20, true);
    }

    /** Admin editor in the detail panel. */
    private static void editor(Canvas c, Model m) {
        boolean add = !m.editExisting();
        if (!add) c.item(m.selected(), DETAIL_CX - 75, 225, 150);
        else if (m.picked() >= 0) c.item("#slot:" + m.picked(), DETAIL_CX - 75, 225, 150);
        else if (!m.selected().isEmpty()) textIn(c, "아이템을 골라 주세요", DETAIL_CX, 300, 22, MUTED, Face.LABEL, 300, true);
        if (add && m.selected().isEmpty()) {
            textIn(c, "상품을 선택하거나", DETAIL_CX, 520, 22, MUTED, Face.LABEL, 400, true);
            textIn(c, "새 상품 추가를 누르세요", DETAIL_CX, 552, 22, MUTED, Face.LABEL, 400, true);
            return;
        }
        for (int i = 0; i < EDIT_ROWS.length; i++) {
            float y = EDIT_ROWS[i], cy = y + FIELD_H / 2;
            textIn(c, EDIT_LABELS[i], ROW_L, cy, 20, 0xFFB4C0CF, Face.LABEL, FIELD_X - ROW_L - 10, false);
            switch (i) {
                case 0 -> fieldBox(c, m, "name", y);
                case 1 -> fieldBox(c, m, "desc", y);
                case 2 -> {
                    fieldBox(c, m, "price", y);
                    coin(c, FIELD_X + FIELD_W - 24, cy, 24);
                }
                case 3 -> { for (int k = 0; k < 3; k++) chip(c, m, k, 3, y, TABS[k], m.editCategory() == k, true); }
                case 4 -> { chip(c, m, 0, 2, y, "판매 중", m.editEnabled(), true); chip(c, m, 1, 2, y, "숨김", !m.editEnabled(), true); }
                case 5 -> { chip(c, m, 0, 2, y, "앞으로", false, !add && m.moveEarlier()); chip(c, m, 1, 2, y, "뒤로", false, !add && m.moveLater()); }
                default -> {
                    chip(c, m, 0, 2, y, add ? "인벤토리에서 선택" : "인벤토리에서 교체", false, true);
                    chip(c, m, 1, 2, y, m.deleteArmed() ? "한 번 더 누르면 삭제" : "삭제", m.deleteArmed(), !add);
                }
            }
        }
        actionButton(c, m, m.actionLabel(), m.actionEnabled());
    }
    private static void fieldBox(Canvas c, Model m, String key, float y) {
        boolean hover = in(m.mx(), m.my(), FIELD_X, y, FIELD_W, FIELD_H);
        box(c, FIELD_X, y, FIELD_W, FIELD_H, 6, 0x990A1424, hover ? 0xFF8293AC : 0xFF52637C, 1.3f);
        float right = key.equals("price") ? 44 : 4;
        c.field(key, FIELD_X + 2, y + 1, FIELD_W - right - 2, FIELD_H - 2);
    }
    static float chipX(int index, int count) { float gap = 8, w = (FIELD_W - gap * (count - 1)) / count; return FIELD_X + (w + gap) * index; }
    static float chipW(int count) { float gap = 8; return (FIELD_W - gap * (count - 1)) / count; }
    private static void chip(Canvas c, Model m, int index, int count, float y, String label, boolean on, boolean enabled) {
        float x = chipX(index, count), w = chipW(count);
        boolean hover = enabled && in(m.mx(), m.my(), x, y, w, FIELD_H);
        int fill = on ? 0x55AE8A52 : hover ? 0x22FFFFFF : 0x660C1727;
        int edge = !enabled ? 0xFF2E3B4F : on ? SELECT : hover ? 0xFF8293AC : 0xFF52637C;
        box(c, x, y, w, FIELD_H, 6, fill, edge, on ? 1.8f : 1.3f);
        textIn(c, label, x + w / 2, y + FIELD_H / 2, 19, !enabled ? 0xFF4E5C72 : on ? 0xFFF3E6C4 : 0xFFC9D2DE, Face.LABEL, w - 14, true);
    }

    // ---------------------------------------------------------------- inventory picker (admin)

    /** Covers the product grid and the pager; slots 9..35 (main) in three rows, the hotbar 0..8 below. */
    static final float PICK_X = 312, PICK_Y = 152, PICK_W = 862, PICK_H = 648, PICK_TITLE_Y = 202, PICK_CLOSE_X = PICK_X + PICK_W - 44;
    static final float SLOT = 84, SLOT_GAP = 10, SLOTS_X = PICK_X + (PICK_W - (9 * SLOT + 8 * SLOT_GAP)) / 2, MAIN_Y = 262, HOTBAR_Y = 568;
    static final int SLOTS = 36;
    static float slotX(int slot) { return SLOTS_X + (SLOT + SLOT_GAP) * (slot % 9); }
    static float slotY(int slot) { return slot < 9 ? HOTBAR_Y : MAIN_Y + (SLOT + SLOT_GAP) * ((slot - 9) / 9); }

    private static void picker(Canvas c, Model m) {
        c.pushLayer(200);
        box(c, PICK_X, PICK_Y, PICK_W, PICK_H, 12, 0xFF0B1626, 0xFFB8975A, 1.6f);
        textIn(c, "인벤토리에서 아이템 선택", PICK_X + PICK_W / 2, PICK_TITLE_Y, 30, TITLE_GOLD, Face.TITLE, PICK_W - 200, true);
        boolean hc = in(m.mx(), m.my(), PICK_CLOSE_X - 22, PICK_TITLE_Y - 22, 44, 44);
        int xc = hc ? CREAM : 0xFFC9C0A6;
        c.line(PICK_CLOSE_X - 11, PICK_TITLE_Y - 11, PICK_CLOSE_X + 11, PICK_TITLE_Y + 11, 2.2f, xc);
        c.line(PICK_CLOSE_X - 11, PICK_TITLE_Y + 11, PICK_CLOSE_X + 11, PICK_TITLE_Y - 11, 2.2f, xc);
        c.gradient(PICK_X + 40, PICK_TITLE_Y + 34, (PICK_W - 80) / 2, 1, 0x00C9A86A, 0x99C9A86A);
        c.gradient(PICK_X + PICK_W / 2, PICK_TITLE_Y + 34, (PICK_W - 80) / 2, 1, 0x99C9A86A, 0x00C9A86A);
        // thin divider between the main inventory and the hotbar, like the vanilla inventory gap
        float gapY = (MAIN_Y + 3 * (SLOT + SLOT_GAP) - SLOT_GAP + HOTBAR_Y) / 2;
        c.gradient(SLOTS_X, gapY, 9 * SLOT + 8 * SLOT_GAP, 1, 0x40A8B4C8, 0x40A8B4C8);
        for (int i = 0; i < SLOTS; i++) {
            float x = slotX(i), y = slotY(i);
            boolean has = m.filled() != null && i < m.filled().length && m.filled()[i], picked = i == m.picked(), hover = has && in(m.mx(), m.my(), x, y, SLOT, SLOT);
            box(c, x, y, SLOT, SLOT, 7, has ? (hover ? 0xFF1A2B47 : CARD_FILL) : 0x99101B2D, picked ? SELECT : hover ? CARD_HOVER : has ? CARD_EDGE : 0xFF1F2B3E, picked ? 2.2f : 1.2f);
            if (has) c.item("#slot:" + i, x + 10, y + 10, SLOT - 20);
        }
        textIn(c, "아이템을 누르면 선택돼요 · 인벤토리에서 빠지지 않아요", PICK_X + PICK_W / 2, (HOTBAR_Y + SLOT + PICK_Y + PICK_H) / 2, 20, MUTED, Face.LABEL, PICK_W - 80, true);
        c.popLayer();
    }

    // ---------------------------------------------------------------- hit testing

    static Hit hit(Model m, double x, double y) {
        if (in(x, y, CLOSE_X - 22, CLOSE_Y - 22, 44, 44)) return new Hit(Kind.CLOSE, 0);
        if (m.admin() && m.picker()) {   // the picker is modal: only its slots and its close button
            if (in(x, y, PICK_CLOSE_X - 22, PICK_TITLE_Y - 22, 44, 44)) return new Hit(Kind.PICK_CLOSE, 0);
            for (int i = 0; i < SLOTS; i++) if (in(x, y, slotX(i), slotY(i), SLOT, SLOT)) return new Hit(Kind.PICK, i);
            return in(x, y, PICK_X, PICK_Y, PICK_W, PICK_H) ? Hit.NONE : new Hit(Kind.PICK_CLOSE, 0);
        }
        if (in(x, y, REFRESH_X, REFRESH_Y, REFRESH_S, REFRESH_S)) return new Hit(Kind.REFRESH, 0);
        for (int i = 0; i < TABS.length; i++) if (in(x, y, TAB_X, tabY(i), TAB_W, TAB_H)) return new Hit(Kind.TAB, i);
        for (int i = 0; i < m.cards().size() && i < PER_PAGE; i++) if (in(x, y, cardX(i), cardY(i), CARD_W, CARD_H)) return new Hit(Kind.CARD, i);
        if (in(x, y, PREV_X, PAGER_Y, PAGER_S, PAGER_S)) return new Hit(Kind.PREV, 0);
        if (in(x, y, NEXT_X, PAGER_Y, PAGER_S, PAGER_S)) return new Hit(Kind.NEXT, 0);
        if (in(x, y, BUY_X, BUY_Y, BUY_W, BUY_H)) return new Hit(Kind.ACTION, 0);
        if (!m.admin()) {
            if (in(x, y, QTY_X, QTY_Y, QTY_CELL, QTY_H)) return new Hit(Kind.MINUS, 0);
            if (in(x, y, QTY_X + QTY_W - QTY_CELL, QTY_Y, QTY_CELL, QTY_H)) return new Hit(Kind.PLUS, 0);
            if (in(x, y, QTY_X + QTY_CELL, QTY_Y, QTY_W - QTY_CELL * 2, QTY_H)) return new Hit(Kind.QUANTITY, 0);
            return Hit.NONE;
        }
        if (!m.editExisting() && m.selected().isEmpty()) return Hit.NONE;
        Kind[] rows = {Kind.NAME, Kind.DESC, Kind.PRICE, Kind.CATEGORY, Kind.ENABLED, Kind.MOVE, Kind.REPLACE};
        for (int i = 0; i < EDIT_ROWS.length; i++) {
            float ry = EDIT_ROWS[i];
            if (y < ry || y >= ry + FIELD_H) continue;
            if (i <= 2) return in(x, y, FIELD_X, ry, FIELD_W, FIELD_H) ? new Hit(rows[i], 0) : Hit.NONE;
            int count = i == 3 ? 3 : 2;
            for (int k = 0; k < count; k++)
                if (in(x, y, chipX(k, count), ry, chipW(count), FIELD_H)) return i == 6 ? new Hit(k == 0 ? Kind.REPLACE : Kind.DELETE, 0) : new Hit(rows[i], k);
        }
        return Hit.NONE;
    }

    private MileageView() {}
}
