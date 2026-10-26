package school.magiccodex.protocol;

import java.io.*;

/**
 * Enemy attack warning on the ground (server MagicMythic -> client MagicCodex): "예고 장판".
 * Owner 2026-10-19: every area attack that is not a body-strike skill shows one first; enemy warnings are red.
 * Shapes as in the reference sheet: circle (원형), line (직선), cone (부채꼴) - translucent red fill + bright red outline.
 *
 * The server keeps the hit test ({@link #inside}) on exactly the same numbers it sends, so the drawn area is the hit area.
 *
 * v1 frame: byte version=1, byte op
 *   SHOW  (op 1): int id, byte shape, double x, double y, double z (origin on the ground), float yaw (Minecraft degrees,
 *                 forward = (-sin yaw, 0, cos yaw)), float size (circle/cone radius, line length), float width (line),
 *                 float angle (cone, full degrees), short ticks (warning time), int argb
 *   CLEAR (op 2): int id (caster died / skill cancelled: remove it now)
 */
public final class TelegraphProtocol {
    public static final String CHANNEL = "magiccodex:telegraph";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 128;
    public static final int OP_SHOW = 1, OP_CLEAR = 2;
    /** Enemy red (fill and outline alphas are applied by the client). */
    public static final int RED = 0xFFFF2B2B;
    public static final float MAX_SIZE = 48, MAX_WIDTH = 24;
    public static final int MAX_TICKS = 20 * 30;

    public enum Shape {
        CIRCLE, LINE, CONE;
        public static Shape of(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "circle", "원형", "원" -> CIRCLE;
                case "line", "직선", "rect" -> LINE;
                case "cone", "부채꼴", "fan" -> CONE;
                default -> null;
            };
        }
    }

    public sealed interface Frame permits Show, Clear {}
    public record Show(int id, Shape shape, double x, double y, double z, float yaw, float size, float width, float angle,
                       int ticks, int argb) implements Frame {
        public Show {
            if (shape == null) throw new IllegalArgumentException("shape");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)) throw new IllegalArgumentException("pos");
            if (!Float.isFinite(size) || size <= 0 || size > MAX_SIZE) throw new IllegalArgumentException("size");
            width = Float.isFinite(width) ? Math.clamp(width, 0, MAX_WIDTH) : 0;
            angle = Float.isFinite(angle) ? Math.clamp(angle, 1, 360) : 60;
            if (shape == Shape.LINE && width <= 0) throw new IllegalArgumentException("width");
            ticks = Math.clamp(ticks, 1, MAX_TICKS);
        }
        /** Forward direction on the ground. */
        public double fx() { return -Math.sin(Math.toRadians(yaw)); }
        public double fz() { return Math.cos(Math.toRadians(yaw)); }
    }
    public record Clear(int id) implements Frame {}

    private TelegraphProtocol() {}

    /**
     * Is the point (px, pz) inside the shape, with `pad` blocks of slack (the target's half width, so a body touching
     * the edge counts)? Height is the caller's job.
     */
    public static boolean inside(Show s, double px, double pz, double pad) {
        double dx = px - s.x(), dz = pz - s.z();
        pad = Math.max(0, pad);
        switch (s.shape()) {
            case CIRCLE -> { return dx * dx + dz * dz <= sq(s.size() + pad); }
            case LINE -> {
                double along = dx * s.fx() + dz * s.fz(), side = Math.abs(dx * s.fz() - dz * s.fx());
                return along >= -pad && along <= s.size() + pad && side <= s.width() / 2 + pad;
            }
            case CONE -> {
                double d = Math.hypot(dx, dz);
                if (d > s.size() + pad) return false;
                if (d <= pad || s.angle() >= 360) return true;
                double half = Math.toRadians(s.angle() / 2);
                double cos = (dx * s.fx() + dz * s.fz()) / d;
                if (cos >= Math.cos(half)) return true;
                // just outside the angle: within pad of one of the two straight edges
                for (int sign = -1; sign <= 1; sign += 2) {
                    double a = sign * half, ex = s.fx() * Math.cos(a) - s.fz() * Math.sin(a), ez = s.fx() * Math.sin(a) + s.fz() * Math.cos(a);
                    double t = Math.clamp(dx * ex + dz * ez, 0, s.size());
                    if (sq(dx - ex * t) + sq(dz - ez * t) <= pad * pad) return true;
                }
                return false;
            }
        }
        return false;
    }
    private static double sq(double v) { return v * v; }

    public static byte[] encode(Frame frame) {
        try {
            var b = new ByteArrayOutputStream(64);
            var o = new DataOutputStream(b);
            o.writeByte(VERSION);
            switch (frame) {
                case Show s -> {
                    o.writeByte(OP_SHOW); o.writeInt(s.id()); o.writeByte(s.shape().ordinal());
                    o.writeDouble(s.x()); o.writeDouble(s.y()); o.writeDouble(s.z());
                    o.writeFloat(s.yaw()); o.writeFloat(s.size()); o.writeFloat(s.width()); o.writeFloat(s.angle());
                    o.writeShort(s.ticks()); o.writeInt(s.argb());
                }
                case Clear c -> { o.writeByte(OP_CLEAR); o.writeInt(c.id()); }
            }
            if (b.size() > MAX_BYTES) throw new IOException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Frame decode(byte[] raw) {
        try {
            if (raw == null || raw.length > MAX_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readUnsignedByte() != VERSION) throw new IOException("version");
            Frame f = switch (i.readUnsignedByte()) {
                case OP_SHOW -> {
                    int id = i.readInt(), shape = i.readUnsignedByte();
                    if (shape >= Shape.values().length) throw new IOException("shape");
                    yield new Show(id, Shape.values()[shape], i.readDouble(), i.readDouble(), i.readDouble(),
                        i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readUnsignedShort(), i.readInt());
                }
                case OP_CLEAR -> new Clear(i.readInt());
                default -> throw new IOException("op");
            };
            if (i.available() != 0) throw new IOException("trailing");
            return f;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
}
