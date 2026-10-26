package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.util.Vector;

/** Pure math helpers (unit tested). */
final class Geometry {
    private Geometry() {}

    /** Longest sub-step a travelling hit checks at once (blocks). */
    static final double MAX_STEP = 0.4;
    /**
     * A04: one tick's travel split into equal sub-steps of at most {@link #MAX_STEP}, so the total moved is exactly
     * `distance` (the old loop added whole 0.4 steps and overshot: speed 0.5 moved 0.8).
     */
    static int steps(double distance) {
        if (!(distance > 0) || !Double.isFinite(distance)) return 1;
        return Math.max(1, (int) Math.ceil(distance / MAX_STEP - 1e-9));
    }

    /** Horizontal cone test. `to` is the offset from apex to the point. */
    static boolean inCone(Vector facing, Vector to, double angleDeg) {
        Vector a = facing.clone().setY(0), b = to.clone().setY(0);
        if (b.lengthSquared() < 1e-6) return true;
        if (a.lengthSquared() < 1e-6) return false;
        double cos = a.normalize().dot(b.normalize());
        return cos >= Math.cos(Math.toRadians(angleDeg / 2));
    }

    /** Wall test in the wall's local frame: width along `right`, depth along `forward`, height up from base. */
    static boolean inWall(Vector base, Vector forward, Vector point, double width, double depth, double height) {
        Vector f = forward.clone().setY(0);
        if (f.lengthSquared() < 1e-6) return false;
        f.normalize();
        Vector right = new Vector(-f.getZ(), 0, f.getX());
        Vector d = point.clone().subtract(base);
        double along = d.dot(right), front = d.dot(f), up = d.getY();
        return Math.abs(along) <= width / 2 && Math.abs(front) <= depth / 2 && up >= -0.5 && up <= height;
    }

    /** Split a health pool proportionally to each max health, never exceeding a max, never creating health. */
    static double[] share(double[] current, double[] max) {
        double total = 0, maxTotal = 0;
        for (int i = 0; i < current.length; i++) { total += Math.max(0, current[i]); maxTotal += Math.max(0, max[i]); }
        double[] out = new double[current.length];
        if (maxTotal <= 0) return current.clone();
        double ratio = Math.min(1, total / maxTotal);
        for (int i = 0; i < out.length; i++) out[i] = Math.max(0.5, Math.min(max[i], max[i] * ratio));
        return out;
    }

    /** Damage multiplier for the n-th pierced target (0-based). */
    static double falloff(double perHit, int index) { return Math.pow(Math.clamp(perHit, 0, 1), Math.max(0, index)); }
}
