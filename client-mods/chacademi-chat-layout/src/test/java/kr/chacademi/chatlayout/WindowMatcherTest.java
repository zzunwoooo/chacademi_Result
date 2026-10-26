package kr.chacademi.chatlayout;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowMatcherTest {
    @Test
    void focusOrderingAndTabOrderingKeepTheSameSavedObjectAndId() {
        WindowState main = window(List.of("전체", "기숙사", "파티", "귓말", "시스템"), .1, .2);
        WindowState party = window(List.of("파티"), .8, .8);
        String id = main.id;
        String signature = main.signature;
        String candidate = LayoutState.signature(List.of("시스템", "귓말", "전체", "파티", "기숙사"));
        WindowState matched = WindowMatcher.find(List.of(party, main), candidate, Set.of(), .11, .19);
        assertSame(main, matched);
        assertEquals(id, main.id);
        assertEquals(signature, main.signature);
        assertEquals(.1, main.x);
        assertEquals(.2, main.y);
    }

    @Test
    void duplicateGroupsUseNearestGeometryAndExcludeClaimedIds() {
        WindowState first = window(List.of("party", "whisper"), .1, .1);
        WindowState second = window(List.of("party", "whisper"), .8, .8);
        List<WindowState> existing = List.of(first, second);
        Set<String> claimed = new HashSet<>();
        assertSame(second, WindowMatcher.find(existing, first.signature, claimed, .75, .7));
        assertTrue(claimed.isEmpty());
        claimed.add(second.id);
        assertSame(first, WindowMatcher.find(existing, first.signature, claimed, .75, .7));
        assertEquals(Set.of(second.id), claimed);
        assertEquals(.8, second.x);
        assertEquals(.8, second.y);
    }

    @Test
    void exactSignatureWinsEvenOverCloserPartialOverlap() {
        WindowState exact = window(List.of("party", "whisper"), .9, .9);
        WindowState partial = window(List.of("party"), .1, .1);
        assertSame(exact, WindowMatcher.find(List.of(partial, exact),
                exact.signature, Set.of(), .1, .1));
    }

    @Test
    void legacyFourTabLayoutAssociatesWithFreshFiveTabMainWindow() {
        WindowState legacy = window(List.of("전체", "기숙사", "파티", "귓말"), .1, .2);
        WindowState system = window(List.of("시스템"), .8, .8);
        String fresh = LayoutState.signature(List.of("전체", "기숙사", "파티", "귓말", "시스템"));
        assertSame(legacy, WindowMatcher.find(List.of(system, legacy), fresh, Set.of(), .8, .8));
    }

    @Test
    void equalPositiveOverlapUsesGeometryAsTieBreaker() {
        WindowState distant = window(List.of("party", "whisper"), .1, .1);
        WindowState close = window(List.of("party", "dorm"), .8, .8);
        String candidate = LayoutState.signature(List.of("party", "global"));
        assertSame(close, WindowMatcher.find(List.of(distant, close), candidate, Set.of(), .75, .7));
    }

    @Test
    void claimedExactCandidateIsNotReusedAndAllClaimedReturnsNull() {
        WindowState exact = window(List.of("party", "whisper"), .1, .1);
        WindowState alternate = window(List.of("party"), .8, .8);
        List<WindowState> existing = List.of(exact, alternate);
        assertSame(alternate, WindowMatcher.find(existing, exact.signature,
                Set.of(exact.id), .1, .1));
        assertNull(WindowMatcher.find(existing, exact.signature,
                Set.of(exact.id, alternate.id), .1, .1));
    }

    @Test
    void unrelatedGroupsAndEmptyNameSetsNeverStealState() {
        WindowState party = window(List.of("party", "whisper"), .2, .2);
        WindowState system = window(List.of("system"), .4, .4);
        List<WindowState> existing = List.of(party, system);
        assertNull(WindowMatcher.find(existing, LayoutState.signature(List.of("combat")),
                Set.of(), .2, .2));
        assertNull(WindowMatcher.find(existing, LayoutState.signature(List.of()),
                Set.of(), .2, .2));
    }

    @Test
    void malformedArgumentsFailExplicitly() {
        assertThrows(NullPointerException.class,
                () -> WindowMatcher.find(null, "", Set.of(), 0, 0));
        assertThrows(NullPointerException.class,
                () -> WindowMatcher.find(List.of(), null, Set.of(), 0, 0));
        assertThrows(NullPointerException.class,
                () -> WindowMatcher.find(List.of(), "", null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WindowMatcher.find(List.of(), "", Set.of(), Double.NaN, 0));
    }

    private static WindowState window(List<String> names, double x, double y) {
        WindowState state = new WindowState();
        state.signature = LayoutState.signature(names);
        state.x = x;
        state.y = y;
        return state;
    }
}
