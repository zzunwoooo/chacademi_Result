package kr.chacademi.chatlayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Addon-only state for any number of independent native chat windows. */
public final class LayoutState {
    public int schemaVersion = 3;
    public List<WindowState> windows = new ArrayList<>();
    public double backgroundOpacity = 0.50, borderOpacity = 0.85;

    public void sanitize() {
        schemaVersion = 3;
        backgroundOpacity = opacity(backgroundOpacity, 0.50);
        borderOpacity = opacity(borderOpacity, 0.85);
        List<WindowState> valid = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (windows != null) {
            for (WindowState window : windows) {
                if (window == null) continue;
                window.sanitize();
                while (!ids.add(window.id)) window.id = UUID.randomUUID().toString();
                valid.add(window);
            }
        }
        windows = valid;
    }

    /** Stable across tab reorder; duplicate names remain distinguishable by their count. */
    public static String signature(List<String> tabNames) {
        Objects.requireNonNull(tabNames, "tabNames");
        List<String> sorted = new ArrayList<>(tabNames);
        for (String name : sorted) Objects.requireNonNull(name, "tab name");
        Collections.sort(sorted);
        return String.join("\u001F", sorted) + "\u001F#" + sorted.size();
    }

    private static double opacity(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }
}
