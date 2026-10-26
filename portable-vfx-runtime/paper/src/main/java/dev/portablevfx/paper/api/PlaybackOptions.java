package dev.portablevfx.paper.api;

import dev.portablevfx.protocol.EffectAnchor;
import dev.portablevfx.protocol.PlayEffect;
import java.util.UUID;

/** Extended visual playback only. startTick is world game time, -1 means now.
 * Named anchors are pose-based approximations, not gameplay positions or bone bindings. */
public record PlaybackOptions(UUID followEntity, EffectAnchor anchor, float offsetX, float offsetY, float offsetZ,
                              long seed, long startTick) {
    public PlaybackOptions {
        new PlayEffect(new UUID(0, 0), "portablevfx:validation", "minecraft:overworld", 0, 0, 0,
                0, 0, 0, 1, 0xffffff, 1, 1, followEntity, seed, startTick, anchor, offsetX, offsetY, offsetZ);
    }
    public static PlaybackOptions world(long seed) {
        return new PlaybackOptions(null, EffectAnchor.WORLD, 0, 0, 0, seed, -1);
    }
    public static PlaybackOptions attached(UUID target, EffectAnchor anchor, long seed) {
        return new PlaybackOptions(target, anchor, 0, 0, 0, seed, -1);
    }
}
