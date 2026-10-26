package school.magiccodex.client;

import java.util.List;

/**
 * 책 읽기 창 geometry (2026-10-23), in the coordinates of the unmodified book_panel.png (1672x941) plus a strip under it
 * for the page buttons. Measured on the PNG: page gold lines x 127 / 1535, spine centre x 837, left page edge at the
 * spine ~x 790, right page edge ~x 875, top / bottom gold lines y ~80 / ~825, corner ornaments ~60 px into each corner.
 * Text boxes keep clear of all of them (≥ 38 px from the spine edges, ≥ 50 px from the gold lines, below / above the
 * corner ornaments), so text never runs off the page or into the fold.
 */
final class BookLayout {
    private BookLayout() {}

    /** Whole canvas: the panel (941 high) and the page-button row under it. */
    static final float W = 1672, H = 1000, PANEL_W = 1672, PANEL_H = 941;
    /** Share of the screen the canvas may use, so the book never fills the whole screen. */
    static final float FRACTION = 0.80f;

    // text boxes (one per side)
    static final float LEFT_X = 185, RIGHT_X = 945, BOX_W = 567;
    /** Right side box width: starts 70 px from its spine-side edge (~875) so the text does not hug the fold. */
    static final float RIGHT_W = 540;
    static float boxWidth(boolean right) { return right ? RIGHT_W : BOX_W; }
    static final float BOX_TOP = 160, BOX_BOTTOM = 780;
    // first page: title (one line, or two when long) + divider at the top of the left side, body under it
    static final float TITLE_TOP = 170, TITLE_SIZE = 46, TITLE_MIN = 30, TITLE_TWO_LINE_SIZE = 34, TITLE_TWO_LINE_MIN = 24, TITLE_LINE = 1.3f;
    static final float DIVIDER_GAP = 26, DIVIDER_W = 300, BODY_GAP = 40;
    static final float BODY_SIZE = 27, BODY_MIN = 21, LINE = 1.75f;

    // close button inside the right page, left of / under the top-right corner ornament
    static final float CLOSE_X = 1436, CLOSE_Y = 92, CLOSE_S = 56;
    // page row under the book, centred on the spine (as in the reference)
    static final float NAV_Y = 942, NAV_S = 74, PREV_CX = 663, NEXT_CX = 1011, LABEL_CX = 837, LABEL_SIZE = 26;

    /**
     * The pages are not fully open: each page is a trapezoid, higher at the outer edge and lower at the spine at the top,
     * almost flat at the bottom (measured on book_panel.png: top edge slope +0.042 left / -0.047 right, bottom edge
     * -0.009 left / +0.009 right, edges at y ~60 / ~860). Text lines are sheared by the slope at their height, around the
     * centre of their page, so they follow the paper instead of sitting level on a tilted page.
     */
    static final float LEFT_TOP_SLOPE = 0.042f, LEFT_BOTTOM_SLOPE = -0.009f, RIGHT_TOP_SLOPE = -0.047f, RIGHT_BOTTOM_SLOPE = 0.009f;
    static final float SLOPE_TOP_Y = 60, SLOPE_BOTTOM_Y = 860;

    /** dy/dx of the paper at height y on the left or right page. */
    static float slope(boolean right, float y) {
        float t = Math.clamp((y - SLOPE_TOP_Y) / (SLOPE_BOTTOM_Y - SLOPE_TOP_Y), 0f, 1f);
        return right ? RIGHT_TOP_SLOPE + (RIGHT_BOTTOM_SLOPE - RIGHT_TOP_SLOPE) * t : LEFT_TOP_SLOPE + (LEFT_BOTTOM_SLOPE - LEFT_TOP_SLOPE) * t;
    }
    /** Shear pivot of a page (centre of its text box). */
    static float pivot(boolean right) { return (right ? RIGHT_X : LEFT_X) + boxWidth(right) / 2; }
    /** Where a point drawn level at (x, y) lands on the tilted page. */
    static float tiltedY(boolean right, float x, float y) { return y + slope(right, y) * (x - pivot(right)); }

    /** Panel opacity (hologram). README: about 0.75~0.85. Buttons are a little more solid so they stay readable. */
    static final float PANEL_ALPHA = 0.82f, BUTTON_ALPHA = 0.92f, DISABLED_ALPHA = 0.32f;

    static CodexLayout fit(int width, int height) {
        float scale = Math.min(width / W, height / H) * FRACTION;
        return new CodexLayout((width - W * scale) / 2, (height - H * scale) / 2, scale);
    }

    record Block(float size, float line, List<String> lines) {
        float lineHeight() { return size * line; }
        float height() { return rows(lines.size(), size, line); }
    }

    interface Measure { double width(String text, float size); }

    /**
     * Wraps text to the box width at the largest size from start down to min (step 1) whose lines fit the height.
     * If even min does not fit, the last visible line ends with "…" (the book config should be split into more pages).
     */
    static Block fit(String text, float boxWidth, float boxHeight, float start, float min, float line, Measure measure) {
        if (text == null || text.isBlank()) return new Block(start, line, List.of());
        String value = text.stripTrailing();
        for (float size = start; size >= min; size -= 1) {
            final float s = size;
            var lines = CodexTextLayout.wrap(value, boxWidth, t -> measure.width(t, s));
            if (rows(lines.size(), s, line) <= boxHeight) return new Block(s, line, lines);
        }
        var lines = CodexTextLayout.wrap(value, boxWidth, t -> measure.width(t, min));
        int max = Math.max(1, (int) Math.floor((boxHeight - min) / (min * line)) + 1);
        return new Block(min, line, CodexTextLayout.preview(lines, max, boxWidth, t -> measure.width(t, min)));
    }

    /** Title: one line from TITLE_SIZE down to TITLE_MIN, else up to two lines (smaller), never wider than the box. */
    static Block title(String title, Measure measure) {
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) return new Block(TITLE_SIZE, TITLE_LINE, List.of());
        for (float s = TITLE_SIZE; s >= TITLE_MIN; s -= 1) if (measure.width(t, s) <= BOX_W) return new Block(s, TITLE_LINE, List.of(t));
        return fit(t, BOX_W, rows(2, TITLE_TWO_LINE_SIZE, TITLE_LINE), TITLE_TWO_LINE_SIZE, TITLE_TWO_LINE_MIN, TITLE_LINE, measure);
    }

    /** Height used by n lines: first line's glyph box plus (n-1) line steps. */
    static float rows(int n, float size, float line) { return n <= 0 ? 0 : size + (n - 1) * size * line; }
}
