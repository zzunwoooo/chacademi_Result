package kr.chacademi.chatlayout;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategorySlotsTest {
    @Test
    void neighborCentersAreStrictAndUseTheOneUnitGap() {
        List<Integer> widths = List.of(10, 20, 30);
        // With startX=0, centers are 5, 21 and 47.
        assertEquals(1, CategorySlots.target(widths, 0, 0, 1, 5));
        assertEquals(1, CategorySlots.target(widths, 0, 0, 1, 47));
        assertEquals(0, CategorySlots.target(widths, 0, 0, 1, 4.999));
        assertEquals(2, CategorySlots.target(widths, 0, 0, 1, 47.001));
    }

    @Test
    void crossingSeveralNeighborsMovesDirectlyToTheFinalDestination() {
        List<Integer> widths = List.of(10, 80, 20, 50);
        assertEquals(2, CategorySlots.target(widths, 0, 0, 0, 103));
        assertEquals(0, CategorySlots.target(widths, 0, 0, 3, -10));
        assertEquals(List.of(10, 80, 20, 50), widths);
    }

    @Test
    void unequalWidthsDoNotReverseAForwardMoveAtTheSameDraggedCenter() {
        List<Integer> widths = List.of(10, 80, 20, 50);
        double draggedCenter = 103;
        int destination = CategorySlots.target(widths, 0, 0, 0, draggedCenter);
        List<Integer> reordered = CategoryOrder.move(widths, 0, destination);
        assertEquals(List.of(80, 20, 10, 50), reordered);
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(destination,
                    CategorySlots.target(reordered, 0, 0, destination, draggedCenter));
        }
    }

    @Test
    void unequalWidthsDoNotReverseABackwardMoveAtTheSameDraggedCenter() {
        List<Integer> widths = List.of(80, 20, 10, 50);
        double draggedCenter = 90;
        int destination = CategorySlots.target(widths, 0, 0, 2, draggedCenter);
        assertEquals(1, destination);
        List<Integer> reordered = CategoryOrder.move(widths, 2, destination);
        assertEquals(List.of(80, 10, 20, 50), reordered);
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(destination,
                    CategorySlots.target(reordered, 0, 0, destination, draggedCenter));
        }
    }

    @Test
    void visibleBoundsClampWithoutUsingHiddenWidthsForCoordinates() {
        List<Integer> widths = List.of(1000, 10, 20);
        assertEquals(1, CategorySlots.target(widths, 1, 30, 2, -1000));
        assertEquals(2, CategorySlots.target(widths, 1, 30, 1, 1000));
        assertEquals(2, CategorySlots.target(widths, 1, 30, 2, 35));
        assertEquals(1, CategorySlots.target(widths, 1, 30, 2, 34.999));
        assertEquals(1, CategorySlots.target(List.of(1000, 10), 1, 30, 1, 1000));
    }

    @Test
    void invalidWidthsAndIndicesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CategorySlots.target(List.of(10, 0), 0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> CategorySlots.target(List.of(-1, 10), 1, 0, 1, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CategorySlots.target(List.of(10), -1, 0, 0, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CategorySlots.target(List.of(10), 1, 0, 0, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CategorySlots.target(List.of(10), 0, 0, 1, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CategorySlots.target(List.of(10, 20), 1, 0, 0, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CategorySlots.target(List.of(), 0, 0, 0, 10));
    }

    @Test
    void nullWidthsAndNonfiniteCoordinatesAreRejected() {
        assertThrows(NullPointerException.class,
                () -> CategorySlots.target(null, 0, 0, 0, 10));
        assertThrows(NullPointerException.class,
                () -> CategorySlots.target(Arrays.asList(10, null), 0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> CategorySlots.target(List.of(10), 0, Double.NaN, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> CategorySlots.target(List.of(10), 0, 0, 0, Double.POSITIVE_INFINITY));
    }
}
