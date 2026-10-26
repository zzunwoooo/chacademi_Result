package school.magiccodex.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Client -> server, sent right before an aimed cast request (2026-10-21, C04 aim): the point and target the client's
 * aim preview chose (shoulder camera aware). The server never trusts it blindly: Bridge SpellRuntime checks range,
 * line of sight and the angle to the server's look ray, and falls back to its own ray when the check fails.
 * v1: int magic, byte flags (1 = target present, 2 = air point), double x, y, z, [long, long target uuid], byte n, n ASCII spell id.
 */
public final class CastAimProtocol {
    public static final String CHANNEL = "magiccodex:cast_aim";
    private static final int MAGIC = 0x41494D01;
    public static final int FLAG_TARGET = 1, FLAG_AIR = 2;
    public static final int MIN_BYTES = 4 + 1 + 24 + 1 + 1, MAX_BYTES = 4 + 1 + 24 + 16 + 1 + 64;
    /** target = null when the point is not on an entity; air = the point was left in the air on purpose (큐디치). */
    public record Aim(String spell, double x, double y, double z, UUID target, boolean air) {
        public Aim {
            if (spell == null || !spell.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("spell");
            for (double v : new double[]{x, y, z}) if (!Double.isFinite(v) || Math.abs(v) > 30_000_000) throw new IllegalArgumentException("point");
        }
    }
    private CastAimProtocol() {}

    public static byte[] encode(Aim a) {
        try {
            var b = new ByteArrayOutputStream(MAX_BYTES); var o = new DataOutputStream(b);
            o.writeInt(MAGIC);
            o.writeByte((a.target() != null ? FLAG_TARGET : 0) | (a.air() ? FLAG_AIR : 0));
            o.writeDouble(a.x()); o.writeDouble(a.y()); o.writeDouble(a.z());
            if (a.target() != null) { o.writeLong(a.target().getMostSignificantBits()); o.writeLong(a.target().getLeastSignificantBits()); }
            byte[] id = a.spell().getBytes(StandardCharsets.US_ASCII);
            o.writeByte(id.length); o.write(id);
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Aim decode(byte[] raw) {
        try {
            if (raw == null || raw.length < MIN_BYTES || raw.length > MAX_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readInt() != MAGIC) throw new IOException("version");
            int flags = i.readUnsignedByte();
            if ((flags & ~(FLAG_TARGET | FLAG_AIR)) != 0) throw new IOException("flags");
            double x = i.readDouble(), y = i.readDouble(), z = i.readDouble();
            UUID target = (flags & FLAG_TARGET) != 0 ? new UUID(i.readLong(), i.readLong()) : null;
            int n = i.readUnsignedByte();
            if (n < 1 || n > 64 || i.available() != n) throw new IOException("spell length");
            byte[] id = new byte[n]; i.readFully(id);
            return new Aim(new String(id, StandardCharsets.US_ASCII), x, y, z, target, (flags & FLAG_AIR) != 0);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
}
