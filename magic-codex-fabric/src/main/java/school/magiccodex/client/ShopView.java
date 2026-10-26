package school.magiccodex.client;

import java.util.List;

/**
 * NPC shop window, v3: a 1:1 copy of the owner's approved mockup (일반 NPC 상점 12칸 UI v2, reference.png,
 * 1672x941). Every position, size and colour below was measured from reference.png; nothing is added.
 * Pure Java: ShopScreen supplies a Minecraft {@link Canvas}; the review preview supplies a Java2D one.
 *
 * Textures: button_primary.png, and shop_deco_panel.png / shop_deco_front.png — the mockup's own thin gold lines
 * and stars, lifted out of reference.png (tools/shop-preview/extract_deco.py) because shop_frame.png's thick lines
 * do not match it. shop_frame.png is not drawn. Only what the bundle
 * README lists is geometry (card borders, selection line, arrows, search field, secondary buttons); no other
 * lines or ornaments are drawn. Order: panel, catalogue, the illustration (it overlaps the panel's left edge),
 * the frame's footer area over it, then the role / name text.
 */
final class ShopView {
    static final int W = 1672, H = 941;
    // text colours sampled from the mockup
    static final int WHITE = 0xFFF0F3F5, GOLD = 0xFFE7CE91, MUTED = 0xFFB9C4D1, HINT = 0xFF9DAABB, DARK = 0xFF2A2112,
            DISABLED = 0xFF8C9298, DISABLED_ON_GOLD = 0xFF4E5257, CYAN = 0xFF92E7F2, ROLE = 0xFFD9B98A, SUM = 0xFFE9C987;
    // fills / lines sampled from the mockup
    static final int PANEL = 0xE00E1D31, FOOTER = 0xF20C1C30, CARD = 0xF00C1A2D, CARD_LINE = 0xFF242F40,
            SELECTED_LINE = 0xFFF2D184, FIELD = 0xFF0A192C, FIELD_LINE = 0xFF6A5F57, TAB = 0xFF0C1D34, TAB_LINE = 0xFF615C5C,
            TAB_TOP = 0xFFFAE2AD, TAB_BOTTOM = 0xFFE9C58A, TAB_EDGE = 0xFFEFCF96, HEAD_DIVIDER = 0xFF9B8D7B,
            FOOT_DIVIDER = 0xFF474648, STEP_LINE = 0xFF4E4C4A, STEP_FILL = 0xFF0D1D31, SWITCH = 0xFF16273F, SWITCH_LINE = 0xFFC2A976,
            ARROW = 0xFFD9B36A;
    static final String ROOT = "textures/gui/shop/v3/", OLD = "textures/gui/shop/v2/";

    enum Face { TITLE, SECTION, BODY, LABEL, BOLD }
    enum Tab { ALL, BUY, SELL }
    /** 정렬: 기본순 = 상점에 등록된 순서(분류·순서), 나머지는 화면에서만 다시 정렬한다. */
    enum Sort {
        DEFAULT("기본순"), PRICE_LOW("가격순"), PRICE_HIGH("가격 높은순"), NAME("이름순");
        final String label;
        Sort(String label) { this.label = label; }
    }

    interface Canvas {
        /** Draws source region (u,v,sw,sh) of a texture with size (tw,th) into the destination box, tinted (ARGB). */
        void image(String texture, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint);
        default void image(String texture, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th) {
            image(texture, x, y, w, h, u, v, sw, sh, tw, th, 0xFFFFFFFF);
        }
        void text(String value, float x, float centerY, float size, int color, Face face, boolean centered);
        float width(String value, float size, Face face);
        void fill(float x, float y, float w, float h, int color);
        /** Vertical gradient (top colour to bottom colour). */
        void gradient(float x, float y, float w, float h, int top, int bottom);
        /** Octagon with corners cut by r (the mockup's small rounded corners). */
        void chamfer(float x, float y, float w, float h, float r, int color);
        void line(float ax, float ay, float bx, float by, float thickness, int color);
        /** Item icon (real ItemStack) drawn as a square; nothing when the row has no stack. */
        void item(Row row, float x, float y, float size);
        /** NPC illustration, full source, any size. */
        void portrait(float x, float y, float w, float h);
        void searchField(float x, float y, float w, float h);
        void quantityField(float x, float y, float w, float h);
        void pushLayer(float z);
        void popLayer();
    }

