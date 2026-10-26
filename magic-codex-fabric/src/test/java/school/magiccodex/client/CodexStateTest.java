package school.magiccodex.client;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.client.CodexData.Category;
import school.magiccodex.client.CodexState.Filter;

class CodexStateTest {
    @Test
    void categorySwitchClearsOldDetailsAndIncludesDarkness() {
        var state = new CodexState(CodexData.previewSpells());
        assertTrue(state.selectSlot(0));
        assertEquals("magical_flame", state.selected().id());
        state.setCategory(Category.DARK);
        assertNull(state.selected());
        assertEquals(3, state.visible().size());
        assertTrue(state.visible().stream().allMatch(s -> s.category() == Category.DARK));
        assertTrue(state.selectSlot(0));
        assertEquals("shadow_veil", state.selected().id());
    }

    @Test
    void pageNavigationHasNoDuplicatesAndIsBounded() {
        var state = new CodexState(CodexData.previewSpells());
        var first = state.visible();
        assertEquals(9, first.size());
        assertFalse(state.changePage(-1));
        assertTrue(state.selectSlot(0));
        assertTrue(state.changePage(1));
        assertNull(state.selected());
        assertEquals(9, state.visible().size());
        assertTrue(state.visible().stream().noneMatch(first::contains));
        assertFalse(state.changePage(1));
        assertTrue(state.selectSlot(0));
        assertEquals("root_bond", state.selected().id());
        state.setCategory(Category.FIRE);
        assertEquals(0, state.page());
        assertEquals(1, state.pages());
    }

    @Test
    void searchAndDiscoveryFilterComposeWithCategory() {
        var state = new CodexState(CodexData.previewSpells());
        state.setCategory(Category.DARK);
        state.setFilter(Filter.DISCOVERED);
        assertEquals(List.of("night_echo"), state.visible().stream().map(CodexData.Spell::id).toList());
        state.setQuery("  메아리 ");
        assertEquals(1, state.visible().size());
        state.setFilter(Filter.UNDISCOVERED);
        assertTrue(state.visible().isEmpty());
        assertEquals(1, state.pages());
        assertFalse(state.selectSlot(0));
        assertFalse(state.changePage(1));
        state.setQuery("");
        assertEquals(2, state.visible().size());
    }

    @Test
    void emptyDataAndInvalidSlotsAreSafe() {
        var state = new CodexState(List.of());
        assertEquals(1, state.pages());
        assertFalse(state.selectSlot(-1));
        assertFalse(state.selectSlot(9));
        assertFalse(state.changePage(1));
        assertNull(state.selected());
    }

    @ParameterizedTest
    @CsvSource({"1920,1080", "427,240", "320,180", "1024,768", "3440,1440", "720,1280"})
    void renderedCentersHitTheSameCardsAndCategoriesAtEveryScale(int width, int height) {
        var layout = CodexLayout.fit(width, height);
        for (int slot = 0; slot < 9; slot++) {
            var box = CodexHitboxes.card(slot);
            double localX = layout.localX(layout.x() + box.centerX() * layout.scale());
            double localY = layout.localY(layout.y() + box.centerY() * layout.scale());
            assertTrue(box.contains(localX, localY));
            for (int other = 0; other < 9; other++) if (slot != other) assertFalse(CodexHitboxes.card(other).contains(localX, localY));
        }
        var dark = CodexHitboxes.category(Category.DARK.ordinal());
        assertTrue(dark.contains(layout.localX(layout.x() + dark.centerX() * layout.scale()),
                layout.localY(layout.y() + dark.centerY() * layout.scale())));
        assertFalse(dark.contains(dark.x() - 1, dark.centerY()));
    }
}
