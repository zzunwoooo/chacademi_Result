package kr.chacademi.chatlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategoryOrderTest {
    @Test
    void movingForwardUsesTheRequestedFinalIndexAndPreservesTheSource() {
        List<String> source = new ArrayList<>(List.of("global", "dorm", "party", "whisper"));
        List<String> result = CategoryOrder.move(source, 1, 3);
        assertEquals(List.of("global", "party", "whisper", "dorm"), result);
        assertEquals(List.of("global", "dorm", "party", "whisper"), source);
        assertNotSame(source, result);
    }

    @Test
    void movingBackwardCanPlaceTheLastCategoryFirst() {
        List<String> source = List.of("global", "dorm", "party", "whisper");
        assertEquals(List.of("whisper", "global", "dorm", "party"),
                CategoryOrder.move(source, 3, 0));
        assertEquals(List.of("global", "dorm", "party", "whisper"), source);
    }

    @Test
    void equalTabObjectsRetainTheirOwnIdentityWithImmutableInput() {
        Tab first = new Tab("same");
        Tab second = new Tab("same");
        Tab third = new Tab("other");
        List<Tab> source = List.of(first, second, third);
        List<Tab> result = CategoryOrder.move(source, 0, 2);
        assertSame(second, result.get(0));
        assertSame(third, result.get(1));
        assertSame(first, result.get(2));
        assertSame(first, source.get(0));
        assertSame(second, source.get(1));
        assertSame(third, source.get(2));
        result.add(new Tab("new"));
        assertEquals(3, source.size());
    }

    @Test
    void noOpStillReturnsAnIndependentMutableList() {
        Tab only = new Tab("global");
        List<Tab> source = List.of(only);
        List<Tab> result = CategoryOrder.move(source, 0, 0);
        assertNotSame(source, result);
        assertSame(only, result.get(0));
        result.clear();
        assertEquals(1, source.size());
        assertSame(only, source.get(0));
    }

    @Test
    void nullElementsCanBeMovedWithoutChangingOtherIdentities() {
        Tab first = new Tab("global");
        Tab last = new Tab("whisper");
        List<Tab> source = Arrays.asList(first, null, last);
        List<Tab> result = CategoryOrder.move(source, 1, 2);
        assertSame(first, result.get(0));
        assertSame(last, result.get(1));
        assertNull(result.get(2));
        assertNull(source.get(1));
    }

    @Test
    void invalidIndicesFailBeforeChangingTheSource() {
        List<String> source = new ArrayList<>(List.of("global", "dorm"));
        assertThrows(IndexOutOfBoundsException.class, () -> CategoryOrder.move(source, -1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> CategoryOrder.move(source, 2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> CategoryOrder.move(source, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> CategoryOrder.move(source, 0, 2));
        assertEquals(List.of("global", "dorm"), source);
    }

    @Test
    void emptyAndNullSourcesAreRejectedExplicitly() {
        assertThrows(IndexOutOfBoundsException.class, () -> CategoryOrder.move(List.of(), 0, 0));
        assertThrows(NullPointerException.class, () -> CategoryOrder.move(null, 0, 0));
    }

    private record Tab(String label) {
    }
}
