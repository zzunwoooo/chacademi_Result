package kr.chacademy.portrait;

import kr.chacademy.portrait.core.CostModel;
import kr.chacademy.portrait.core.PromptBuilder;
import kr.chacademy.portrait.skin.SkinRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortraitCoreTest {

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void rendersModernAndLegacySkins() throws Exception {
        BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // 얼굴 정면을 빨강으로, 몸 정면을 파랑으로
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) modern.setRGB(x, y, 0xFFFF0000);
        for (int y = 20; y < 32; y++) for (int x = 20; x < 28; x++) modern.setRGB(x, y, 0xFF0000FF);
        BufferedImage out = SkinRenderer.render(modern, false);
        assertEquals(SkinRenderer.OUT_W, out.getWidth());
        assertEquals(SkinRenderer.OUT_H, out.getHeight());
        // 정면 그림의 얼굴 중앙: x0 + 8*20, y0 + 4*20
        int w = 16 * 20, gap = 6 * 20, x0 = (SkinRenderer.OUT_W - (2 * w + gap)) / 2, y0 = (SkinRenderer.OUT_H - 32 * 20) / 2;
        assertEquals(0xFFFF0000, out.getRGB(x0 + 8 * 20, y0 + 4 * 20));
        assertEquals(0xFF0000FF, out.getRGB(x0 + 8 * 20, y0 + 14 * 20));
        // 바깥 배경은 회색
        assertEquals(0xFFE4E6EA, out.getRGB(2, 2));
        byte[] encoded = SkinRenderer.renderPng(png(modern), true);
        assertTrue(encoded.length > 100);

        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 20; y < 32; y++) for (int x = 44; x < 48; x++) legacy.setRGB(x, y, 0xFF00FF00); // 오른팔 정면
        BufferedImage lo = SkinRenderer.render(legacy, false);
        // 왼팔(보는 사람 오른쪽, x 12..16)도 초록 (오른팔 반전)
        assertEquals(0xFF00FF00, lo.getRGB(x0 + 13 * 20, y0 + 12 * 20));
        assertEquals(0xFF00FF00, lo.getRGB(x0 + 1 * 20, y0 + 12 * 20));
    }

    @Test
    void rejectsBadSkins() {
        assertThrows(java.io.IOException.class, () -> SkinRenderer.renderPng(new byte[]{1, 2, 3}, false));
        assertThrows(java.io.IOException.class, () -> SkinRenderer.renderPng(png(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)), false));
    }

    @Test
    void promptKeepsRequestInItsSlot() {
        String p = PromptBuilder.build("BASE", "A: {appearance}", "R: {request}", "silver hair", "밤하늘\n배경 \"무시해\"§c");
        assertTrue(p.startsWith("BASE"));
        assertTrue(p.contains("A: silver hair"));
        assertTrue(p.contains("R: \"밤하늘 배경 '무시해'c\""));
        assertFalse(p.contains("\n배경"));
        String none = PromptBuilder.build("BASE", "A: {appearance}", "R: {request}", "", "  ");
        assertTrue(none.startsWith("BASE")); assertFalse(none.contains("A: ")); assertFalse(none.contains("R: ")); assertTrue(none.contains("NON-NEGOTIABLE CHARACTER RULES"));
    }

    @Test
    void costReserveAndSettle() {
        var prices = Map.of("gpt-image-2", new CostModel.Prices(5, 8, 30, 0), "gpt-image-1.5", new CostModel.Prices(5, 8, 32, 0));
        var est = new CostModel.Estimate(600, 2400, 9000, 9000, 1500, 3000);
        CostModel c = new CostModel(prices, est);
        // 1500*5 + 9000*8 + 2400*30 = 7500 + 72000 + 72000 = 151500 micro = $0.1515
        assertEquals(151_500, c.reserveImage("gpt-image-2", "medium"));
        assertEquals(156_300, c.reserveImage("gpt-image-1.5", "medium"));
        assertTrue(c.hasPrices("gpt-image-2-2026-04-21"));
        var usage = new CostModel.ImageUsage(200, 4000, 1600);
        // 200*5 + 4000*8 + 1600*30 = 1000+32000+48000 = 81000
        assertEquals(81_000, c.settleImage("gpt-image-2", usage, 151_500));
        assertEquals(151_500, c.settleImage("gpt-image-2", new CostModel.ImageUsage(-1, -1, -1), 151_500));
        assertEquals("$0.0810", CostModel.usd(81_000));
    }
}
