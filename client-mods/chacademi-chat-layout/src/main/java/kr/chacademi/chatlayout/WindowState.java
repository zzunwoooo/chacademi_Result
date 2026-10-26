package kr.chacademi.chatlayout;

import java.util.UUID;

/** Persistent layout for one native window; contains no chat messages or filters. */
public final class WindowState {
    public String id = UUID.randomUUID().toString();
    public String signature = "";
    public double x = 0.0125, y = 0.18;
    public Double width, height, backgroundOpacity;
    public boolean locked, systemDefault;
    public UiState.Mode mode = UiState.Mode.NORMAL, restore = UiState.Mode.NORMAL;

    public void sanitize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (signature == null) signature = "";
        x = finite(x, 0.0125);
        if (!(systemDefault && y == -1)) y = finite(y, 0.18);
        if(backgroundOpacity!=null)
            backgroundOpacity=Double.isFinite(backgroundOpacity)?Math.max(0,Math.min(1,backgroundOpacity)):null;
        width = size(width);
        height = size(height);
        if (mode == null || mode == UiState.Mode.MAXIMIZED || mode == UiState.Mode.MINIMIZED) mode = UiState.Mode.NORMAL;
        restore = UiState.Mode.NORMAL;
        if (!restorable(restore)) restore = UiState.Mode.NORMAL;
    }

    public void anchor(double x, double y) { this.x = x; this.y = y; }

    public void resize(double width, double height) {
        if (locked) return;
        this.width = size(width);
        this.height = size(height);
    }

    public void toggleLock() { locked = !locked; }

    public void maximize() {
        if (locked || mode == UiState.Mode.CLOSED) return;
        mode = mode == UiState.Mode.MAXIMIZED ? UiState.Mode.NORMAL : UiState.Mode.MAXIMIZED;
    }

    public void minimize() {
        if (mode == UiState.Mode.CLOSED) return;
        if (mode == UiState.Mode.MINIMIZED) mode = restorable(restore) ? restore : UiState.Mode.NORMAL;
        else {
            restore = restorable(mode) ? mode : UiState.Mode.NORMAL;
            mode = UiState.Mode.MINIMIZED;
        }
    }

    public void close() {
        if (restorable(mode)) restore = mode;
        mode = UiState.Mode.CLOSED;
    }

    public void open() { mode = restorable(restore) ? restore : UiState.Mode.NORMAL; }

    private static boolean restorable(UiState.Mode value) {
        return value == UiState.Mode.NORMAL || value == UiState.Mode.MAXIMIZED;
    }

    private static Double size(Double value) {
        return value != null && Double.isFinite(value) && value > 0 ? Math.min(1, value) : null;
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }
}
