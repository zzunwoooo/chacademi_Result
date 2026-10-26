package school.magiccodex.client;

/**
 * 지도 이동 창의 계산 (2026-10-23) — no Minecraft classes, so the layout / zoom / click math is unit tested.
 *
 * Every tab has a "map space" (u, v): for an image map it is the image's own pixels (u right, v down), tied to the
 * world by three reference corners in travel.yml (top-left, top-right, bottom-left pixel corner = world x,z), so any
 * rotation, mirror or scale works and replacing the image only needs new numbers. For terrain / empty tabs map space
 * is the world itself (u = x, v = z, north up).
 */
final class TravelMapMath {
    private TravelMapMath() {}

    /** world (x, z) <-> map (u, v). */
    record Affine(double ox, double oz, double ux, double uz, double vx, double vz, double w, double h) {
        /** Image of w x h px whose top-left / top-right / bottom-left corners are the given world points. */
        static Affine image(double tlX, double tlZ, double trX, double trZ, double blX, double blZ, int w, int h) {
            return new Affine(tlX, tlZ, (trX - tlX) / w, (trZ - tlZ) / w, (blX - tlX) / h, (blZ - tlZ) / h, w, h);
        }
        /** u = x - minX, v = z - minZ (north up), extent = the bounds. */
        static Affine world(double minX, double minZ, double maxX, double maxZ) {
            return new Affine(minX, minZ, 1, 0, 0, 1, maxX - minX, maxZ - minZ);
        }
        double worldX(double u, double v) { return ox + u * ux + v * vx; }
        double worldZ(double u, double v) { return oz + u * uz + v * vz; }
        double det() { return ux * vz - uz * vx; }
        boolean valid() { double d = det(); return Double.isFinite(d) && Math.abs(d) > 1e-12; }
        double u(double x, double z) { double dx = x - ox, dz = z - oz; return (dx * vz - dz * vx) / det(); }
        double v(double x, double z) { double dx = x - ox, dz = z - oz; return (ux * dz - uz * dx) / det(); }
        /** Map-space angle (radians, 0 = up / -v, clockwise) of a world yaw (Minecraft: 0 = +z south, 90 = -x west). */
        double heading(float yaw) {
            double r = Math.toRadians(yaw);
            double dx = -Math.sin(r), dz = Math.cos(r);
            double du = (dx * vz - dz * vx) / det(), dv = (ux * dz - uz * dx) / det();
            return Math.atan2(du, -dv);
        }
    }
    // ------------------------------------------------------------------ window layout (design units = main_frame.png pixels)

    /** main_frame.png 1254 x 1254: visible frame, map window, header and footer bands. */
    static final float FRAME_W = 1254, FRAME_H = 1254;
    static final float BOX_L = 54, BOX_T = 57, BOX_R = 1202, BOX_B = 1196;
    static final float MAP_L = 97, MAP_T = 197, MAP_R = 1157, MAP_B = 1057;
    /** Drawer toggle (drawer_expand 131x403 / drawer_collapse 92x272, trimmed) hugs the frame's right edge. */
    static final float TOGGLE_W = 44, TOGGLE_H = 140, TOGGLE_GAP = 2;
    /** Drawer panel (drawer_panel.png, padding trimmed to 621 x 1809), same height as the frame box. */
    static final float PANEL_ART_W = 621, PANEL_ART_H = 1809;
    static final float PANEL_H = BOX_B - BOX_T, PANEL_W = PANEL_H * PANEL_ART_W / PANEL_ART_H, PANEL_GAP = 12;
    /** Width of the whole arrangement when the list is open (frame box + toggle + gap + panel). */
    static final float OPEN_W = (BOX_R - BOX_L) + TOGGLE_GAP + TOGGLE_W + PANEL_GAP + PANEL_W;
    static final float CLOSED_W = (BOX_R - BOX_L) + TOGGLE_GAP + TOGGLE_W;

    /**
     * Screen placement. One scale for both states (opening the list never resizes the map): it is chosen so the open
     * arrangement fits the screen with margins. open = 0..1 (eased): the frame slides left so the arrangement stays centred.
     */
    record Placement(float scale, float frameX, float frameY) {
        /** Screen x of a design x on the frame image. */
        float x(float design) { return frameX + design * scale; }
        float y(float design) { return frameY + design * scale; }
        double designX(double screen) { return (screen - frameX) / scale; }
        double designY(double screen) { return (screen - frameY) / scale; }
    }
    static final float MARGIN = .04f, MAX_SCALE_FRACTION_H = .86f;

    static Placement place(int screenW, int screenH, float open) {
        float usableW = screenW * (1 - 2 * MARGIN), usableH = Math.min(screenH * MAX_SCALE_FRACTION_H, screenH * (1 - 2 * MARGIN));
        float scale = Math.min(usableW / OPEN_W, usableH / (BOX_B - BOX_T));
        scale = Math.max(scale, 1e-3f);
        float width = CLOSED_W + (OPEN_W - CLOSED_W) * Math.clamp(open, 0, 1);
        float left = (screenW - width * scale) / 2;           // screen x of the box's left edge
        float top = (screenH - (BOX_B - BOX_T) * scale) / 2;
        return new Placement(scale, left - BOX_L * scale, top - BOX_T * scale);
    }

