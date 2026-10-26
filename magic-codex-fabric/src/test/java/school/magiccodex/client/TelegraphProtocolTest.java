package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TelegraphProtocol;
import school.magiccodex.protocol.TelegraphProtocol.*;

final class TelegraphProtocolTest {
    @Test void roundTrip() {
        var s = new Show(7, Shape.CONE, 10.5, 64, -3.25, 90, 6, 0, 70, 30, TelegraphProtocol.RED);
        assertEquals(s, TelegraphProtocol.decode(TelegraphProtocol.encode(s)));
        var c = new Clear(7);
        assertEquals(c, TelegraphProtocol.decode(TelegraphProtocol.encode(c)));
    }

    @Test void rejectsBadFrames() {
        assertThrows(IllegalArgumentException.class, () -> TelegraphProtocol.decode(new byte[]{1, 9}));
        assertThrows(IllegalArgumentException.class, () -> new Show(1, Shape.LINE, 0, 0, 0, 0, 5, 0, 0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new Show(1, Shape.CIRCLE, 0, 0, 0, 0, 0, 0, 0, 20, 0));
        byte[] ok = TelegraphProtocol.encode(new Clear(3)), extra = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> TelegraphProtocol.decode(extra));
    }

    @Test void circleHit() {
        var s = new Show(1, Shape.CIRCLE, 0, 0, 0, 0, 3, 0, 0, 20, 0);
        assertTrue(TelegraphProtocol.inside(s, 2.9, 0, 0));
        assertFalse(TelegraphProtocol.inside(s, 3.2, 0, 0));
        assertTrue(TelegraphProtocol.inside(s, 3.2, 0, 0.3));
    }

    @Test void lineHitFollowsYaw() {
        // yaw 0 = +Z forward; yaw 90 = -X forward (Minecraft)
        var s = new Show(1, Shape.LINE, 0, 0, 0, 0, 8, 2, 0, 20, 0);
        assertTrue(TelegraphProtocol.inside(s, 0.9, 7.5, 0));
        assertFalse(TelegraphProtocol.inside(s, 0, -0.5, 0));
        assertFalse(TelegraphProtocol.inside(s, 1.2, 4, 0));
        var w = new Show(1, Shape.LINE, 0, 0, 0, 90, 8, 2, 0, 20, 0);
        assertTrue(TelegraphProtocol.inside(w, -6, 0.5, 0));
        assertFalse(TelegraphProtocol.inside(w, 6, 0, 0));
    }

    @Test void coneHit() {
        var s = new Show(1, Shape.CONE, 0, 0, 0, 0, 6, 0, 60, 20, 0);
        assertTrue(TelegraphProtocol.inside(s, 0, 5, 0));
        assertTrue(TelegraphProtocol.inside(s, 2.5, 5, 0));      // 26.6° < 30°
        assertFalse(TelegraphProtocol.inside(s, 4, 4, 0));       // 45°
        assertFalse(TelegraphProtocol.inside(s, 0, -2, 0));      // behind
        assertFalse(TelegraphProtocol.inside(s, 0, 6.5, 0));
        assertTrue(TelegraphProtocol.inside(s, 3.1, 5, 0.3));    // just past the edge, within pad
    }
}
