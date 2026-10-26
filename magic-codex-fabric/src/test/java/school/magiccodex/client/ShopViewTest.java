package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopViewTest {
    static ShopView.Row row(String id, String buy, String sell) {
        return new ShopView.Row(id, id, List.of(), buy, sell, 3, true, true, null);
    }
    static ShopView.Model model(ShopView.Tab tab, List<ShopView.Row> rows, int page, boolean sortOpen) {
        return new ShopView.Model("t", "r", "n", false, 1, "0", tab, new boolean[]{true, true, true}, rows, page, "",
                false, "", 1, "", true, true, true, true, ShopView.Sort.DEFAULT, sortOpen, false, "", 0, 0, 0, false, 941);
    }
    static List<ShopView.Row> rows(int n) {
        var out = new ArrayList<ShopView.Row>();
        for (int i = 0; i < n; i++) out.add(row("p" + i, "10", ""));
        return out;
    }
    static boolean overlap(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    @Test void footerControlsNeverOverlapAndStayInsideTheFooterBar() {
        var boxes = List.of(ShopView.minusBox(), ShopView.quantityBox(), ShopView.plusBox(), ShopView.switchBox(), ShopView.tradeBox());
        for (int i = 0; i < boxes.size(); i++)
            for (int j = i + 1; j < boxes.size(); j++) assertFalse(overlap(boxes.get(i), boxes.get(j)), i + "/" + j);
        for (var b : boxes) {
            assertTrue(b[1] > ShopView.FOOT_T && b[1] + b[3] < ShopView.FOOT_B);
            assertTrue(b[0] > ShopView.FOOT_L && b[0] + b[2] < ShopView.PANEL_R);
        }
        assertTrue(ShopView.SUM_X + ShopView.SUM_MAX < ShopView.SWITCH_X);
        assertTrue(ShopView.F_TEXT_X + ShopView.F_TEXT_MAX < ShopView.F_DIV1);
        assertTrue(ShopView.footIconBox()[0] > ShopView.FOOT_L, "footer icon stays inside the footer bar");
    }

    @Test void headerRowAndGridFitInsideThePanel() {
        assertTrue(ShopView.SEARCH_X + ShopView.SEARCH_W < ShopView.tabX(0));
        for (int i = 0; i < 2; i++) assertTrue(ShopView.tabX(i) + ShopView.TAB_WS[i] < ShopView.tabX(i + 1));
        assertTrue(ShopView.tabX(2) + ShopView.TAB_WS[2] < ShopView.DIVIDER_X && ShopView.DIVIDER_X < ShopView.SORT_X);
        assertTrue(ShopView.SORT_X + ShopView.SORT_W < ShopView.PANEL_R);
        assertTrue(ShopView.BAR_Y > ShopView.fyTop(178), "search row sits below the header line");
        assertTrue(ShopView.gridBottom() < ShopView.PAGER_Y - ShopView.ARROW_S / 2);
        assertTrue(ShopView.PAGER_Y + ShopView.ARROW_S / 2 < ShopView.FOOT_T);
        assertTrue(ShopView.cardX(ShopView.COLS - 1) + ShopView.CARD_W < ShopView.PANEL_R);
        assertTrue(ShopView.CARD_X0 > ShopView.PANEL_L);
        assertEquals(ShopView.FOOT_T, ShopView.fyTop(887.5f), .01f);
        assertEquals(ShopView.FOOT_B, ShopView.fyFoot(1017), .01f);
    }

    @Test void cardsHitThePageTheyShow() {
        var list = rows(30);
        var m = model(ShopView.Tab.ALL, list, 1, false);
        assertEquals(3, m.pages());
        var h = ShopView.hit(m, ShopView.cardX(0) + 4, ShopView.cardY(0) + ShopView.CARD_H - 4);
        assertEquals(ShopView.Kind.CARD, h.kind());
        assertEquals(12, h.index());
        float[] icon = ShopView.iconBox(5);
        assertEquals(new ShopView.Hit(ShopView.Kind.ICON, 17), ShopView.hit(m, icon[0] + 2, icon[1] + 2));
        var last = model(ShopView.Tab.ALL, list, 2, false);
        assertEquals(ShopView.Kind.NONE, ShopView.hit(last, ShopView.cardX(11) + 4, ShopView.cardY(11) + 4).kind(), "empty slot on the last page");
        float[] prev = ShopView.prevBox(), next = ShopView.nextBox();
        assertEquals(ShopView.Kind.PREV, ShopView.hit(m, prev[0] + 2, prev[1] + 2).kind());
        assertEquals(ShopView.Kind.NEXT, ShopView.hit(m, next[0] + 2, next[1] + 2).kind());
    }

    @Test void openSortListTakesEveryClick() {
        var m = model(ShopView.Tab.ALL, rows(5), 0, true);
        float[] o = ShopView.sortOption(2);
        assertEquals(new ShopView.Hit(ShopView.Kind.SORT_OPTION, 2), ShopView.hit(m, o[0] + 4, o[1] + 4));
        assertEquals(ShopView.Kind.NONE, ShopView.hit(m, ShopView.cardX(0) + 4, ShopView.cardY(0) + 4).kind());
        float[] t = ShopView.tradeBox();
        assertEquals(ShopView.Kind.NONE, ShopView.hit(m, t[0] + 4, t[1] + 4).kind());
    }

    @Test void cardPriceDirection() {
        var both = row("a", "10", "5");
        var sellOnly = row("b", "", "5");
        assertFalse(ShopView.cardSells(model(ShopView.Tab.ALL, List.of(both), 0, false), both));
        assertTrue(ShopView.cardSells(model(ShopView.Tab.ALL, List.of(sellOnly), 0, false), sellOnly));
        assertTrue(ShopView.cardSells(model(ShopView.Tab.SELL, List.of(both), 0, false), both));
    }

    @Test void moneyFormatting() {
        assertEquals("1,200", ShopView.money("1200"));
        assertEquals("0.5", ShopView.money("0.5"));
        assertEquals("1,000,000,000,000", ShopView.money("1000000000000"));
        assertEquals("—", ShopView.money(""));
        assertEquals("abc", ShopView.money("abc"));
    }

    @Test void loreCleanup() {
        assertEquals("치유 물약", ShopView.clean(" 치유  물약 "));
    }
}
