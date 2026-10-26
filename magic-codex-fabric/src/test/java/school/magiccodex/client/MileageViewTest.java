package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** 마일리지 상점 layout: text never wider than its box, hit areas inside the panel and not overlapping. */
class MileageViewTest {
    /** Fixed-advance canvas: every char = size * 0.9 wide (wider than the real fonts, so the test is strict). */
    static final class Fake implements MileageView.Canvas {
        final List<float[]> texts = new ArrayList<>();
        public void image(String t, float x, float y, float w, float h, int u, int v, int sw, int sh, int tw, int th, int tint) {}
        public void text(String s, float x, float cy, float size, int color, MileageView.Face f, boolean centered) {
            float w = width(s, size, f); float left = centered ? x - w / 2 : x; texts.add(new float[]{left, left + w});
        }
        public float width(String s, float size, MileageView.Face f) { return s.length() * size * .9f; }
        public float[] ink(String s, float size, MileageView.Face f) { return new float[]{-size * .7f, s.contains(",") ? size * .15f : 0}; }
        public void gradient(float x, float y, float w, float h, int l, int r) {}
        public void line(float ax, float ay, float bx, float by, float t, int c) {}
        public void polygon(float[] p, int c) {}
        public void item(String k, float x, float y, float s) {}
        public void field(String k, float x, float y, float w, float h) {}
        public void pushLayer(float z) {}
        public void popLayer() {}
    }

    static MileageView.Model model(boolean admin, List<MileageView.Card> cards, String selected, String name, String desc, String total) {
        return new MileageView.Model(admin, "1,000,000,000,000", cards.size(), 0, cards, 0, 3, selected, name, desc, total, true, true, true,
                admin ? "저장하기" : "구매하기", 0, true, true, false, true, true, false, new boolean[36], -1, "판매 중인 상품이 없어요", false, -1, -1, false);
    }

    @Test void fitNeverExceedsMax() {
        var c = new Fake();
        for (String s : List.of("", "귀환권", "아주아주 긴 이름을 가진 특별한 이벤트 한정 귀환권 패키지 디럭스 에디션")) {
            var f = MileageView.fit(c, s, 24, MileageView.Face.LABEL, 200, .82f);
            assertTrue(c.width(f.text(), f.size(), MileageView.Face.LABEL) <= 200.01f, s);
        }
        var lines = MileageView.wrap(c, "설명이 아주 길어서 두 줄을 넘어가는 경우를 확인합니다 접속 위치를 저장해 두었다가 언제든지 돌아갈 수 있게 해 줍니다", 21, MileageView.Face.BODY, 410, 2);
        assertTrue(lines.size() <= 2);
        for (String l : lines) assertTrue(c.width(l, 21, MileageView.Face.BODY) <= 410.01f, l);
        assertTrue(lines.getLast().endsWith("…"));
    }

    @Test void cardTextStaysInsideCards() {
        var c = new Fake();
        var cards = new ArrayList<MileageView.Card>();
        for (int i = 0; i < 6; i++) cards.add(new MileageView.Card("p" + i, "아주아주 긴 이름을 가진 특별한 이벤트 한정 상품 " + i, "999,999,999", i % 2 == 0, false));
        MileageView.draw(c, model(false, cards, "p0", "아주아주 긴 이름을 가진 특별한 이벤트 한정 상품", "긴 설명 ".repeat(30), "99,000,000,000"));
        for (float[] t : c.texts) assertTrue(t[0] >= 44 && t[1] <= 1665, Arrays.toString(t));
        // nothing drawn in the grid crosses into the detail panel
        for (int i = 0; i < 6; i++) assertTrue(MileageView.cardX(i) + MileageView.CARD_W < MileageView.DETAIL_X);
    }

    @Test void hitAreasDoNotOverlap() {
        var cards = new ArrayList<MileageView.Card>();
        for (int i = 0; i < 6; i++) cards.add(new MileageView.Card("p" + i, "상품", "1", true, false));
        for (boolean admin : new boolean[]{false, true}) {
            var m = model(admin, cards, "p0", "상품", "", "1");
            assertEquals(MileageView.Kind.CARD, MileageView.hit(m, MileageView.cardX(4) + 10, MileageView.cardY(4) + 10).kind());
            assertEquals(4, MileageView.hit(m, MileageView.cardX(4) + 10, MileageView.cardY(4) + 10).index());
            assertEquals(MileageView.Kind.TAB, MileageView.hit(m, 100, MileageView.tabY(2) + 5).kind());
            assertEquals(MileageView.Kind.ACTION, MileageView.hit(m, MileageView.BUY_X + 5, MileageView.BUY_Y + 5).kind());
            assertEquals(MileageView.Kind.CLOSE, MileageView.hit(m, MileageView.CLOSE_X, MileageView.CLOSE_Y).kind());
        }
        var shop = model(false, cards, "p0", "상품", "", "1");
        assertEquals(MileageView.Kind.MINUS, MileageView.hit(shop, MileageView.QTY_X + 5, MileageView.QTY_Y + 5).kind());
        assertEquals(MileageView.Kind.PLUS, MileageView.hit(shop, MileageView.QTY_X + MileageView.QTY_W - 5, MileageView.QTY_Y + 5).kind());
        var admin = model(true, cards, "p0", "", "", "");
        assertEquals(MileageView.Kind.DELETE, MileageView.hit(admin, MileageView.FIELD_X + MileageView.FIELD_W - 5, MileageView.EDIT_ROWS[6] + 5).kind());
        assertEquals(MileageView.Kind.REPLACE, MileageView.hit(admin, MileageView.FIELD_X + 5, MileageView.EDIT_ROWS[6] + 5).kind());
        var cat = MileageView.hit(admin, MileageView.chipX(2, 3) + 5, MileageView.EDIT_ROWS[3] + 5);
        assertEquals(MileageView.Kind.CATEGORY, cat.kind());
        assertEquals(2, cat.index());
        // inventory picker is modal: slots hit, everything under it does not
        var picking = new MileageView.Model(true, "", 6, 0, cards, 0, 1, "#new", "", "", "", false, false, false, "추가하기", 0, true, false, false, false, false,
                true, new boolean[36], -1, "", false, -1, -1, false);
        var slot = MileageView.hit(picking, MileageView.slotX(20) + 5, MileageView.slotY(20) + 5);
        assertEquals(MileageView.Kind.PICK, slot.kind());
        assertEquals(20, slot.index());
        assertEquals(4, MileageView.hit(picking, MileageView.slotX(4) + 5, MileageView.slotY(4) + 5).index());
        assertEquals(MileageView.Kind.NONE, MileageView.hit(picking, MileageView.cardX(0) + 5, MileageView.cardY(0) + 5).kind());
        assertEquals(MileageView.Kind.PICK_CLOSE, MileageView.hit(picking, MileageView.FIELD_X + 5, MileageView.EDIT_ROWS[0] + 5).kind());
        for (int i = 0; i < 36; i++) {
            assertTrue(MileageView.slotX(i) >= MileageView.PICK_X && MileageView.slotX(i) + MileageView.SLOT <= MileageView.PICK_X + MileageView.PICK_W);
            assertTrue(MileageView.slotY(i) + MileageView.SLOT <= MileageView.PICK_Y + MileageView.PICK_H);
        }
        // the last edit row ends above the save button
        assertTrue(MileageView.EDIT_ROWS[6] + MileageView.FIELD_H < MileageView.BUY_Y);
    }
}
