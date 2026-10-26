package school.chacademia.magicmythic;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test void coneAcceptsFrontRejectsSide() {
        Vector north = new Vector(0, 0, -1);
        assertTrue(Geometry.inCone(north, new Vector(0.2, 0, -3), 50));
        assertFalse(Geometry.inCone(north, new Vector(3, 0, -1), 50));
        assertFalse(Geometry.inCone(north, new Vector(0, 0, 3), 50));
    }
    @Test void wallIsWideAndShallow() {
        Vector base = new Vector(0, 64, 0), north = new Vector(0, 0, -1);
        assertTrue(Geometry.inWall(base, north, new Vector(2.5, 64.5, 0.3), 6, 1.5, 3));
        assertFalse(Geometry.inWall(base, north, new Vector(0, 64.5, 2), 6, 1.5, 3));
        assertFalse(Geometry.inWall(base, north, new Vector(4, 64.5, 0), 6, 1.5, 3));
        assertFalse(Geometry.inWall(base, north, new Vector(0, 68, 0), 6, 1.5, 3));
    }
    @Test void shareNeverCreatesHealth() {
        double[] out = Geometry.share(new double[]{2, 20, 10}, new double[]{20, 20, 40});
        double sum = out[0] + out[1] + out[2];
        assertEquals(32, sum, 1e-6);
        assertEquals(out[0] / 20, out[2] / 40, 1e-9);
    }
    @Test void shareCapsAtMax() {
        double[] out = Geometry.share(new double[]{20, 20}, new double[]{20, 10});
        assertEquals(20, out[0], 1e-9);
        assertEquals(10, out[1], 1e-9);
    }
    @Test void falloffDropsPerTarget() {
        assertEquals(1, Geometry.falloff(0.7, 0), 1e-9);
        assertEquals(0.49, Geometry.falloff(0.7, 2), 1e-9);
    }
}
