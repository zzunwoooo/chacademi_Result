package dev.portablevfx.paper.api;

import dev.portablevfx.protocol.PlayEffect;
import java.util.Objects;
import java.util.UUID;

/** Immutable, validated world-space effect request. Angles are degrees; duration is ticks. */
public record EffectRequest(
        String effectId, String worldName, double x, double y, double z,
        float scale, int durationTicks, float yaw, float pitch, float roll,
        int rgb, float opacity, double radius) {
    public EffectRequest {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank() || worldName.length() > 128) {
            throw new IllegalArgumentException("worldName must contain 1..128 characters");
        }
        // The shared record is the single source of truth for wire-value validation.
        new PlayEffect(new UUID(0, 0), effectId, "minecraft:overworld", x, y, z,
                yaw, pitch, roll, scale, rgb, opacity, durationTicks);
        if (!Double.isFinite(radius) || radius <= 0 || radius > 256) {
            throw new IllegalArgumentException("radius must be finite and in (0, 256]");
        }
    }

    public static EffectRequest at(String effectId, String worldName, double x, double y, double z) {
        return new EffectRequest(effectId, worldName, x, y, z, 1, 40,
                0, 0, 0, 0xFFFFFF, 1, 64);
    }
}