    /**
     * One visible product. buy/sell are "" when that trade is off for the product.
     * cost (교환 상품, 2026-10-26): 돈 대신 내는 아이템 이름 — 그때 buy 는 1개당 그 아이템 개수, 가격 자리는 "이름 ×개수"
     * 와 그 아이템 아이콘(Canvas.item 에 id "cost:<상품 id>" 줄)으로 보인다. "" = 보통 상품(G).
     */
    record Row(String id, String name, List<String> lore, String buy, String sell, int owned,
               boolean canBuy, boolean canSell, Object icon, String cost) {
        Row(String id, String name, List<String> lore, String buy, String sell, int owned, boolean canBuy, boolean canSell, Object icon) {
            this(id, name, lore, buy, sell, owned, canBuy, canSell, icon, "");
        }
        boolean buys() { return !buy.isEmpty(); }
        boolean sells() { return !sell.isEmpty(); }
        boolean exchange() { return !cost.isEmpty(); }
        /** 비용 아이템 아이콘을 그릴 때 Canvas.item 에 넘기는 줄. */
        Row costRow() { return new Row("cost:" + id, cost, List.of(), "", "", 0, false, false, null, ""); }
    }
    /** 구매 쪽 가격 글자: 보통 "1,200 G", 교환 상품은 "아티팩트 조각 ×10". */
    static String buyText(Row r, String amount) { return r.exchange() ? r.cost() + " ×" + money(amount) : money(amount) + " G"; }
    /** 가격 앞 동그라미 자리: 보통은 동전, 교환 상품은 비용 아이템 아이콘 (같은 자리·같은 크기). */
    static void priceMark(Canvas c, Row r, boolean exchange, float cx, float cy, float diameter) {
        if (exchange) c.item(r.costRow(), cx - diameter / 2, cy - diameter / 2, diameter);
        else coin(c, cx, cy, diameter);
    }

    /** Everything one frame needs; built by the screen from the server response. */
    record Model(String title, String role, String npcName, boolean hasPortrait, float portraitAspect,
                 String balance, Tab tab, boolean[] tabEnabled, List<Row> rows, int page, String selected,
                 boolean selling, String empty, int max, String total, boolean minusOk, boolean plusOk,
                 boolean tradeOk, boolean switchOk, Sort sort, boolean sortOpen, boolean busy, String notice,
                 float noticeAlpha, double mouseX, double mouseY, boolean mouseDown, float screenBottom) {
        int pages() { return Math.max(1, (rows.size() + PER_PAGE - 1) / PER_PAGE); }
        Row chosen() { return rows.stream().filter(r -> r.id().equals(selected)).findFirst().orElse(null); }
    }

    enum Kind { NONE, TAB, SEARCH, SORT, SORT_OPTION, CARD, ICON, PREV, NEXT, MINUS, PLUS, QUANTITY, SWITCH, TRADE }
    record Hit(Kind kind, int index) { static final Hit NONE = new Hit(Kind.NONE, -1); }

