package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import school.magiccodex.client.AlchemyLayout.Box;

/** Owner request 2026-10-16: nothing in the alchemy window may overlap. */
class AlchemyLayoutTest {
    static boolean inside(Box inner, Box outer) {
        return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    @Test void partsNeverOverlap() {
        for (int slots = 1; slots <= 8; slots++) {
            var parts = new ArrayList<>(AlchemyLayout.parts(slots).entrySet());
            for (int i = 0; i < parts.size(); i++) for (int j = i + 1; j < parts.size(); j++)
                assertFalse(parts.get(i).getValue().overlaps(parts.get(j).getValue()),
                        slots + " slots: " + parts.get(i).getKey() + " overlaps " + parts.get(j).getKey());
        }
    }

    @Test void everythingStaysInsideItsFrame() {
        for (var e : AlchemyLayout.parts(6).entrySet()) {
            if (e.getKey().equals("close")) continue;
            boolean left = inside(e.getValue(), AlchemyLayout.LEFT), right = inside(e.getValue(), AlchemyLayout.RIGHT);
            assertTrue(left || right, e.getKey() + " leaves the panel frames");
        }
        assertTrue(inside(AlchemyLayout.PICKER, AlchemyLayout.LEFT));
        for (int i = 0; i < AlchemyLayout.PICK_COLUMNS * AlchemyLayout.PICK_ROWS; i++) {
            var cell = AlchemyLayout.pickCell(i);
            assertTrue(inside(cell, AlchemyLayout.PICKER), "picker cell " + i);
            assertTrue(cell.y() > AlchemyLayout.PICK_TITLE_Y + 20 && cell.bottom() < AlchemyLayout.PICK_HINT_Y - 10, "picker cell " + i + " vs title/hint");
            if (i > 0) assertFalse(cell.overlaps(AlchemyLayout.pickCell(i - 1)));
        }
        assertFalse(AlchemyLayout.PICK_CLOSE.overlaps(AlchemyLayout.pickCell(AlchemyLayout.PICK_COLUMNS - 1)));
    }

    @Test void slotsSitOnTheCircleBeamsAndClearTheResultRing() {
        for (int i = 0; i < 6; i++) {
            var s = AlchemyLayout.slot(i, 6);
            double d = Math.hypot(s.cx() - AlchemyLayout.CIRCLE_X, s.cy() - AlchemyLayout.CIRCLE_Y);
            assertEquals(AlchemyLayout.SLOT_RADIUS, d, .01);
            assertTrue(s.y() > AlchemyLayout.LEFT.y() + 20 && s.bottom() < AlchemyLayout.MESSAGE_Y - 30, "slot " + i);
        }
        // the six art positions are symmetric left/right and top/bottom
        assertEquals(AlchemyLayout.slot(0, 6).cy(), AlchemyLayout.slot(1, 6).cy(), .01);
        assertEquals(AlchemyLayout.slot(2, 6).cy(), AlchemyLayout.CIRCLE_Y, .01);
        assertEquals(AlchemyLayout.CIRCLE_X * 2, AlchemyLayout.slot(0, 6).cx() + AlchemyLayout.slot(1, 6).cx(), .01);
    }
}
