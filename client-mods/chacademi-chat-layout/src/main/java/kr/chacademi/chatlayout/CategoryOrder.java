package kr.chacademi.chatlayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Moves a category within one window without changing its tab objects. */
public final class CategoryOrder {
    private CategoryOrder() {
    }

    /**
     * Returns a mutable copy with the source element at the desired final index.
     * All elements, including equal objects and nulls, retain their identity.
     * The source list is never changed, including when validation fails.
     *
     * @throws NullPointerException if source is null
     * @throws IndexOutOfBoundsException if either index is outside [0, size)
     */
    public static <T> List<T> move(List<T> source, int fromIndex, int toIndex) {
        Objects.requireNonNull(source, "source");
        int size = source.size();
        Objects.checkIndex(fromIndex, size);
        Objects.checkIndex(toIndex, size);

        List<T> reordered = new ArrayList<>(source);
        if (fromIndex != toIndex) {
            T moved = reordered.remove(fromIndex);
            reordered.add(toIndex, moved);
        }
        return reordered;
    }
}
