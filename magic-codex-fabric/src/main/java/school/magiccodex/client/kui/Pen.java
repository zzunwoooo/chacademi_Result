package school.magiccodex.client.kui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * A Canvas plus what the views need around it: the pointer (in design pixels), the pressed control, named click areas
 * (kept in top-level design pixels even inside scaled groups) and small helpers (fitted text, buttons with
 * hover / pressed / disabled art of identical size).
 */
public final class Pen {
    public final Canvas c;
    public final float mx, my;
    public final String pressed;
    public record Hit(String id, float x, float y, float w, float h) {
        public boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }
    private final List<Hit> hits = new ArrayList<>();
    private final ArrayList<float[]> stack = new ArrayList<>();
    private float tx, ty, ts = 1;

    public Pen(Canvas c, float mx, float my, String pressed) { this.c = c; this.mx = mx; this.my = my; this.pressed = pressed; }

    public List<Hit> hits() { return hits; }
    public String hitAt(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(x, y)) return hits.get(i).id();
        return null;
    }

    // ------------------------------------------------------------------ groups
    public void push(float x, float y, float s) { stack.add(new float[]{tx, ty, ts}); tx += x * ts; ty += y * ts; ts *= s; c.push(x, y, s); }
    public void pop() { float[] p = stack.remove(stack.size() - 1); tx = p[0]; ty = p[1]; ts = p[2]; c.pop(); }

    // ------------------------------------------------------------------ pointer
    /** Pointer over a rectangle given in the current group's coordinates. */
    public boolean over(float x, float y, float w, float h) {
        float ax = tx + x * ts, ay = ty + y * ts;
        return mx >= ax && mx < ax + w * ts && my >= ay && my < ay + h * ts;
    }
    public void hit(String id, float x, float y, float w, float h) { hits.add(new Hit(id, tx + x * ts, ty + y * ts, w * ts, h * ts)); }

    // ------------------------------------------------------------------ drawing helpers
    public void image(String part, float x, float y, float w, float h) { c.image(part, x, y, w, h, 1); }
    /** Part at its own pixel size. */
    public void image(String part, float x, float y) { int[] s = Parts.size(part); c.image(part, x, y, s[0], s[1], 1); }
    public void text(String s, float x, float y, float size, int color, Face f, int align) { if (s != null && !s.isEmpty()) c.text(s, x, y, size, color, f, align); }
    /** Shrinks the size so the text fits in maxWidth. */
    public void fit(String s, float x, float y, float size, float maxWidth, int color, Face f, int align) {
        if (s == null || s.isEmpty()) return;
        float w = c.width(s, size, f);
        c.text(s, x, y, w > maxWidth ? size * maxWidth / w : size, color, f, align);
    }
    public float width(String s, float size, Face f) { return s == null ? 0 : c.width(s, size, f); }
    public void head(UUID id, String skin, float x, float y, float size) { if (id != null) c.head(id, skin == null ? "" : skin, x, y, size); }

    /** Round avatar with a thin ring (the ring is drawn over the edge). */
    public void avatar(UUID id, String skin, float cx, float cy, float r, int ring) {
        if (id == null) return;
        c.arc(cx, cy, 0, r + 1.5f, 0, 1, 0xFF0A1018);
        c.avatar(id, skin == null ? "" : skin, cx, cy, r);
        if (ring != 0) c.arc(cx, cy, r - 1, r + 1.5f, 0, 1, ring);
    }

    // ------------------------------------------------------------------ shapes (rects + quarter pies, no overlap so translucent fills stay even)
    public void round(float x, float y, float w, float h, float r, int argb) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= .5f) { c.fill(x, y, w, h, argb); return; }
        c.fill(x + r, y, w - 2 * r, h, argb);
        c.fill(x, y + r, r, h - 2 * r, argb);
        c.fill(x + w - r, y + r, r, h - 2 * r, argb);
        c.arc(x + r, y + r, 0, r, .75f, 1, argb);
        c.arc(x + w - r, y + r, 0, r, 0, .25f, argb);
        c.arc(x + w - r, y + h - r, 0, r, .25f, .5f, argb);
        c.arc(x + r, y + h - r, 0, r, .5f, .75f, argb);
    }
    /** Rounded outline of thickness t (inside the rectangle). */
    public void outline(float x, float y, float w, float h, float r, float t, int argb) {
        r = Math.min(r, Math.min(w, h) / 2);
        c.fill(x + r, y, w - 2 * r, t, argb);
        c.fill(x + r, y + h - t, w - 2 * r, t, argb);
        c.fill(x, y + r, t, h - 2 * r, argb);
        c.fill(x + w - t, y + r, t, h - 2 * r, argb);
        if (r <= .5f) return;
        float in = Math.max(0, r - t);
        c.arc(x + r, y + r, in, r, .75f, 1, argb);
        c.arc(x + w - r, y + r, in, r, 0, .25f, argb);
        c.arc(x + w - r, y + h - r, in, r, .25f, .5f, argb);
        c.arc(x + r, y + h - r, in, r, .5f, .75f, argb);
    }
    /** Glass panel: translucent fill and a faint border. */
    public void panel(float x, float y, float w, float h, float r, int fill, int border) {
        round(x, y, w, h, r, fill);
        if (border != 0) outline(x, y, w, h, r, 1.5f, border);
    }
    /** Vertical gradient in strips (top colour → bottom colour). */
    public void vgrad(float x, float y, float w, float h, int top, int bottom) {
        int n = Math.max(1, Math.round(h / 6));
        for (int i = 0; i < n; i++) { float a = y + h * i / n, b = y + h * (i + 1) / n; c.fill(x, a, w, b - a, mix(top, bottom, (i + .5f) / n)); }
    }
    /** Horizontal gradient in strips (left colour → right colour). */
    public void hgrad(float x, float y, float w, float h, int left, int right) {
        int n = Math.max(1, Math.round(w / 6));
        for (int i = 0; i < n; i++) { float a = x + w * i / n, b = x + w * (i + 1) / n; c.fill(a, y, b - a, h, mix(left, right, (i + .5f) / n)); }
    }
    public static int mix(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int ca = (a >>> sh) & 255, cb = (b >>> sh) & 255;
            r |= Math.round(ca + (cb - ca) * t) << sh;
        }
        return r;
    }

    /**
     * Button: base part at its rectangle; hover / pressed / disabled variants ("_hover", "_pressed", "_disabled") when they exist.
     * @return true when the pointer is over it (enabled)
     */
    public boolean button(String id, String part, float x, float y, float w, float h, boolean enabled) {
        boolean over = enabled && over(x, y, w, h);
        String variant = !enabled ? "_disabled" : id.equals(pressed) && over ? "_pressed" : over ? "_hover" : "";
        String use = !variant.isEmpty() && Parts.exists(part + variant) ? part + variant : part;
        c.image(use, x, y, w, h, 1);
        if (enabled) hit(id, x, y, w, h);
        return over;
    }
}
