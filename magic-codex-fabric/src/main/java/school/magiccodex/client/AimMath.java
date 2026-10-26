package school.magiccodex.client;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Pure aiming math (no Minecraft types) so it can be unit tested. Screen coordinates are GUI-scaled pixels, y down. */
public final class AimMath {
    private AimMath() {}

    /** World offset from the camera -> screen pixel, or null when behind the camera. */
    public static float[] project(Matrix4f viewProjection, double dx, double dy, double dz, float width, float height) {
        var v = new Vector4f((float) dx, (float) dy, (float) dz, 1f);
        viewProjection.transform(v);
        if (v.w <= 1e-4f) return null;
        float nx = v.x / v.w, ny = v.y / v.w;
        return new float[]{(nx * .5f + .5f) * width, (1f - (ny * .5f + .5f)) * height};
    }

    /** Screen pixel -> normalized world direction from the camera (inverse of view*projection). */
    public static double[] unproject(Matrix4f inverseViewProjection, float sx, float sy, float width, float height) {
        float nx = sx / width * 2f - 1f, ny = 1f - sy / height * 2f;
        var near = new Vector4f(nx, ny, -1f, 1f);
        var far = new Vector4f(nx, ny, 1f, 1f);
        inverseViewProjection.transform(near);
        inverseViewProjection.transform(far);
        double ax = near.x / near.w, ay = near.y / near.w, az = near.z / near.w;
        double bx = far.x / far.w - ax, by = far.y / far.w - ay, bz = far.z / far.w - az;
        double len = Math.sqrt(bx * bx + by * by + bz * bz);
        if (!(len > 1e-9)) return new double[]{0, 0, 1};
        return new double[]{bx / len, by / len, bz / len};
    }

    /** Minecraft yaw/pitch (degrees) that look along the vector. Pitch is positive when looking down. */
    public static float[] yawPitch(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horizontal));
        return new float[]{yaw, Math.clamp(pitch, -90f, 90f)};
    }

    /** Horizontal reach on a plane {@code dy} below/above the eyes for a 3D range (0 when out of reach). */
    public static double horizontalReach(double range, double dy) {
        double r2 = range * range - dy * dy;
        return r2 <= 0 ? 0 : Math.sqrt(r2);
    }

    /** Pulls (x,z) toward (cx,cz) so that the horizontal distance is at most {@code max}. Returns {x,z,clampedFlag}. */
    /** 1-6 #6: ground spells measure range horizontally (caster above the ground). */
    public static boolean horizontalWithin(double cx, double cz, double x, double z, double max) {
        double dx = x - cx, dz = z - cz;
        return max > 0 && dx * dx + dz * dz <= max * max;
    }

    public static double[] clampHorizontal(double cx, double cz, double x, double z, double max) {
        double dx = x - cx, dz = z - cz, d = Math.sqrt(dx * dx + dz * dz);
        if (d <= max || d < 1e-9) return new double[]{x, z, 0};
        double k = Math.max(0, max) / d;
        return new double[]{cx + dx * k, cz + dz * k, 1};
    }

    /** Segments for a smooth ring of the given radius (more for big rings, capped for cost). */
    public static int ringSegments(double radius) {
        return (int) Math.clamp(Math.round(radius * 6), 24, 192);
    }

    /** Angle difference in degrees within [-180, 180). */
    public static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Cursor step in framebuffer pixels for a look delta (vanilla turns 0.15 degree per unit). */
    public static double cursorStep(double lookUnits, double framebufferHeight, double fovDegrees, double speed) {
        double pixelsPerDegree = framebufferHeight / Math.max(1, fovDegrees);
        return lookUnits * .15 * pixelsPerDegree * speed;
    }

    /**
     * Rounded, fading end of a band. {@code fromEnd} = distance from the nearer end, {@code cap} = half the band width.
     * @return {width factor (semicircle: 0 at the tip, 1 from one cap length in), alpha factor (smooth fade-in)}
     */
    public static double[] capsuleEnd(double fromEnd, double cap) {
        double e = Math.max(0, fromEnd), c = Math.max(1e-6, cap);
        double width = e >= c ? 1 : Math.sqrt(Math.max(0, 1 - (1 - e / c) * (1 - e / c)));
        double t = Math.min(1, e / (c * 1.6 + .2));
        return new double[]{width, t * t * (3 - 2 * t)};
    }
}
