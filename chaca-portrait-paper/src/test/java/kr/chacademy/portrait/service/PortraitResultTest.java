package kr.chacademy.portrait.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PortraitResultTest {

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void validatesTransparencyAndSize() throws Exception {
        BufferedImage clear = new BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB);
        clear.setRGB(32, 48, 0xFFFFFFFF);
        assertNull(PortraitService.validateResult(png(clear), true));
        BufferedImage opaque = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
        assertNotNull(PortraitService.validateResult(png(opaque), true));
        assertNull(PortraitService.validateResult(png(opaque), false));
        BufferedImage huge = new BufferedImage(2100, 10, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(PortraitService.validateResult(png(huge), false));
        assertNotNull(PortraitService.validateResult("not a png at all, definitely not".repeat(4).getBytes(), false));
        assertEquals(64, PortraitService.sha256(new byte[]{1}).length());
    }

    private static BufferedImage canvas() {
        return new BufferedImage(64, 96, BufferedImage.TYPE_INT_ARGB);
    }

    private static void fill(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                img.setRGB(x, y, argb);
            }
        }
    }

    @Test
    void bustTouchingBottomEdgeAndLowerSidesIsAccepted() throws Exception {
        // 상반신: 허리 아래가 잘려 아래쪽 가장자리·아래 귀퉁이·옆 가장자리 아래쪽에 몸이 닿는다 → 정상
        BufferedImage bust = canvas();
        fill(bust, 0, 48, 64, 96, 0xFF336699);
        fill(bust, 20, 8, 44, 48, 0xFF336699);
        assertNull(PortraitService.validateResult(png(bust), true));
        assertTrue(PortraitService.transparentFrame(bust));
        // 머리카락이 윗줄에 조금(10% 이하) 닿거나, 옆 가장자리 위쪽 절반에 조금(40% 이하) 닿는 것도 허용
        BufferedImage hair = canvas();
        fill(hair, 29, 0, 35, 40, 0xFF000000);  // 윗줄 6/64
        fill(hair, 0, 30, 1, 48, 0xFF000000);   // 왼쪽 위 절반 18/48
        fill(hair, 63, 30, 64, 48, 0xFF000000); // 오른쪽 위 절반 18/48
        assertTrue(PortraitService.transparentFrame(hair));
        // 거의 투명(알파 16 이하)은 투명으로 본다
        BufferedImage faint = canvas();
        fill(faint, 0, 0, 64, 1, 0x10FFFFFF);
        assertTrue(PortraitService.transparentFrame(faint));
    }

    @Test
    void opaqueBackgroundIsRejected() throws Exception {
        BufferedImage full = canvas();
        fill(full, 0, 0, 64, 96, 0xFFCCCCCC);
        assertNotNull(PortraitService.validateResult(png(full), true));
        BufferedImage corner = canvas();
        corner.setRGB(0, 0, 0xFF000000);
        assertFalse(PortraitService.transparentFrame(corner));
        BufferedImage otherCorner = canvas();
        otherCorner.setRGB(63, 0, 0x11000000); // 알파 17
        assertFalse(PortraitService.transparentFrame(otherCorner));
        BufferedImage topRow = canvas();
        fill(topRow, 10, 0, 30, 1, 0xFF000000); // 윗줄 20/64 불투명 → 90% 미만
        assertFalse(PortraitService.transparentFrame(topRow));
        BufferedImage side = canvas();
        fill(side, 0, 20, 1, 48, 0xFF000000); // 왼쪽 위 절반 28/48 불투명 → 60% 미만
        assertFalse(PortraitService.transparentFrame(side));
        BufferedImage right = canvas();
        fill(right, 63, 20, 64, 48, 0xFF000000);
        assertFalse(PortraitService.transparentFrame(right));
        assertNotNull(PortraitService.validateResult(png(right), true));
        assertNull(PortraitService.validateResult(png(right), false));
    }

    @Test
    void queueServesAdminThenRerollThenAutoInArrivalOrder() {
        var queue = new java.util.concurrent.PriorityBlockingQueue<Runnable>();
        java.util.UUID id = java.util.UUID.randomUUID();
        queue.add(new PortraitService.QueuedJob(PortraitService.Kind.AUTO, 1, id, () -> { }));
        queue.add(new PortraitService.QueuedJob(PortraitService.Kind.REROLL, 2, id, () -> { }));
        queue.add(new PortraitService.QueuedJob(PortraitService.Kind.AUTO, 3, id, () -> { }));
        queue.add(new PortraitService.QueuedJob(PortraitService.Kind.ADMIN, 4, id, () -> { }));
        queue.add(new PortraitService.QueuedJob(PortraitService.Kind.REROLL, 5, id, () -> { }));
        long[] order = new long[5];
        for (int i = 0; i < 5; i++) {
            order[i] = ((PortraitService.QueuedJob) queue.poll()).order.seq();
        }
        assertArrayEquals(new long[]{4, 2, 5, 1, 3}, order);
    }
}
