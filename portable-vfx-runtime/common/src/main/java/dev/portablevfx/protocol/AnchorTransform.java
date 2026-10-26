package dev.portablevfx.protocol;

/** Dependency-free local-offset math. Matches render rotateXYZ(pitch, -yaw, roll): local roll, yaw, then pitch, in degrees. */
public final class AnchorTransform {
    private AnchorTransform() { }
    public static double[] rotate(double x, double y, double z, double yaw, double pitch, double roll) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(roll)) {
            throw new IllegalArgumentException("finite offset and rotation required");
        }
        double r = Math.toRadians(roll % 360), p = Math.toRadians(pitch % 360), h = Math.toRadians(yaw % 360);
        double xx = Math.cos(r) * x - Math.sin(r) * y;
        double yy = Math.sin(r) * x + Math.cos(r) * y;
        double xh = Math.cos(h) * xx - Math.sin(h) * z;
        double zh = Math.sin(h) * xx + Math.cos(h) * z;
        return new double[] {xh, Math.cos(p) * yy - Math.sin(p) * zh,
                Math.sin(p) * yy + Math.cos(p) * zh};
    }
}
