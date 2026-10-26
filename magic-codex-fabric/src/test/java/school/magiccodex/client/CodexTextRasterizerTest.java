package school.magiccodex.client;

import java.awt.Font;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodexTextRasterizerTest {
    @Test
    void koreanTextHasSmoothCoverageAtActualSmallDisplaySizes() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/magiccodex/font/pretendard_regular.ttf")) {
            var font = Font.createFont(Font.TRUETYPE_FONT, stream);
            for (float size : new float[]{13, 15, 17, 24}) {
                var image = CodexTextRasterizer.rasterize(font, "마법의 기록 · 발견 조건", size);
                int partial = 0, strongCoverage = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    if (alpha > 0 && alpha < 255) partial++;
                    if (alpha >= 160) strongCoverage++;
                    if (x == 0 || y == 0 || x == image.getWidth() - 1 || y == image.getHeight() - 1)
                        assertEquals(0, alpha, "Text must not clip the texture edge");
                }
                assertTrue(partial > 30, "Need grayscale antialiasing at size " + size);
                // A subpixel-width regular stroke may never reach exactly 255 at 13px.
                assertTrue(strongCoverage > 0, "Need substantial stroke coverage");
                assertTrue(CodexTextRasterizer.width(font, "마법의 기록", size) > 0);
            }
        }
    }

    @Test
    void selectionFrameHasATransparentInteriorAndExterior() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/magiccodex/textures/gui/card_selected.png")) {
            var image = ImageIO.read(stream);
            assertEquals(1254, image.getWidth());
            assertEquals(1254, image.getHeight());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            for (int y = 220; y < 1050; y += 20) for (int x = 220; x < 1050; x += 20)
                assertEquals(0, image.getRGB(x, y) >>> 24, "Frame must not cover spell content");
            int left=1254, top=1254, right=0, bottom=0;
            for (int y=0; y<1254; y++) for (int x=0; x<1254; x++) if ((image.getRGB(x,y) >>> 24) >= 64) {
                left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x); bottom=Math.max(bottom,y);
            }
            System.out.printf("Selection alpha bounds: %d,%d,%d,%d%n", left,top,right-left+1,bottom-top+1);
            assertTrue(right > left && bottom > top);
        }
    }
}