    // ---- geometry (reference.png pixels) ----------------------------------------------------------------------
    // Panel lines in the mockup: outer x488.5..1650, top y21.5, footer line y799, bottom y914.5.
    // shop_frame.png 1440x1092 lines: outer x37..1402, top y62, footer y887.5, bottom y1017 → mapped onto them.
    static final float PANEL_L = 488.5f, PANEL_R = 1650, PANEL_T = 21.5f, FOOT_T = 799, FOOT_B = 914.5f, FOOT_L = 416.5f;
    static final float KX = (PANEL_R - PANEL_L) / (1402 - 37), KY_TOP = (FOOT_T - PANEL_T) / (887.5f - 62), KY_FOOT = (FOOT_B - FOOT_T) / (1017 - 887.5f);
    static float fx(float u) { return PANEL_L + (u - 37) * KX; }
    static float fyTop(float v) { return PANEL_T + (v - 62) * KY_TOP; }
    static float fyFoot(float v) { return FOOT_T + (v - 887.5f) * KY_FOOT; }
    static final int FRAME_SPLIT = 880;                                 // frame rows below 880 are drawn over the footer fill
    // header
    static final float TITLE_X = 576, HEAD_CY = 76.5f, AMOUNT_RIGHT = 1614, AMOUNT_CY = 81,
            GOLD_LABEL_X = 1377, GOLD_LABEL_CY = 78.5f, COIN_HEAD_X = 1344, COIN_HEAD_CY = 77;
    // search row
    static final float BAR_Y = 136, BAR_H = 48, SEARCH_X = 517, SEARCH_W = 517;
    static final float[] TAB_XS = {1062, 1186, 1307}, TAB_WS = {116, 115, 114};
    static final float TAB_Y = 138, TAB_H = 45, DIVIDER_X = 1450, SORT_X = 1481, SORT_W = 139, SORT_Y = 140, SORT_H = 45;
    // cards
    static final int COLS = 4, ROWS = 3, PER_PAGE = COLS * ROWS;
    static final float CARD_X0 = 517, CARD_PITCH_X = 279, CARD_W = 268, CARD_Y0 = 199, CARD_PITCH_Y = 191.5f, CARD_H = 181;
    static final float CARD_ICON = 104, CARD_ICON_DY = 11, CARD_NAME_DY = 134, CARD_PRICE_DY = 162;
    // pager
    static final float PAGER_X = 1069, PAGER_Y = 785, ARROW_DX = 74, ARROW_S = 26;
    // footer (mockup bar 416.5..1650 x 799..914.5)
    static final float FOOT_CY = 857.5f, F_ICON_CX = 468, F_ICON = 84, F_TEXT_X = 543, F_NAME_CY = 839, F_DESC_CY = 874.5f, F_TEXT_MAX = 186,
            F_DIV1 = 738, F_LABEL_X = 759, F_VALUE_RIGHT = 864, F_BUY_CY = 841, F_SELL_CY = 877, F_DIV2 = 893,
            QTY_LABEL_X = 914, QTY_X = 958, QTY_Y = 829, QTY_W = 160, QTY_H = 58, STEP = 48, F_DIV3 = 1144.5f,
            SUM_LABEL_X = 1171, SUM_LABEL_CY = 835, SUM_COIN_X = 1182, SUM_X = 1209, SUM_CY = 866, SUM_MAX = 120,
            SWITCH_X = 1337, SWITCH_Y = 830, SWITCH_W = 104, SWITCH_H = 57, TRADE_X = 1452, TRADE_Y = 826, TRADE_W = 171, TRADE_H = 64;
    // illustration and the role / name label (mockup: portrait at x≈-3, full screen height)
    static final float NPC_X = -3, NPC_H = 942, ROLE_CX = 166, ROLE_CY = 827.5f, NAME_CX = 166, NAME_CY = 865.5f;

