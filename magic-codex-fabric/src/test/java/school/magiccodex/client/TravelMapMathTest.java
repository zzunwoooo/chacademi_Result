package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 지도 이동 창 배치·확대·클릭 좌표 (2026-10-23, owner checks: 창 크기·비율, 목록 펼침/접힘, 확대 후 클릭 좌표, 긴 한글 이름). */
class TravelMapMathTest {
    static final int[][] SCREENS = {{854, 480}, {1280, 720}, {1366, 768}, {1920, 1080}, {2560, 1080}, {3440, 1440}, {1024, 768}, {800, 600}, {640, 480}, {1600, 1200}, {3840, 2160}};

    @Test void openListNeverResizesTheMapAndEverythingStaysOnScreen() {
        for (int[] s : SCREENS) {
            var closed = TravelMapMath.place(s[0], s[1], 0);
            var open = TravelMapMath.place(s[0], s[1], 1);
            assertEquals(closed.scale(), open.scale(), 1e-6, "scale " + s[0] + "x" + s[1]);
            float mapW = (TravelMapMath.MAP_R - TravelMapMath.MAP_L) * open.scale();
            assertEquals((TravelMapMath.MAP_R - TravelMapMath.MAP_L) * closed.scale(), mapW, 1e-4);
            for (float k = 0; k <= 1; k += .25f) {
                var p = TravelMapMath.place(s[0], s[1], k);
                float left = p.x(TravelMapMath.BOX_L), top = p.y(TravelMapMath.BOX_T), bottom = p.y(TravelMapMath.BOX_B);
                float right = p.x(TravelMapMath.BOX_L) + (TravelMapMath.CLOSED_W + (TravelMapMath.OPEN_W - TravelMapMath.CLOSED_W) * k) * p.scale();
                assertTrue(left >= 0 && top >= 0 && right <= s[0] + .01f && bottom <= s[1] + .01f, "on screen " + s[0] + "x" + s[1] + " open " + k);
            }
            // the list panel is outside the frame, to its right
            assertTrue(open.x(TravelMapMath.BOX_R + TravelMapMath.TOGGLE_GAP + TravelMapMath.TOGGLE_W + TravelMapMath.PANEL_GAP) > open.x(TravelMapMath.BOX_R));
            // compact, not wide: the frame box is about square
            assertEquals(1, (TravelMapMath.BOX_R - TravelMapMath.BOX_L) / (TravelMapMath.BOX_B - TravelMapMath.BOX_T), .02);
        }
    }

    @Test void screenToDesignRoundTrip() {
        var p = TravelMapMath.place(1366, 768, .4f);
        for (float d : new float[]{0, 97, 627, 1157, 1254}) {
            assertEquals(d, p.designX(p.x(d)), 1e-3);
            assertEquals(d, p.designY(p.y(d)), 1e-3);
        }
    }

    @Test void clickAfterZoomHitsTheSameWorldSpot() {
        // rotated + mirrored image: top-left (100,200), top-right (100,-824) (image u runs north), bottom-left (612,200)
        var a = TravelMapMath.Affine.image(100, 200, 100, -824, 612, 200, 1024, 512);
        assertTrue(a.valid());
        double x = 300, z = -100;
        assertEquals(x, a.worldX(a.u(x, z), a.v(x, z)), 1e-9);
        assertEquals(z, a.worldZ(a.u(x, z), a.v(x, z)), 1e-9);
        var cam = new TravelMapMath.Camera();
        TravelMapMath.home(cam, 1024, 512); cam.snap();
        for (int i = 0; i < 5; i++) { TravelMapMath.zoomAt(cam, 400, 500, 1.25, 1024, 512); cam.snap(); }
        double sx = TravelMapMath.screenX(cam, a.u(x, z)), sy = TravelMapMath.screenY(cam, a.v(x, z));
        double u = TravelMapMath.mapU(cam, sx), v = TravelMapMath.mapV(cam, sy);
        assertEquals(x, a.worldX(u, v), 1e-6); assertEquals(z, a.worldZ(u, v), 1e-6);
    }

    @Test void zoomKeepsThePointUnderTheCursor() {
        var cam = new TravelMapMath.Camera();
        TravelMapMath.home(cam, 2000, 2000); cam.snap();
        double mx = 300, my = 400;
        double u = TravelMapMath.mapU(cam, mx), v = TravelMapMath.mapV(cam, my);
        TravelMapMath.zoomAt(cam, mx, my, 1.25, 2000, 2000); cam.snap();
        assertEquals(mx, TravelMapMath.screenX(cam, u), 1e-6); assertEquals(my, TravelMapMath.screenY(cam, v), 1e-6);
        // the animation settles on the target
        TravelMapMath.zoomAt(cam, mx, my, 1.25, 2000, 2000);
        for (int i = 0; i < 200; i++) cam.step(1 / 60.0);
        assertFalse(cam.moving());
    }

    @Test void focusZoomsInButNeverOut() {
        var cam = new TravelMapMath.Camera();
        TravelMapMath.home(cam, 1000, 1000); cam.snap();
        double far = cam.tzoom;
        TravelMapMath.focus(cam, 100, 900, far * 3, 1000, 1000);
        assertEquals(far * 3, cam.tzoom, 1e-9); assertEquals(100, cam.tu); assertEquals(900, cam.tv);
        TravelMapMath.focus(cam, 500, 500, far * 2, 1000, 1000);
        assertEquals(far * 3, cam.tzoom, 1e-9);
    }

    @Test void headingFollowsMinecraftYaw() {
        var north = TravelMapMath.Affine.world(-100, -100, 100, 100);
        assertEquals(Math.PI, Math.abs(north.heading(0)), 1e-9);          // yaw 0 = south = down
        assertEquals(0, north.heading(180), 1e-9);                         // north = up
        assertEquals(-Math.PI / 2, north.heading(90), 1e-9);               // west = left
    }

    @Test void longKoreanNamesAreCut() {
        TravelMapMath.Measure m = s -> s.codePointCount(0, s.length()) * 10f;
        assertEquals("항구 마을", TravelMapMath.ellipsize("항구 마을", 100, m));
        String cut = TravelMapMath.ellipsize("아주아주 긴 이름의 항구 마을 동쪽 선착장 앞 광장", 100, m);
        assertTrue(cut.endsWith("…")); assertTrue(m.width(cut) <= 100);
        assertEquals("…", TravelMapMath.ellipsize("가나다", 5, m));
    }
}
