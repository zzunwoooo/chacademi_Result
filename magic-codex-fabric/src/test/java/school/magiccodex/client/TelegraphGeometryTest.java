package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TelegraphProtocol;
import school.magiccodex.protocol.TelegraphProtocol.*;

final class TelegraphGeometryTest {
    private static Show show(Shape shape, float yaw, float size, float width, float angle) {
        return new Show(1, shape, 10, 64, 20, yaw, size, width, angle, 20, TelegraphProtocol.RED);
    }

    @Test void circleOutlineIsClosedAndOnTheRadius() {
        var m = TelegraphGeometry.of(show(Shape.CIRCLE, 0, 3, 0, 0));
        var o = m.outline();
        assertEquals(o.get(0), o.get(o.size() - 1));
        for (var p : o) assertEquals(3, Math.hypot(p.x() - 10, p.z() - 20), 1e-9);
        assertEquals(0, m.fill().size() % 3);
        for (int i = 1; i < o.size(); i++) assertFalse(TelegraphGeometry.sharp(o, i), "arc joint " + i);
    }

    @Test void lineMatchesTheServerHitBox() {
        var s = show(Shape.LINE, 90, 8, 2, 0);              // forward = -X
        var m = TelegraphGeometry.of(s);
        assertEquals(5, m.outline().size());
        for (var p : m.outline()) assertTrue(TelegraphProtocol.inside(s, p.x(), p.z(), 1e-6), p.toString());
        double minX = m.outline().stream().mapToDouble(TelegraphGeometry.P::x).min().orElseThrow();
        assertEquals(2, minX, 1e-9);
        for (int i = 0; i < 4; i++) assertTrue(TelegraphGeometry.sharp(m.outline(), i));
    }

    @Test void coneEdgesAreInsideAndCornersSharp() {
        var s = show(Shape.CONE, 0, 6, 0, 70);
        var m = TelegraphGeometry.of(s);
        var o = m.outline();
        assertEquals(o.get(0), o.get(o.size() - 1));
        for (var p : o) assertTrue(TelegraphProtocol.inside(s, p.x(), p.z(), 1e-6), p.toString());
        assertTrue(TelegraphGeometry.sharp(o, 0));
        assertTrue(TelegraphGeometry.sharp(o, 1));
        assertFalse(TelegraphGeometry.sharp(o, 3));
    }

    @Test void fadeInHoldFadeOut() {
        assertEquals(0, TelegraphGeometry.alpha(1000, 2000, 1000), 1e-6);
        assertEquals(1, TelegraphGeometry.alpha(1000, 2000, 1500), 1e-6);
        assertTrue(TelegraphGeometry.alpha(1000, 2000, 2070) < 1);
        assertTrue(TelegraphGeometry.alpha(1000, 2000, 2000 + TelegraphGeometry.FADE_OUT) < 0);
    }
}