    static float cardX(int slot) { return CARD_X0 + (slot % COLS) * CARD_PITCH_X; }
    static float cardY(int slot) { return CARD_Y0 + (slot / COLS) * CARD_PITCH_Y; }
    static float gridBottom() { return cardY(PER_PAGE - 1) + CARD_H; }
    static float tabX(int i) { return TAB_XS[i]; }
    static float[] tabBox(int i) { return new float[]{TAB_XS[i], TAB_Y, TAB_WS[i], TAB_H}; }
    static float[] minusBox() { return new float[]{QTY_X, QTY_Y, STEP, QTY_H}; }
    static float[] plusBox() { return new float[]{QTY_X + QTY_W - STEP, QTY_Y, STEP, QTY_H}; }
    static float[] quantityBox() { return new float[]{QTY_X + STEP, QTY_Y, QTY_W - 2 * STEP, QTY_H}; }
    static float[] tradeBox() { return new float[]{TRADE_X, TRADE_Y, TRADE_W, TRADE_H}; }
    static float[] switchBox() { return new float[]{SWITCH_X, SWITCH_Y, SWITCH_W, SWITCH_H}; }
    static float[] searchBox() { return new float[]{SEARCH_X, BAR_Y, SEARCH_W, BAR_H}; }
    static float[] sortBox() { return new float[]{SORT_X, SORT_Y, SORT_W, SORT_H}; }
    static float[] sortOption(int i) { return new float[]{SORT_X, SORT_Y + SORT_H + 4 + i * 42, SORT_W, 42}; }
    static float[] prevBox() { return new float[]{PAGER_X - ARROW_DX - ARROW_S / 2, PAGER_Y - ARROW_S / 2, ARROW_S, ARROW_S}; }
    static float[] nextBox() { return new float[]{PAGER_X + ARROW_DX - ARROW_S / 2, PAGER_Y - ARROW_S / 2, ARROW_S, ARROW_S}; }
    static float[] iconBox(int slot) { return new float[]{cardX(slot) + (CARD_W - CARD_ICON) / 2, cardY(slot) + CARD_ICON_DY, CARD_ICON, CARD_ICON}; }
    static float[] footIconBox() { return new float[]{F_ICON_CX - F_ICON / 2, FOOT_CY - F_ICON / 2, F_ICON, F_ICON}; }

