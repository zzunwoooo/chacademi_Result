package kr.chacademi.chatlayout;

/** Each panel owns its normalized position, size, lock and mode. */
public final class UiState {
    public enum Mode { NORMAL, MAXIMIZED, MINIMIZED, CLOSED }
    public double upperX = 0.0125, upperY = 0.18;
    public Double lowerX, lowerY;
    // Null sizes retain the default responsive width and system's two-line height.
    public Double upperWidth, upperHeight, lowerWidth, lowerHeight;
    public boolean upperLocked, lowerLocked;
    public Mode upper = Mode.NORMAL, lower = Mode.NORMAL;
    public Mode upperRestore = Mode.NORMAL, lowerRestore = Mode.NORMAL;

    public void sanitize() {
        upperX = finite(upperX, 0.0125); upperY = finite(upperY, 0.18);
        if (lowerX == null || lowerY == null || !Double.isFinite(lowerX) || !Double.isFinite(lowerY)) {
            lowerX = null; lowerY = null;
        } else { lowerX = finite(lowerX, 0.0125); lowerY = finite(lowerY, 0.4); }
        upperWidth = size(upperWidth); upperHeight = size(upperHeight);
        lowerWidth = size(lowerWidth); lowerHeight = size(lowerHeight);
        if (upper == null) upper = Mode.NORMAL;
        if (lower == null) lower = Mode.NORMAL;
        if (!restorable(upperRestore)) upperRestore = Mode.NORMAL;
        if (!restorable(lowerRestore)) lowerRestore = Mode.NORMAL;
    }
    private static boolean restorable(Mode m) { return m == Mode.NORMAL || m == Mode.MAXIMIZED; }
    private static Double size(Double v) { return v != null && Double.isFinite(v) && v > 0 ? Math.min(1, v) : null; }
    private static double finite(double v, double fallback) { return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : fallback; }
    public void anchor(boolean top, double x, double y) { if (top) { upperX=x; upperY=y; } else { lowerX=x; lowerY=y; } }
    public void resize(boolean top, double width, double height) {
        if (locked(top)) return;
        if (top) { upperWidth=size(width); upperHeight=size(height); }
        else { lowerWidth=size(width); lowerHeight=size(height); }
    }
    public boolean locked(boolean top) { return top ? upperLocked : lowerLocked; }
    public void toggleLock(boolean top) { if (top) upperLocked=!upperLocked; else lowerLocked=!lowerLocked; }
    public Mode mode(boolean top) { return top ? upper : lower; }
    private void mode(boolean top, Mode m) { if (top) upper=m; else lower=m; }
    private Mode restore(boolean top) { return top ? upperRestore : lowerRestore; }
    private void restore(boolean top, Mode m) { if (top) upperRestore=m; else lowerRestore=m; }
    public void maximize(boolean top) {
        if (locked(top)) return;
        mode(top, mode(top)==Mode.MAXIMIZED ? Mode.NORMAL : Mode.MAXIMIZED);
    }
    public void minimize(boolean top) {
        if (mode(top)==Mode.MINIMIZED) mode(top, restore(top));
        else { restore(top, mode(top)); mode(top, Mode.MINIMIZED); }
    }
    public void close(boolean top) {
        Mode m=mode(top);
        if (m!=Mode.CLOSED && m!=Mode.MINIMIZED) restore(top,m);
        mode(top,Mode.CLOSED);
    }
    public void open(boolean top) { mode(top,restore(top)); }
}
