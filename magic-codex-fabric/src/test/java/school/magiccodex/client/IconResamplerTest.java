package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IconResamplerTest {
    @Test void solidOpaqueStaysExactAndClearStaysClear() {
        int[] src = new int[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) src[y * 256 + x] = x < 128 ? 0xFFC04020 : 0x00FFFFFF;
        int[] out = IconResampler.resample(src, 256, 256, 72, 72);
        assertEquals(72 * 72, out.length);
        assertEquals(0xFFC04020, out[36 * 72 + 10]);           // inside the opaque half
        assertEquals(0, out[36 * 72 + 62]);                     // inside the clear half
        int edge = out[36 * 72 + 36];                           // boundary: partial, never brighter than its alpha
        int a = edge >>> 24, r = (edge >>> 16) & 255;
        assertTrue(a > 0 && a < 255);
        assertTrue(r <= a);
    }

    @Test void nonSquareSizesWork() {
        int[] src = new int[200 * 100];
        java.util.Arrays.fill(src, 0x80FFFFFF);
        int[] out = IconResampler.resample(src, 200, 100, 73, 37);
        assertEquals(73 * 37, out.length);
        assertEquals(0x80, out[20 * 73 + 30] >>> 24);
    }
}