    static boolean in(double x, double y, float[] b) { return in(x, y, b[0], b[1], b[2], b[3]); }
    static boolean in(double x, double y, float bx, float by, float bw, float bh) {
        return x >= bx && x < bx + bw && y >= by && y < by + bh;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------
    /** Line box with the mockup's small rounded (cut) corners: edge octagon, then the fill inset by t. */
    static void box(Canvas c, float x, float y, float w, float h, float r, int fill, int edge, float t) {
        c.chamfer(x, y, w, h, r, edge);
        c.chamfer(x + t, y + t, w - 2 * t, h - 2 * t, Math.max(0, r - t * .6f), fill);
    }
    static void icon(Canvas c, String path, float cx, float cy, float size, int tw, int th) {
        c.image(path, cx - size / 2, cy - size / 2, size, size, 0, 0, tw, th, tw, th);
    }
    /** coin.png: the disc is 66 of its 128px, so the box is scaled to give a disc of {@code diameter}. */
    static void coin(Canvas c, float cx, float cy, float diameter) { icon(c, OLD + "coin.png", cx, cy, diameter * 128 / 66f, 128, 128); }
    /** Display-only grouping ("1200" -> "1,200"); the server value itself is never changed or re-sent. */
    static String money(String plain) {
        if (plain == null || plain.isEmpty()) return "—";
        try {
            var d = new java.math.BigDecimal(plain);
            var f = new java.text.DecimalFormat("#,##0.######", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT));
            return f.format(d);
        } catch (RuntimeException e) { return plain; }
    }
    /** Lore text without resource-pack glyphs (private-use code points) the UI font cannot draw. */
    static String clean(String s) {
        var b = new StringBuilder();
        s.codePoints().filter(cp -> !(cp >= 0xE000 && cp <= 0xF8FF) && cp < 0xF0000 && cp != '§' && !Character.isISOControl(cp))
                .forEach(b::appendCodePoint);
        return b.toString().replaceAll("\\s+", " ").strip();
    }
    /** Numbers are never cut: they only shrink (down to 45%) to stay inside their box. */
    static float shrink(Canvas c, String s, float size, Face f, float max) {
        float w = c.width(s, size, f);
        return w <= max ? size : Math.max(size * .45f, size * max / w);
    }
    /** Shrinks to 82% first, then cuts with an ellipsis so text never leaves its box. */
    static String fit(Canvas c, String s, float size, Face f, float max, float[] out) {
        out[0] = size;
        if (s.isEmpty() || c.width(s, size, f) <= max) return s;
        float small = size * .82f;
        out[0] = Math.max(small, size * max / c.width(s, size, f));
        if (c.width(s, out[0], f) <= max) return s;
        out[0] = small;
        int n = s.length();
        while (n > 0 && c.width(s.substring(0, n) + "…", small, f) > max) n--;
        return n == 0 ? "…" : s.substring(0, n).stripTrailing() + "…";
    }
    static void fitted(Canvas c, String s, float x, float cy, float size, float max, int color, Face f, boolean centered) {
        float[] k = new float[1];
        String v = fit(c, s, size, f, max, k);
        c.text(v, x, cy, k[0], color, f, centered);
    }
    static boolean hover(Model m, float[] b) { return !m.sortOpen() && in(m.mouseX(), m.mouseY(), b); }
    /** Price a card shows: buyable products show the buy price, the 판매 tab and sell-only products the sell price. */
    static boolean cardSells(Model m, Row r) { return m.tab() == Tab.SELL || !r.buys(); }
    // ---- frame ----------------------------------------------------------------------------------------------
    static void draw(Canvas c, Model m) {
        // panel backing, then the mockup's thin border / title star / header lines (shop_frame.png is not drawn)
        c.fill(PANEL_L, PANEL_T, PANEL_R - PANEL_L, FOOT_T - PANEL_T, PANEL);
        c.image(ROOT + "shop_deco_panel.png", 0, 0, W, H, 0, 0, W, H, W, H);

        // title · gold
        float labelW = c.width("보유 골드", 21.4f, Face.LABEL);
        fitted(c, m.title(), TITLE_X, HEAD_CY, 41, COIN_HEAD_X - 40 - TITLE_X, GOLD, Face.TITLE, false);
        coin(c, COIN_HEAD_X, COIN_HEAD_CY, 28);
        c.text("보유 골드", GOLD_LABEL_X, GOLD_LABEL_CY, 21.4f, WHITE, Face.LABEL, false);
        String gold = m.balance().isEmpty() ? "—" : money(m.balance()) + " G";
        float gs = shrink(c, gold, 28.2f, Face.TITLE, AMOUNT_RIGHT - (GOLD_LABEL_X + labelW + 20));
        c.text(gold, AMOUNT_RIGHT - c.width(gold, gs, Face.TITLE), AMOUNT_CY, gs, WHITE, Face.TITLE, false);

        // search · tabs · sort
        box(c, SEARCH_X, BAR_Y, SEARCH_W, BAR_H, 7, FIELD, FIELD_LINE, 1.5f);
        icon(c, OLD + "search.png", 548, 159, 26 * 128 / 104f, 128, 128);
        c.searchField(576, BAR_Y + 4, SEARCH_W - 72, BAR_H - 8);
        String[] tabs = {"전체", "구매", "판매"};
        for (int i = 0; i < 3; i++) tab(c, m, tabBox(i), tabs[i], m.tab().ordinal() == i, m.tabEnabled()[i]);
        c.fill(DIVIDER_X, TAB_Y + 2, 1.5f, TAB_H - 4, HEAD_DIVIDER);
        float[] sb = sortBox();
        box(c, sb[0], sb[1], sb[2], sb[3], 7, FIELD, FIELD_LINE, 1.5f);
        fitted(c, m.sort().label, 1500, 162, 18.1f, 82, WHITE, Face.LABEL, false);
        chevron(c, 1596.5f, 163, m.sortOpen());

        // catalogue
        int from = m.page() * PER_PAGE;
        for (int s = 0; s < PER_PAGE && from + s < m.rows().size(); s++) card(c, m, m.rows().get(from + s), s);
        if (m.rows().isEmpty()) c.text(m.empty(), (CARD_X0 + cardX(3) + CARD_W) / 2, (CARD_Y0 + gridBottom()) / 2, 22, MUTED, Face.LABEL, true);
        pager(c, m);

        // illustration (over the panel's left edge, under the footer bar)
        if (m.hasPortrait()) {
            float h = NPC_H, w = h * m.portraitAspect();
            c.portrait(NPC_X, Math.max(H, m.screenBottom()) - h, w, h);
        }

        // soft dark shade only behind the role / name (oval, fades out on every side)
        c.image(ROOT + "shop_name_shade.png", NAME_CX - 200, (ROLE_CY + NAME_CY) / 2 - 75, 400, 150, 0, 0, 400, 150, 400, 150);
        // footer bar over the illustration, then the mockup's own thin lines and stars on top
        c.fill(FOOT_L, FOOT_T, PANEL_R - FOOT_L, FOOT_B - FOOT_T, FOOTER);
        c.image(ROOT + "shop_deco_front.png", 0, 0, W, H, 0, 0, W, H, W, H);
        footer(c, m);
        fitted(c, m.role(), ROLE_CX, ROLE_CY, 19.5f, 220, ROLE, Face.LABEL, true);
        fitted(c, m.npcName(), NAME_CX, NAME_CY, 37.8f, 300, WHITE, Face.TITLE, true);

        if (m.sortOpen()) {
            c.pushLayer(300);
            for (int i = 0; i < Sort.values().length; i++) {
                float[] b = sortOption(i);
                boolean on = m.sort().ordinal() == i, h = in(m.mouseX(), m.mouseY(), b);
                box(c, b[0], b[1], b[2], b[3], 5, h ? 0xFF16273F : FIELD, on ? SELECTED_LINE : FIELD_LINE, 1.5f);
                fitted(c, Sort.values()[i].label, b[0] + 19, b[1] + b[3] / 2, 18.1f, b[2] - 30, on ? GOLD : WHITE, Face.LABEL, false);
            }
            c.popLayer();
        }
        if (!m.notice().isEmpty() && m.noticeAlpha() > 0) {
            c.pushLayer(400);
            float w = 700, x = PAGER_X - w / 2, y = gridBottom() - 66;
            int a = Math.round(255 * Math.min(1, m.noticeAlpha()));
            box(c, x, y, w, 50, 7, (a << 24) | (FIELD & 0xFFFFFF), (a << 24) | (FIELD_LINE & 0xFFFFFF), 1.5f);
            fitted(c, m.notice(), PAGER_X, y + 25, 19, w - 40, (a << 24) | (WHITE & 0xFFFFFF), Face.LABEL, true);
            c.popLayer();
        }
    }

