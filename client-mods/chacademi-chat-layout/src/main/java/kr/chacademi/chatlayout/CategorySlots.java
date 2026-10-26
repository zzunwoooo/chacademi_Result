package kr.chacademi.chatlayout;

import java.util.List;
import java.util.Objects;

/** Computes category destinations from current widths without renderer caches. */
public final class CategorySlots {
    private CategorySlots() {
    }

    /**
     * Uses a one-unit gap and strict neighboring-center crossings. A center exactly
     * on a neighbor stays in its current position. Hidden earlier tabs are excluded.
     * Indices refer to the current list, and all coordinates use the same GUI units.
     *
     * @throws NullPointerException if widths or any width is null
     * @throws IndexOutOfBoundsException if either index is invalid or draggedIndex is hidden
     * @throws IllegalArgumentException if a width is below one or a coordinate is not finite
     */
    public static int target(List<Integer> widths, int firstVisible, double startX,
                             int draggedIndex, double draggedCenter) {
        Objects.requireNonNull(widths, "widths");
        int size = widths.size();
        Objects.checkIndex(firstVisible, size);
        Objects.checkIndex(draggedIndex, size);
        if (draggedIndex < firstVisible) {
            throw new IndexOutOfBoundsException("draggedIndex is before firstVisible");
        }
        if (!Double.isFinite(startX) || !Double.isFinite(draggedCenter)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        for (int index = 0; index < size; index++) {
            int width = Objects.requireNonNull(widths.get(index), "width at " + index);
            if (width < 1) throw new IllegalArgumentException("width must be at least one");
        }

        double[] centers = new double[size];
        double cursor = startX;
        for (int index = firstVisible; index < size; index++) {
            int width = widths.get(index);
            centers[index] = cursor + width / 2.0;
            cursor += width + 1.0;
        }

        int target = draggedIndex;
        while (target > firstVisible && draggedCenter < centers[target - 1]) target--;
        while (target < size - 1 && draggedCenter > centers[target + 1]) target++;
        return target;
    }
}
