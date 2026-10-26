package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import school.magiccodex.protocol.TelegraphProtocol.Show;

/**
 * Ground geometry of a warning, in world X/Z. Pure math (tested); the renderer only lays it on the ground.
 * fill = triangles (3 points each), outline = closed polyline (last point = first).
 */
final class TelegraphGeometry {
    record P(double x, double z) {}
    record Mesh(List<P> fill, List<P> outline) {}

    /** Short fade in / out so warnings do not pop (ms). */
    static final long FADE_IN = 90, FADE_OUT = 140;

    private TelegraphGeometry() {}

    /** 0..1 visibility at `now` (fade in, hold, fade out after the end); negative = gone. */
    static float alpha(long start, long end, long now) {
        if (now >= end + FADE_OUT) return -1;
        if (now >= end) return 1 - (now - end) / (float) FADE_OUT;
        return Math.min(1, (now - start) / (float) FADE_IN);
    }

    static int segments(double radius, double degrees) {
        return Math.clamp((int) Math.ceil(Math.max(radius, 1) * 10 * degrees / 360), 12, 160);
    }

    static Mesh of(Show s) {
        var fill = new ArrayList<P>();
        var out = new ArrayList<P>();
        double fx = s.fx(), fz = s.fz(), rx = fz, rz = -fx; // right = forward rotated -90° (Minecraft yaw)
        switch (s.shape()) {
            case CIRCLE -> {
                int n = segments(s.size(), 360);
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    out.add(new P(s.x() + Math.cos(a) * s.size(), s.z() + Math.sin(a) * s.size()));
                }
                out.add(out.get(0));
                fan(fill, new P(s.x(), s.z()), out);
            }
            case LINE -> {
                double h = s.width() / 2, l = s.size();
                P a = new P(s.x() + rx * h, s.z() + rz * h), b = new P(s.x() - rx * h, s.z() - rz * h);
                P c = new P(b.x() + fx * l, b.z() + fz * l), d = new P(a.x() + fx * l, a.z() + fz * l);
                out.addAll(List.of(a, b, c, d, a));
                fill.addAll(List.of(a, b, c, a, c, d));
            }
            case CONE -> {
                double half = Math.toRadians(s.angle() / 2);
                int n = segments(s.size(), s.angle());
                var arc = new ArrayList<P>(n + 1);
                for (int i = 0; i <= n; i++) {
                    double a = -half + 2 * half * i / n, c = Math.cos(a), sn = Math.sin(a);
                    double dx = fx * c - fz * sn, dz = fx * sn + fz * c;
                    arc.add(new P(s.x() + dx * s.size(), s.z() + dz * s.size()));
                }
                P o = new P(s.x(), s.z());
                fan(fill, o, arc);
                if (s.angle() >= 360) { out.addAll(arc.subList(0, arc.size() - 1)); out.add(arc.get(0)); }
                else { out.add(o); out.addAll(arc); out.add(o); }
            }
        }
        return new Mesh(List.copyOf(fill), List.copyOf(out));
    }

    /** Is the closed polyline's corner at index j sharper than 25°? (first and last point are the same corner) */
    static boolean sharp(List<P> pts, int j) {
        int n = pts.size() - 1;
        if (n < 2) return true;
        int k = j % n;
        P prev = pts.get((k - 1 + n) % n), at = pts.get(k), next = pts.get(k + 1);
        double ax = at.x() - prev.x(), az = at.z() - prev.z(), bx = next.x() - at.x(), bz = next.z() - at.z();
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        if (la < 1e-9 || lb < 1e-9) return true;
        return (ax * bx + az * bz) / (la * lb) < Math.cos(Math.toRadians(25));
    }

    private static void fan(List<P> fill, P center, List<P> rim) {
        for (int i = 0; i + 1 < rim.size(); i++) { fill.add(center); fill.add(rim.get(i)); fill.add(rim.get(i + 1)); }
    }

}