    static void chevron(Canvas c, float cx, float cy, boolean up) {
        float d = up ? -1 : 1;
        c.line(cx - 6.5f, cy - 3.5f * d, cx, cy + 3.5f * d, 2, GOLD);
        c.line(cx, cy + 3.5f * d, cx + 6.5f, cy - 3.5f * d, 2, GOLD);
    }

    static void tab(Canvas c, Model m, float[] b, String label, boolean selected, boolean ok) {
        if (selected) {
            c.chamfer(b[0], b[1], b[2], b[3], 6, TAB_EDGE);
            c.gradient(b[0] + 2, b[1] + 2, b[2] - 4, b[3] - 4, TAB_TOP, TAB_BOTTOM);
        } else box(c, b[0], b[1], b[2], b[3], 6, TAB, TAB_LINE, 1.5f);
        c.text(label, b[0] + b[2] / 2, b[1] + b[3] / 2 + 1, 17.9f, !ok ? DISABLED : selected ? DARK : WHITE, Face.BOLD, true);
    }

    static void card(Canvas c, Model m, Row r, int slot) {
        float x = cardX(slot), y = cardY(slot);
        boolean selected = r.id().equals(m.selected());
        box(c, x, y, CARD_W, CARD_H, 5, CARD, selected ? SELECTED_LINE : CARD_LINE, selected ? 2.5f : 1);
        float[] ib = iconBox(slot);
        c.item(r, ib[0], ib[1], ib[2]);
        float cx = x + CARD_W / 2;
        fitted(c, r.name(), cx, y + CARD_NAME_DY, 21.7f, CARD_W - 24, WHITE, Face.BOLD, true);
        boolean sells = cardSells(m, r);
        String v = sells ? money(r.sell()) + " G" : buyText(r, r.buy());
        float size = shrink(c, v, 17.3f, Face.BOLD, CARD_W - 70);
        float vw = c.width(v, size, Face.BOLD), total = 20 + 14 + vw, left = cx - total / 2, py = y + CARD_PRICE_DY;
        priceMark(c, r, !sells && r.exchange(), left + 10, py, 20);
        c.text(v, left + 34, py + 1, size, WHITE, Face.BOLD, false);
    }

