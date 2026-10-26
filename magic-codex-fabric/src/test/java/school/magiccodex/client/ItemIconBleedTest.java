package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemIconBleedTest {
    @Test void clearTexelsTakeNearestVisibleColourAndStayClear() {
        int red = 0xFFFF0000, blue = 0x80_0000FF;
        int[] src = {0, red, 0, 0, 0, blue};
        int[] out = ItemIconTextures.bleed(src, 3, 2);
        for (int i = 0; i < src.length; i++) assertEquals(src[i] >>> 24, out[i] >>> 24, "alpha unchanged at " + i);
        assertEquals(0xFF0000, out[0] & 0xFFFFFF);
        assertEquals(0xFF0000, out[2] & 0xFFFFFF);
        assertEquals(0xFF0000, out[4] & 0xFFFFFF);
        assertEquals(red, out[1]);
        assertEquals(blue, out[5]);
    }
    @Test void fullyClearImageIsUnchanged() {
        int[] src = new int[4];
        assertArrayEquals(src, ItemIconTextures.bleed(src, 2, 2));
    }
}
