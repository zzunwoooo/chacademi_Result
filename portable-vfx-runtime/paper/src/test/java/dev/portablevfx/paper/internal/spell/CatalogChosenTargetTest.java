package dev.portablevfx.paper.internal.spell;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** #77: a gameplay-chosen target replaces the thin eye ray only inside the plan range + slack. */
class CatalogChosenTargetTest {
    @Test void chosenTargetUsableWithinRangePlusSlack() {
        assertTrue(CatalogVisualEngine.chosenUsable(15 * 15, 15));
        assertTrue(CatalogVisualEngine.chosenUsable(21 * 21, 15));      // broom flight eye lag
        assertFalse(CatalogVisualEngine.chosenUsable(21.1 * 21.1, 15));
        assertFalse(CatalogVisualEngine.chosenUsable(Double.NaN, 15));
    }
}