    static void pager(Canvas c, Model m) {
        boolean prev = m.page() > 0, next = m.page() < m.pages() - 1;
        int pc = prev ? ARROW : 0x66D9B36A, nc = next ? ARROW : 0x66D9B36A;
        float y = PAGER_Y - 3, lx = PAGER_X - ARROW_DX, rx = PAGER_X + ARROW_DX;
        c.line(lx + 4, y - 8, lx - 4, y, 2.4f, pc);
        c.line(lx - 4, y, lx + 4, y + 8, 2.4f, pc);
        c.line(rx - 4, y - 8, rx + 4, y, 2.4f, nc);
        c.line(rx + 4, y, rx - 4, y + 8, 2.4f, nc);
        c.text((m.page() + 1) + " / " + m.pages(), PAGER_X, PAGER_Y, 18.9f, WHITE, Face.LABEL, true);
    }

    static void footer(Canvas c, Model m) {
        Row r = m.chosen();
        if (r != null) {
            float[] ib = footIconBox();
            c.item(r, ib[0], ib[1], ib[2]);
            boolean lore = !r.lore().isEmpty();
            fitted(c, r.name(), F_TEXT_X, lore ? F_NAME_CY : FOOT_CY, 28.7f, F_TEXT_MAX, WHITE, Face.BOLD, false);
            if (lore) fitted(c, r.lore().getFirst(), F_TEXT_X, F_DESC_CY, 17.2f, F_TEXT_MAX, MUTED, Face.BODY, false);
            c.text("구매", F_LABEL_X, F_BUY_CY, 16.8f, MUTED, Face.LABEL, false);
            c.text("판매", F_LABEL_X, F_SELL_CY, 16.8f, MUTED, Face.LABEL, false);
            footPrice(c, r.buys() ? buyText(r, r.buy()) : "—", F_BUY_CY);
            footPrice(c, r.sells() ? money(r.sell()) + " G" : "—", F_SELL_CY);
        }
        c.fill(F_DIV1, 820, 1.5f, 76, FOOT_DIVIDER);
        c.fill(F_DIV2, 820, 1.5f, 76, FOOT_DIVIDER);

        c.text("수량", QTY_LABEL_X, FOOT_CY, 16.8f, WHITE, Face.LABEL, false);
        box(c, QTY_X, QTY_Y, QTY_W, QTY_H, 5, STEP_FILL, STEP_LINE, 1.5f);
        step(c, m, minusBox(), "−", m.minusOk());
        step(c, m, plusBox(), "+", m.plusOk());
        float[] q = quantityBox();
        c.quantityField(q[0], q[1], q[2], q[3]);
        c.fill(F_DIV3, 820, 1.5f, 76, FOOT_DIVIDER);

        c.text("합계", SUM_LABEL_X, SUM_LABEL_CY, 16.2f, SUM, Face.LABEL, false);
        Row chosen = m.chosen();
        boolean costSum = chosen != null && chosen.exchange() && !m.selling();
        priceMark(c, chosen, costSum, SUM_COIN_X, 868, 26);
        String sum = m.total().isEmpty() ? "—" : costSum ? buyText(chosen, m.total()) : money(m.total()) + " G";

        c.text(sum, SUM_X, SUM_CY, shrink(c, sum, 25.5f, Face.BOLD, SUM_MAX), SUM, Face.BOLD, false);

        float[] sw = switchBox();
        boolean sok = m.switchOk() && !m.busy();
        box(c, sw[0], sw[1], sw[2], sw[3], 6, hover(m, sw) && sok ? 0xFF1E3452 : SWITCH, SWITCH_LINE, 1.5f);
        c.text(m.selling() ? "구매" : "판매", sw[0] + sw[2] / 2, sw[1] + sw[3] / 2, 16.2f, sok ? WHITE : DISABLED, Face.LABEL, true);

        float[] b = tradeBox();
        boolean ok = m.tradeOk() && !m.busy(), h = ok && hover(m, b);
        int tint = !ok ? 0xFF7C7C7C : h && m.mouseDown() ? 0xFFDADADA : 0xFFFFFFFF;
        primary(c, b, tint);
        String label = m.busy() ? "처리 중…" : m.selling() ? "판매하기" : "구매하기";
        c.text(label, b[0] + b[2] / 2, b[1] + b[3] / 2 + 1, 23.4f, ok ? DARK : DISABLED_ON_GOLD, Face.BOLD, true);
    }
    /** button_primary.png ink u42..2163 / v109..583, horizontal 3-slice (cut corners kept) at the mockup's 171x64. */
    static void primary(Canvas c, float[] b, int tint) {
        String p = ROOT + "button_primary.png";
        int u0 = 42, u1 = 2164, v0 = 109, v1 = 584, cap = 130;
        float k = b[3] / (v1 - v0), capW = cap * k;
        c.image(p, b[0], b[1], capW, b[3], u0, v0, cap, v1 - v0, 2206, 713, tint);
        c.image(p, b[0] + capW, b[1], b[2] - 2 * capW, b[3], u0 + cap, v0, u1 - u0 - 2 * cap, v1 - v0, 2206, 713, tint);
        c.image(p, b[0] + b[2] - capW, b[1], capW, b[3], u1 - cap, v0, cap, v1 - v0, 2206, 713, tint);
    }
    static void footPrice(Canvas c, String v, float cy) {
        float size = shrink(c, v, 17.5f, Face.BOLD, F_VALUE_RIGHT - F_LABEL_X - 40);
        c.text(v, F_VALUE_RIGHT - c.width(v, size, Face.BOLD), cy, size, WHITE, Face.BOLD, false);
    }
    static void step(Canvas c, Model m, float[] b, String glyph, boolean ok) {
        ok = ok && !m.busy();
        box(c, b[0], b[1], b[2], b[3], 5, ok && hover(m, b) ? 0xFF16273F : STEP_FILL, STEP_LINE, 1.5f);
        c.text(glyph, b[0] + b[2] / 2, b[1] + b[3] / 2, 26, ok ? WHITE : DISABLED, Face.LABEL, true);
    }

