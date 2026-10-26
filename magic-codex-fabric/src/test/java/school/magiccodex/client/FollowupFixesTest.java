package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FollowupFixesTest {
    @Test void sliderKnobAndValueShareOneMapping() {
        for (float t : new float[]{0, .25f, .5f, .75f, 1}) assertEquals(t, SettingsSlider.fraction(SettingsSlider.knobX(t)), 1e-5);
        assertEquals(1, SettingsSlider.fraction(SettingsSlider.KNOB_MAX + 200), 0, "past the end the value and the knob both stop at 100%");
        assertEquals(0, SettingsSlider.fraction(SettingsSlider.KNOB_MIN - 200), 0);
        assertEquals(SettingsSlider.KNOB_MAX, SettingsSlider.knobX(1.4f), 0, "the knob never runs past the track");
    }
    @Test void threeSliderRowsStayAboveThePanelDivider() {
        // Two-row pages keep the original grid; 3-row pages (effect + bloom, camera) end above the art's divider.
        assertEquals(193, SettingsSlider.rowY(0, 2)); assertEquals(284, SettingsSlider.rowY(1, 2));
        for (int count = 1; count <= 3; count++) for (int row = 0; row < count; row++) {
            int y = SettingsSlider.rowY(row, count);
            assertTrue(y + 56 < SettingsSlider.DIVIDER_Y, "row " + row + "/" + count + " knob bottom above divider");
            assertTrue(y - 18 > 143, "value box below the top divider");
            if (row > 0) assertTrue(SettingsSlider.rowY(row - 1, count) + 56 <= y - 18, "rows do not overlap");
        }
    }
    @Test void typedValuesParseAndRejectGarbage() {
        assertEquals(.8f, SettingsSlider.parse("80", true), 1e-6);
        assertEquals(.8f, SettingsSlider.parse("80%", true), 1e-6);
        assertEquals(1.5f, SettingsSlider.parse("1,5", false), 1e-6);
        assertEquals(-.5f, SettingsSlider.parse("-0.5", false), 1e-6);
        assertNull(SettingsSlider.parse("", false));
        assertNull(SettingsSlider.parse("-", false));
        assertNull(SettingsSlider.parse("1.2.3", false));
    }
    @Test void mouseSitsRightOfTheOriginalKeyboardInsideThePanel() {
        for (var key : KeySettingsLayout.KEYS) assertTrue(key.box().x() + key.box().width() + 40 < MouseBindingLayout.X, key.label());
        assertTrue(MouseBindingLayout.X + MouseBindingLayout.W < 1535, "inside the panel border");
        assertTrue(MouseBindingLayout.Y + MouseBindingLayout.H < KeySettingsLayout.SAVE.y(), "above the button row");
        assertTrue(KeySettingsLayout.SAVE.y() + KeySettingsLayout.SAVE.height() < KeySettingsScreen.HEIGHT - 20, "buttons inside the compact panel");
    }
    @Test void monsterCardKeepsEqualMarginsInsideTheFrame() {
        for (boolean shiny : new boolean[]{false, true}) {
            var b = MonsterPanel.box(shiny);
            assertTrue(b.gap() > 2, "room between name, bar and HP");
            assertEquals(b.top() + MonsterPanel.PAD, b.nameY() - MonsterPanel.NAME / 2, 1e-4);
            assertEquals(b.bottom() - MonsterPanel.PAD, b.hpY() + MonsterPanel.HP / 2, 1e-4, "bottom margin equals the top margin");
            assertTrue(b.left() >= 58, "clear of the corner diamonds");
            assertTrue(b.emblem() <= b.bottom() - b.top() - 2 * MonsterPanel.PAD);
        }
        assertEquals(MonsterPanel.scale(1920, 1080), MonsterPanel.scale(1920, 1080));
        assertEquals(9, MonsterPanel.fit(18, 236, 118), 1e-6);
    }
    @Test void wallEndsAreRoundedAndFadeOut() {
        double[] tip = AimMath.capsuleEnd(0, 1.5), mid = AimMath.capsuleEnd(3, 1.5), half = AimMath.capsuleEnd(.75, 1.5);
        assertEquals(0, tip[0], 1e-9, "zero width at the very end: no flat cut");
        assertEquals(0, tip[1], 1e-9, "fully transparent at the very end");
        assertEquals(1, mid[0], 1e-9); assertEquals(1, mid[1], 1e-9);
        assertTrue(half[0] > .8 && half[0] < 1, "semicircular cap");
        double previous = -1;
        for (double e = 0; e <= 3; e += .1) { double a = AimMath.capsuleEnd(e, 1.5)[1]; assertTrue(a >= previous - 1e-12); previous = a; }
    }
    @Test void onlyZonesAndWallsNeedGround() {
        assertTrue(AimTargeting.grounded(SpellTargets.Mode.WALL));
        assertTrue(AimTargeting.grounded(SpellTargets.Mode.AREA));
        assertTrue(AimTargeting.grounded(SpellTargets.Mode.POINT));
        assertFalse(AimTargeting.grounded(SpellTargets.Mode.LINE));
        assertFalse(AimTargeting.grounded(SpellTargets.Mode.CONE));
        assertFalse(AimTargeting.grounded(SpellTargets.Mode.UNIT));
    }
}
