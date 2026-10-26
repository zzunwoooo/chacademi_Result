package school.magiccodex.protocol;

import java.nio.ByteBuffer;

/**
 * 거꾸로 된 마나병 (아티팩트, 2026-10-26): server -> client "your mana bar is an overheat gauge now".
 * Separate channel so the mana response format (41/49 bytes) stays the same and old clients never see it
 * (the server only sends to clients that registered this channel). While on, the normal mana snapshot means
 * current = free capacity, maximum = gauge maximum, so heat = maximum - current.
 * Payload (6 bytes): version 1, on (0/1), lock remaining milliseconds (int, 0 = not locked).
 */
public final class ManaHeatProtocol {
    public static final String CHANNEL = "magiccodex:mana_heat";
    public record State(boolean on, int lockMillis) {
        public State { if (lockMillis < 0 || lockMillis > 600_000) throw new IllegalArgumentException("Invalid heat lock"); }
    }
    public static byte[] encode(State s) { return ByteBuffer.allocate(6).put((byte) 1).put((byte) (s.on() ? 1 : 0)).putInt(s.lockMillis()).array(); }
    public static State decode(byte[] b) {
        if (b == null || b.length != 6) throw new IllegalArgumentException("Invalid heat size");
        var x = ByteBuffer.wrap(b);
        if (x.get() != 1) throw new IllegalArgumentException("Invalid heat version");
        byte on = x.get();
        if (on != 0 && on != 1) throw new IllegalArgumentException("Invalid heat flag");
        return new State(on == 1, x.getInt());
    }
    private ManaHeatProtocol() {}
}
