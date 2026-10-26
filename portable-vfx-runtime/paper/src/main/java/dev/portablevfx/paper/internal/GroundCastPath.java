package dev.portablevfx.paper.internal;

import dev.portablevfx.protocol.VfxProtocol;

/** Input bounds for the generic, flat-ground operator preview. No Minecraft/gameplay dependencies. */
public final class GroundCastPath {
    private GroundCastPath() {}
    public static boolean holding(long elapsedTicks, int holdTicks) { return elapsedTicks <= holdTicks; }
    public static void validate(double width, double speed, double distance, int holdTicks, int ttl) {
        if (!Double.isFinite(width) || width <= 0 || width > VfxProtocol.MAX_EFFECT_WIDTH)
            throw new IllegalArgumentException("width must be finite and in (0, 64] metres");
        if (!Double.isFinite(speed) || speed < .1 || speed > 4)
            throw new IllegalArgumentException("speed must be 0.1..4 blocks/tick");
        if (!Double.isFinite(distance) || distance < 1 || distance > 128)
            throw new IllegalArgumentException("distance must be 1..128 blocks");
        if (holdTicks < 0 || holdTicks > 100)
            throw new IllegalArgumentException("holdTicks must be 0..100");
        if (holdTicks + Math.ceil(distance / speed) >= ttl)
            throw new IllegalArgumentException("hold and travel must finish before the configured cast TTL (" + ttl + " ticks)");
    }
}
