package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GalaxyBagLayoutTest {
    @Test void everyShownSlotHasOneElementAndArmourIsHidden() {
        assertEquals(1 + 4 + 27 + 9, GalaxyBagLayout.elements().size());
        for (int armour = 5; armour <= 8; armour++) assertNull(GalaxyBagLayout.element(armour));
        assertNull(GalaxyBagLayout.element(45));
        assertEquals(GalaxyBagLayout.Kind.HOTBAR, GalaxyBagLayout.element(36).kind());
        assertEquals(GalaxyBagLayout.Kind.MAIN, GalaxyBagLayout.element(35).kind());
    }

    @Test void slotRingsNeverOverlapSoOneClickIsOneSlot() {
        var all = GalaxyBagLayout.elements();
        for (var a : all) for (var b : all) {
            if (a == b || a.inPanel() != b.inPanel()) continue;
            double d = Math.hypot(a.cx() - b.cx(), a.cy() - b.cy());
            assertTrue(d > a.radius() + b.radius() + 8, a.slotId() + " vs " + b.slotId());   // hit circles are radius+4
        }
    }

    @Test void bagAloneIsCentredAndPairIsCentredWhenPanelOpens() {
        float closedCentre = (GalaxyBagLayout.BAG_LEFT + GalaxyBagLayout.BAG_RIGHT) / 2 + GalaxyBagLayout.bagShift(0);
        assertEquals(GalaxyBagLayout.CANVAS_W / 2f, closedCentre, 0.01);
        float pairCentre = (GalaxyBagLayout.BAG_LEFT + GalaxyBagLayout.PANEL_RIGHT) / 2 + GalaxyBagLayout.bagShift(1);
        assertEquals(GalaxyBagLayout.CANVAS_W / 2f, pairCentre, 4);
        assertEquals(0, GalaxyBagLayout.panelShift(1), 1e-6);
        assertTrue(GalaxyBagLayout.PANEL_LEFT > GalaxyBagLayout.BAG_RIGHT, "panel never covers the bag");
    }

    @Test void bordersKeepPngAspectAndPanelSitsBesideTheBag() {
        assertEquals(GalaxyBagLayout.BAG_SX, GalaxyBagLayout.BAG_SY, 1e-6);
        assertTrue(GalaxyBagLayout.PANEL_LEFT > GalaxyBagLayout.BAG_RIGHT);
        assertTrue(GalaxyBagLayout.PANEL_LEFT - GalaxyBagLayout.BAG_RIGHT < 20);
        assertTrue(GalaxyBagLayout.PANEL_RIGHT <= GalaxyBagLayout.CANVAS_W);
        assertTrue(GalaxyBagLayout.BAG_LEFT >= 0);
    }

    @Test void fitIsUniformTopAlignedAndInsideTheWindow() {
        for (int[] s : new int[][]{{960, 540}, {640, 360}, {427, 240}, {800, 600}, {1280, 400}}) {
            var f = GalaxyBagLayout.fit(s[0], s[1]);
            assertEquals(0, f.y(), 1e-6);
            assertTrue(f.x() >= -0.01 && f.x() + GalaxyBagLayout.CANVAS_W * f.scale() <= s[0] + 0.01);
            assertTrue(GalaxyBagLayout.CANVAS_H * f.scale() <= s[1] + 0.01);
        }
    }

    @Test void panelAnimationIsClampedAndSmooth() {
        float t = 0;
        for (int i = 0; i < 100; i++) t = GalaxyBagLayout.approach(t, true, 0.016f);
        assertEquals(1, t, 1e-6);
        assertEquals(0.5f, GalaxyBagLayout.ease(0.5f), 1e-6);
        assertFalse(GalaxyBagLayout.panelInteractive(0.5f));
        assertTrue(GalaxyBagLayout.panelInteractive(1));
    }

    @Test void hitTestsFollowTheSlide() {
        var e = GalaxyBagLayout.element(9);
        assertTrue(GalaxyBagLayout.hit(e, e.cx() + GalaxyBagLayout.bagShift(0), e.cy(), 0));
        assertFalse(GalaxyBagLayout.hit(e, e.cx(), e.cy(), 0));
        assertTrue(GalaxyBagLayout.insideBag(GalaxyBagLayout.CANVAS_W / 2.0, 300, 0));
        assertFalse(GalaxyBagLayout.insideBag(10, 900, 0));
        assertFalse(GalaxyBagLayout.insidePanel(1500, 300, 0));
        assertTrue(GalaxyBagLayout.insidePanel(1500, 300, 1));
        assertEquals(0, GalaxyBagLayout.card(GalaxyBagLayout.CARD_X[0] + 5, GalaxyBagLayout.CARD_Y[0] + 5, 1));
        assertEquals(5, GalaxyBagLayout.card(GalaxyBagLayout.CARD_X[2] + 5, GalaxyBagLayout.CARD_Y[1] + 5, 1));
        assertEquals(-1, GalaxyBagLayout.card(GalaxyBagLayout.CARD_X[0] - 6, GalaxyBagLayout.CARD_Y[0], 1));
    }

    /** 2026-10-26 오너: 잠긴 쪽 자물쇠·문구 묶음은 27칸 영역(핫바 제외) 정중앙, 제목은 링과 겹치지 않음, 핫바와도 안 겹침. */
    @Test void lockedPageStackIsCentredOnTheMainAreaAndClearOfTitleAndHotbar() {
        float[] a = GalaxyBagLayout.mainArea();
        var first = GalaxyBagLayout.element(GalaxyBagLayout.MAIN_START);
        var last = GalaxyBagLayout.element(GalaxyBagLayout.MAIN_START + 26);
        assertEquals(first.cx() - first.radius(), a[0], 1e-3);
        assertEquals(last.cy() + last.radius(), a[3], 1e-3);
        for (float[] h : new float[][]{{23, 15}, {30, 20}, {18, 12}}) {
            var s = GalaxyBagLayout.lockStack(h[0], h[1]);
            assertEquals((a[0] + a[2]) / 2, s.cx(), 1e-3);
            assertEquals((a[1] + a[3]) / 2, (s.top() + s.bottom()) / 2, 1e-3);
            // 묶음의 위끝 = 자물쇠 그림 위끝, 아래끝 = 안내 문구 아래끝.
            assertEquals(s.top(), s.iconY() - GalaxyBagLayout.LOCK_ICON / 2 + GalaxyBagLayout.LOCK_INK_TOP, 1e-3);
            assertEquals(s.bottom(), s.hintY() + h[1] / 2, 1e-3);
            assertTrue(s.textY() + h[0] / 2 < s.hintY() - h[1] / 2, "두 줄 문구는 겹치지 않음");
            assertTrue(s.top() > a[1] && s.bottom() < a[3], "묶음은 27칸 영역 안");
            // 링을 흐리게 덮는 판(최대 26 바깥)도 핫바 구분선 위에서 끝난다.
            assertTrue(s.bottom() + 18 + 26 < GalaxyBagLayout.DIVIDER_Y);
        }
    }

    @Test void pagerTextAndRecipePages() {
        assertEquals("1 / 3", GalaxyBagLayout.pager(1, 3));
        assertEquals(1, GalaxyBagLayout.recipePages(0));
        assertEquals(1, GalaxyBagLayout.recipePages(6));
        assertEquals(2, GalaxyBagLayout.recipePages(7));
        assertEquals("", GalaxyBagLayout.count(1));
        assertEquals("64", GalaxyBagLayout.count(64));
    }
}
