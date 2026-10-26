package kr.chacademi.chatlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LayoutStateTest {
    @Test
    void signatureIsOrderIndependentAndKeepsDuplicateCount() {
        List<String> names = new ArrayList<>(List.of("party", "global", "party"));
        assertEquals("global\u001Fparty\u001Fparty\u001F#3", LayoutState.signature(names));
        assertEquals(LayoutState.signature(names),
                LayoutState.signature(List.of("party", "party", "global")));
        assertNotEquals(LayoutState.signature(List.of("party")),
                LayoutState.signature(List.of("party", "party")));
        assertEquals(List.of("party", "global", "party"), names);
    }

    @Test
    void opacityUsesDefaultsForNonfiniteValuesAndClampsFiniteValues() {
        LayoutState state = new LayoutState();
        state.backgroundOpacity = Double.NaN;
        state.borderOpacity = Double.POSITIVE_INFINITY;
        state.sanitize();
        assertEquals(.50, state.backgroundOpacity);
        assertEquals(.85, state.borderOpacity);
        state.backgroundOpacity = -1;
        state.borderOpacity = 2;
        state.sanitize();
        assertEquals(0, state.backgroundOpacity);
        assertEquals(1, state.borderOpacity);
    }

    @Test
    void nullWindowsAndDuplicateIdsAreRepairedWithoutCloningStates() {
        WindowState first = new WindowState(), second = new WindowState();
        second.id = first.id;
        LayoutState state = new LayoutState();
        state.schemaVersion = -1;
        state.windows = Arrays.asList(first, null, second);
        state.sanitize();
        assertEquals(3, state.schemaVersion);
        assertEquals(2, state.windows.size());
        assertSame(first, state.windows.get(0));
        assertSame(second, state.windows.get(1));
        assertNotEquals(first.id, second.id);
        state.windows.add(new WindowState());
        assertEquals(3, state.windows.size());
    }
}
