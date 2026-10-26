package school.magiccodex.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #53 타운맵 그림 맞추기 계산. */
class TravelMapFitTest {
    @Test
    void statueAnchorGivesTheShippedCorners() {
        var f = TravelMapFit.anchored(493, 432, 32.5, 12.5, 0.25);
        assertArrayEquals(new double[] {-90.75, -95.5, 279.0, -95.5, -90.75, 170.5}, f.corners(1479, 1064), 1e-9);
        var back = TravelMapFit.of(f.corners(1479, 1064), 1479);
        assertEquals(493, back.px(32.5, 12.5), 1e-9);
        assertEquals(432, back.py(32.5, 12.5), 1e-9);
    }

    @Test
    void twoPointsSolveScaleAndRotation() {
        var truth = TravelMapFit.rotate(TravelMapFit.anchored(493, 432, 40, 20, 0.3), 493, 432, 10);
        var fit = TravelMapFit.two(493, 432, 40, 20, 1250, 450, truth.x(1250, 450), truth.z(1250, 450));
        assertEquals(0.3, fit.scale(), 1e-9);
        assertEquals(10, fit.degrees(), 1e-9);
        assertEquals(truth.x(100, 900), fit.x(100, 900), 1e-9);
        assertEquals(truth.z(100, 900), fit.z(100, 900), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> TravelMapFit.two(493, 432, 40, 20, 495, 433, 60, 20));
    }

    @Test
    void moveScaleRotateKeepTheAnchor() {
        var f = TravelMapFit.move(TravelMapFit.anchored(493, 432, 0, 0, 0.25), 493, 432, 40, 20);
        assertEquals(40, f.x(493, 432), 1e-9);
        var s = TravelMapFit.scale(f, 493, 432, 0.5);
        assertEquals(0.5, s.scale(), 1e-9);
        assertEquals(40, s.x(493, 432), 1e-9); assertEquals(20, s.z(493, 432), 1e-9);
        var r = TravelMapFit.rotate(s, 493, 432, 90);
        assertEquals(40, r.x(493, 432), 1e-9); assertEquals(90, r.degrees(), 1e-9);
        var m = TravelMapFit.shift(r, 3, -2);
        assertEquals(43, m.x(493, 432), 1e-9); assertEquals(18, m.z(493, 432), 1e-9);
    }
}
