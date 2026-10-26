package dev.portablevfx.paper.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectRequestTest {
    @Test void convenienceFactoryUsesBoundedDefaults() {
        EffectRequest request = EffectRequest.at("portablevfx:demo", "world", 1, 2, 3);
        assertEquals(1, request.scale());
        assertEquals(40, request.durationTicks());
        assertEquals(64, request.radius());
        assertEquals(0xFFFFFF, request.rgb());
    }

    @Test void rejectsInvalidEffectIdWorldAndNonFinitePosition() {
        assertThrows(IllegalArgumentException.class, () -> EffectRequest.at("Demo", "world", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EffectRequest.at("portablevfx:demo", " ", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> EffectRequest.at("portablevfx:demo", "world", Double.NaN, 0, 0));
    }

    @Test void rejectsInvalidRadiusAndSharedWireValues() {
        assertThrows(IllegalArgumentException.class, () -> request(1, 40, 0xFFFFFF, 1, 257));
        assertThrows(IllegalArgumentException.class, () -> request(0, 40, 0xFFFFFF, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> request(1, 0, 0xFFFFFF, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> request(1, 40, 0x1000000, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> request(1, 40, 0xFFFFFF, Float.NaN, 64));
    }

    @Test void extendedOptionsValidateTargetAnchorAndBounds() {
        var target=java.util.UUID.randomUUID();
        var options=PlaybackOptions.attached(target,dev.portablevfx.protocol.EffectAnchor.HEAD,42);
        assertEquals(target,options.followEntity()); assertEquals(42,options.seed()); assertEquals(-1,options.startTick());
        assertNull(PlaybackOptions.world(0).followEntity());
        assertThrows(IllegalArgumentException.class,()->PlaybackOptions.attached(null,dev.portablevfx.protocol.EffectAnchor.HEAD,0));
        assertThrows(IllegalArgumentException.class,()->PlaybackOptions.attached(target,dev.portablevfx.protocol.EffectAnchor.WORLD,0));
        assertThrows(IllegalArgumentException.class,()->new PlaybackOptions(target,dev.portablevfx.protocol.EffectAnchor.HEAD,65,0,0,0,-1));
        assertThrows(IllegalArgumentException.class,()->new PlaybackOptions(null,dev.portablevfx.protocol.EffectAnchor.WORLD,0,0,0,0,-2));
    }

    private static EffectRequest request(float scale, int duration, int rgb, float opacity, double radius) {
        return new EffectRequest("portablevfx:demo", "world", 0, 0, 0,
                scale, duration, 0, 0, 0, rgb, opacity, radius);
    }
}