    // ------------------------------------------------------------------ the map camera

    /** View of a map: centre (u, v) and design-pixels per map unit. */
    static final class Camera {
        double u, v, zoom = 1, tu, tv, tzoom = 1;
        double minZoom = .05, maxZoom = 8;
        void snap() { u = tu; v = tv; zoom = tzoom; }
        /** Smooth approach (exponential, frame-rate independent). Zoom eases in log space so in/out feel the same. */
        void step(double dt) {
            double k = 1 - Math.exp(-dt * 7.5);
            u += (tu - u) * k; v += (tv - v) * k;
            zoom = Math.exp(Math.log(zoom) + (Math.log(tzoom) - Math.log(zoom)) * k);
            if (Math.abs(tu - u) * zoom < .01 && Math.abs(tv - v) * zoom < .01) { u = tu; v = tv; }
            if (Math.abs(tzoom - zoom) < 1e-5 * tzoom) zoom = tzoom;
        }
        boolean moving() { return u != tu || v != tv || zoom != tzoom; }
    }

    static final double VIEW_W = MAP_R - MAP_L, VIEW_H = MAP_B - MAP_T;
    static double viewCx() { return (MAP_L + MAP_R) / 2.0; }
    static double viewCy() { return (MAP_T + MAP_B) / 2.0; }

    /** Zoom that shows the whole extent (w x h map units) inside the window with a small border. */
    static double fitZoom(double w, double h) {
        if (!(w > 0) || !(h > 0)) return 1;
        return Math.min(VIEW_W / w, VIEW_H / h) * .94;
    }
    static void home(Camera c, double w, double h) {
        double z = fitZoom(w, h);
        c.minZoom = z * .9; c.maxZoom = Math.max(z * 2, Math.max(z, 1) * 10);
        c.tu = w / 2; c.tv = h / 2; c.tzoom = z;
    }
    /** Zoom toward a point (keeps it under the cursor): factor > 1 = in. */
    static void zoomAt(Camera c, double designX, double designY, double factor, double w, double h) {
        double nz = Math.clamp(c.tzoom * factor, c.minZoom, c.maxZoom);
        double mu = c.tu + (designX - viewCx()) / c.tzoom, mv = c.tv + (designY - viewCy()) / c.tzoom;
        c.tu = mu - (designX - viewCx()) / nz; c.tv = mv - (designY - viewCy()) / nz; c.tzoom = nz;
        clamp(c, w, h);
    }
    /** Centre a map point and zoom in to at least `focusZoom` (smoothly). */
    static void focus(Camera c, double u, double v, double focusZoom, double w, double h) {
        c.tzoom = Math.clamp(Math.max(c.tzoom, focusZoom), c.minZoom, c.maxZoom);
        c.tu = u; c.tv = v;
        clamp(c, w, h);
    }
    static void pan(Camera c, double dDesignX, double dDesignY, double w, double h) {
        c.tu -= dDesignX / c.tzoom; c.tv -= dDesignY / c.tzoom; c.u = c.tu; c.v = c.tv;
        clamp(c, w, h);
    }
    /** Keep the centre inside the extent (a little past the edge is fine when zoomed out). */
    static void clamp(Camera c, double w, double h) {
        if (!(w > 0) || !(h > 0)) return;
        c.tu = Math.clamp(c.tu, 0, w); c.tv = Math.clamp(c.tv, 0, h);
    }
    /** Design position of a map point under the (current, animated) camera. */
    static double screenX(Camera c, double u) { return viewCx() + (u - c.u) * c.zoom; }
    static double screenY(Camera c, double v) { return viewCy() + (v - c.v) * c.zoom; }
    static double mapU(Camera c, double designX) { return c.u + (designX - viewCx()) / c.zoom; }
    static double mapV(Camera c, double designY) { return c.v + (designY - viewCy()) / c.zoom; }
    static boolean insideMap(double designX, double designY) { return designX >= MAP_L && designX < MAP_R && designY >= MAP_T && designY < MAP_B; }

    /** Text that does not fit: cut with "…" (long Korean names in the list and labels). */
    interface Measure { float width(String s); }
    static String ellipsize(String s, float max, Measure m) {
        if (s == null || s.isEmpty() || m.width(s) <= max) return s == null ? "" : s;
        int[] cps = s.codePoints().toArray();
        int lo = 0, hi = cps.length;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (m.width(new String(cps, 0, mid) + "…") <= max) lo = mid; else hi = mid - 1;
        }
        return lo == 0 ? "…" : new String(cps, 0, lo) + "…";
    }
}
