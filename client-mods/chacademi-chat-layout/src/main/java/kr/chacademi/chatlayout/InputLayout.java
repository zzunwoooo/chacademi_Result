package kr.chacademi.chatlayout;

import java.util.Objects;
import kr.chacademi.chatlayout.LayoutMath.Panel;
import kr.chacademi.chatlayout.LayoutMath.Rect;

/** Adds native-input space while keeping every message body's geometry intact. */
public final class InputLayout {
    public static final int ROW_HEIGHT = 20, FIELD_HEIGHT = 12;
    private InputLayout() {}

    public static Panel attach(Panel panel) {
        Objects.requireNonNull(panel, "panel");
        if (!panel.nativeBodyVisible()) return panel;
        Rect frame = panel.frame();
        return new Panel(new Rect(frame.x(), frame.top(), frame.width(),
                Math.addExact(frame.height(), ROW_HEIGHT)),
                panel.header(), panel.body(), panel.tabs(), panel.mode());
    }

    public static Rect field(Panel attached) {
        Objects.requireNonNull(attached, "attached");
        if (!attached.nativeBodyVisible()) return null;
        Rect body = attached.body();
        return new Rect(body.x() + 10, body.bottom() + 5,
                Math.max(1, body.width() - 20), FIELD_HEIGHT);
    }

    /** The borderless vanilla field draws its glyphs at field.top, without built-in padding. */
    public static Rect background(Rect field) {
        Objects.requireNonNull(field, "field");
        return new Rect(field.x() - 6, field.top() - 4, field.width() + 12, FIELD_HEIGHT + 6);
    }

    /** ChatPlus 2.8.1 renders pane index n at z = n * 0.1. Keep text and fill together above it. */
    public static float overlayDepth(int windowCount) {
        return Math.max(1, windowCount) * 0.1f + 1.0f;
    }
    public static int tabOffset(Panel attached) {
        Objects.requireNonNull(attached, "attached");
        return attached.nativeBodyVisible() ? ROW_HEIGHT : 0;
    }

    public static Rect popup(Rect desired, int viewportWidth, int viewportHeight) {
        Objects.requireNonNull(desired, "desired");
        if (viewportWidth < 6 || viewportHeight < 6)
            throw new IllegalArgumentException("Viewport must allow two-pixel margins");
        int width = clamp(desired.width(), 1, viewportWidth - 4);
        int height = clamp(desired.height(), 1, viewportHeight - 4);
        return new Rect(clamp(desired.x(), 2, viewportWidth - width - 2),
                clamp(desired.top(), 2, viewportHeight - height - 2), width, height);
    }

    /** Keeps the native rendered row count within the same viewport bounds as its hit box. */
    public static int suggestionRows(int configured, int viewportHeight) {
        int available = (int)Math.max(1L, ((long)viewportHeight - 4) / 12);
        return Math.min(Math.max(1, configured), available);
    }

    public static Rect anchoredPopup(Rect field, int x, int width, int rows,
                                     int viewportWidth, int viewportHeight) {
        int above = Math.max(0, field.top() - 6);
        int below = Math.max(0, viewportHeight - field.bottom() - 6);
        int wanted = Math.max(1, rows) * 12;
        boolean up = above >= wanted || above >= below;
        int height = Math.min(wanted, Math.max(12, ((up ? above : below) / 12) * 12));
        return popup(new Rect(x, up ? field.top()-4-height : field.bottom()+4, width, height),
                viewportWidth, viewportHeight);
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