    // ---- input ----------------------------------------------------------------------------------------------
    static Hit hit(Model m, double x, double y) {
        if (m.sortOpen()) {
            for (int i = 0; i < Sort.values().length; i++) if (in(x, y, sortOption(i))) return new Hit(Kind.SORT_OPTION, i);
            if (in(x, y, sortBox())) return new Hit(Kind.SORT, 0);
            return Hit.NONE;                       // any other click just closes the list
        }
        for (int i = 0; i < 3; i++) if (in(x, y, tabBox(i))) return new Hit(Kind.TAB, i);
        if (in(x, y, searchBox())) return new Hit(Kind.SEARCH, 0);
        if (in(x, y, sortBox())) return new Hit(Kind.SORT, 0);
        if (in(x, y, minusBox())) return new Hit(Kind.MINUS, 0);
        if (in(x, y, plusBox())) return new Hit(Kind.PLUS, 0);
        if (in(x, y, quantityBox())) return new Hit(Kind.QUANTITY, 0);
        if (in(x, y, switchBox())) return new Hit(Kind.SWITCH, 0);
        if (in(x, y, tradeBox())) return new Hit(Kind.TRADE, 0);
        if (in(x, y, prevBox())) return new Hit(Kind.PREV, 0);
        if (in(x, y, nextBox())) return new Hit(Kind.NEXT, 0);
        int from = m.page() * PER_PAGE;
        for (int s = 0; s < PER_PAGE && from + s < m.rows().size(); s++) {
            if (in(x, y, iconBox(s))) return new Hit(Kind.ICON, from + s);
            if (in(x, y, cardX(s), cardY(s), CARD_W, CARD_H)) return new Hit(Kind.CARD, from + s);
        }
        return Hit.NONE;
    }
}
