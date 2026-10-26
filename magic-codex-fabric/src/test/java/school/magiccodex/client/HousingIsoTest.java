package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HousingIsoTest {
    @Test void floorRoundTrip() {
        var iso = HousingIso.fit(5, 300, 90, 1300, 500, 1f, 0, 0);
        for (float x : new float[] {0, 1.5f, 4.25f}) for (float z : new float[] {0, 2f, 3.75f}) {
            float[] back = iso.floorAt(iso.sx(x, 0, z), iso.sy(x, 0, z));
            assertEquals(x, back[0], 1e-3);
            assertEquals(z, back[1], 1e-3);
        }
    }

    @Test void wallRoundTrip() {
        var iso = HousingIso.fit(4, 0, 0, 1000, 600, 1.3f, 20, -10);
        float[] left = iso.wallAt(iso.sx(0, 1.5f, 2.5f), iso.sy(0, 1.5f, 2.5f), 0);
        assertEquals(2.5f, left[0], 1e-3); assertEquals(1.5f, left[1], 1e-3);
        float[] right = iso.wallAt(iso.sx(3.25f, 2f, 0), iso.sy(3.25f, 2f, 0), 1);
        assertEquals(3.25f, right[0], 1e-3); assertEquals(2f, right[1], 1e-3);
        assertEquals(0, iso.wallSide(iso.sx(0, 0, 2)));
        assertEquals(1, iso.wallSide(iso.sx(2, 0, 0)));
    }

    @Test void roomFitsArea() {
        var iso = HousingIso.fit(8, 300, 90, 1300, 500, 1f, 0, 0);
        float top = iso.sy(0, 4, 0), bottom = iso.sy(8, 0, 8), left = iso.sx(0, 0, 8), right = iso.sx(8, 0, 0);
        assertTrue(top >= 90 && bottom <= 590, "vertical " + top + ".." + bottom);
        assertTrue(left >= 300 && right <= 1600, "horizontal " + left + ".." + right);
    }

    @Test void hullHitTest() {
        var iso = new HousingIso(4, 100, 500, 200);
        float[][] hull = iso.boxHull(1, 1, 2, 2, 1);
        assertTrue(hull.length >= 6);
        assertTrue(HousingIso.inside(hull, iso.sx(1.5f, .5f, 1.5f), iso.sy(1.5f, .5f, 1.5f)));
        assertFalse(HousingIso.inside(hull, iso.sx(3.5f, 0, 3.5f), iso.sy(3.5f, 0, 3.5f)));
    }
}
