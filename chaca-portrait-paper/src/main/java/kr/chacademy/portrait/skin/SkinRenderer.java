package kr.chacademy.portrait.skin;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 마인크래프트 스킨(64x64 / 구형 64x32)을 앞·뒤 전신 그림으로 펼친다. AI 호출 없이 서버에서 처리.
 * 원본 스킨 전개도는 모델이 알아보기 어렵기 때문에, 사람이 보는 정면/후면 모습으로 바꿔 크게 확대한다.
 * 기본층은 불투명, 겉옷층(모자·재킷·소매·바지)은 위에 겹친다.
 */
public final class SkinRenderer {

    public static final int OUT_W = 1024;
    public static final int OUT_H = 768;
    private static final int SCALE = 20;
    private static final Color BACKGROUND = new Color(0xE4, 0xE6, 0xEA);

    private SkinRenderer() {
    }

    /** @param slim 알렉스(가는 팔) 모델 여부 */
    public static byte[] renderPng(byte[] skinPng, boolean slim) throws IOException {
        BufferedImage skin = read(skinPng);
        BufferedImage out = render(skin, slim);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    public static BufferedImage read(byte[] png) throws IOException {
        if (png == null || png.length == 0 || png.length > 512 * 1024) {
            throw new IOException("skin size");
        }
        BufferedImage raw;
        // 디코드 전에 크기부터 확인 (큰 크기를 선언한 PNG로 메모리를 쓰게 하지 않음)
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("not an image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w != 64 || (h != 64 && h != 32)) {
                    throw new IOException("unsupported skin size " + w + "x" + h);
                }
                raw = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        BufferedImage skin = new BufferedImage(64, raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = skin.createGraphics();
        g.drawImage(raw, 0, 0, null);
        g.dispose();
        return skin;
    }

    public static BufferedImage render(BufferedImage skin, boolean slim) {
        boolean legacy = skin.getHeight() == 32;
        int arm = slim ? 3 : 4;
        // 한 면 = 16 x 32 스킨 픽셀
        BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage back = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        boolean hat = !legacy || hasTransparency(skin, 32, 0, 32, 16);

        // ---- 정면 (보는 사람 왼쪽 = 캐릭터 오른팔) ----
        base(skin, front, 8, 8, 8, 8, 4, 0, false);                       // 머리
        base(skin, front, 20, 20, 8, 12, 4, 8, false);                    // 몸
        base(skin, front, 44, 20, arm, 12, 4 - arm, 8, false);            // 오른팔
        base(skin, front, 4, 20, 4, 12, 4, 20, false);                    // 오른다리
        if (legacy) {
            base(skin, front, 44, 20, arm, 12, 12, 8, true);              // 왼팔 = 오른팔 좌우반전
            base(skin, front, 4, 20, 4, 12, 8, 20, true);                 // 왼다리
        } else {
            base(skin, front, 36, 52, arm, 12, 12, 8, false);
            base(skin, front, 20, 52, 4, 12, 8, 20, false);
        }
        if (hat) {
            overlay(skin, front, 40, 8, 8, 8, 4, 0, false);
        }
        if (!legacy) {
            overlay(skin, front, 20, 36, 8, 12, 4, 8, false);
            overlay(skin, front, 44, 36, arm, 12, 4 - arm, 8, false);
            overlay(skin, front, 52, 52, arm, 12, 12, 8, false);
            overlay(skin, front, 4, 36, 4, 12, 4, 20, false);
            overlay(skin, front, 4, 52, 4, 12, 8, 20, false);
        }

        // ---- 후면 (보는 사람 왼쪽 = 캐릭터 왼팔) ----
        int armBackRight = slim ? 51 : 52;
        int armBackLeft = slim ? 43 : 44;
        base(skin, back, 24, 8, 8, 8, 4, 0, false);
        base(skin, back, 32, 20, 8, 12, 4, 8, false);
        base(skin, back, armBackRight, 20, arm, 12, 12, 8, false);       // 오른팔(뒤) → 오른쪽
        base(skin, back, 12, 20, 4, 12, 8, 20, false);                   // 오른다리(뒤)
        if (legacy) {
            base(skin, back, armBackRight, 20, arm, 12, 4 - arm, 8, true);
            base(skin, back, 12, 20, 4, 12, 4, 20, true);
        } else {
            base(skin, back, armBackLeft, 52, arm, 12, 4 - arm, 8, false);
            base(skin, back, 28, 52, 4, 12, 4, 20, false);
        }
        if (hat) {
            overlay(skin, back, 56, 8, 8, 8, 4, 0, false);
        }
        if (!legacy) {
            overlay(skin, back, 32, 36, 8, 12, 4, 8, false);
            overlay(skin, back, armBackRight, 36, arm, 12, 12, 8, false);
            overlay(skin, back, slim ? 59 : 60, 52, arm, 12, 4 - arm, 8, false);
            overlay(skin, back, 12, 36, 4, 12, 8, 20, false);
            overlay(skin, back, 12, 52, 4, 12, 4, 20, false);
        }

        BufferedImage out = new BufferedImage(OUT_W, OUT_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, OUT_W, OUT_H);
        int w = 16 * SCALE, h = 32 * SCALE, gap = 6 * SCALE;
        int x0 = (OUT_W - (2 * w + gap)) / 2, y0 = (OUT_H - h) / 2;
        g.drawImage(front, x0, y0, w, h, null);
        g.drawImage(back, x0 + w + gap, y0, w, h, null);
        g.dispose();
        return out;
    }

    private static void base(BufferedImage skin, BufferedImage dst, int sx, int sy, int w, int h, int dx, int dy, boolean mirror) {
        copy(skin, dst, sx, sy, w, h, dx, dy, mirror, true);
    }

    private static void overlay(BufferedImage skin, BufferedImage dst, int sx, int sy, int w, int h, int dx, int dy, boolean mirror) {
        copy(skin, dst, sx, sy, w, h, dx, dy, mirror, false);
    }

    private static void copy(BufferedImage skin, BufferedImage dst, int sx, int sy, int w, int h, int dx, int dy,
                             boolean mirror, boolean opaque) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int srcX = sx + (mirror ? w - 1 - x : x), srcY = sy + y;
                if (srcX < 0 || srcY < 0 || srcX >= skin.getWidth() || srcY >= skin.getHeight()) {
                    continue;
                }
                int tx = dx + x, ty = dy + y;
                if (tx < 0 || ty < 0 || tx >= dst.getWidth() || ty >= dst.getHeight()) {
                    continue;
                }
                int argb = skin.getRGB(srcX, srcY);
                int a = argb >>> 24;
                if (opaque) {
                    dst.setRGB(tx, ty, argb | 0xFF000000);
                } else if (a >= 128) {
                    dst.setRGB(tx, ty, argb | 0xFF000000);
                }
            }
        }
    }

    private static boolean hasTransparency(BufferedImage skin, int x, int y, int w, int h) {
        for (int yy = y; yy < y + h && yy < skin.getHeight(); yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if ((skin.getRGB(xx, yy) >>> 24) < 128) {
                    return true;
                }
            }
        }
        return false;
    }
}
