package dev.portablevfx.client.claude;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeTextureCodecTest {
    /** Blocks and pixels cross-checked against the independent bcdec reference BC7 decoder (2026-10-14). */
    @Test void decoderMatchesReferenceVectors() {
        assertBlock("808d88518ca1148e11ffffffcbc93736", // mode 7, partition 0
                "141cc7ff3c1cb4ff661ca1ff8e1c8eff141cc7ff3c1cb4ff661ca1ff8e1c8eff1455c7ff3c55b4ff6655a1ff8e558eff1455c7ff3c55b4ff6655a1ff8e558eff");
        assertBlock("c03982c253e0ff7f00ff00ff00ff00ff", // mode 6
                "e62814fee62814fe1078f0fe1078f0fee62814fee62814fe1078f0fe1078f0fee62814fee62814fe1078f0fe1078f0fee62814fee62814fe1078f0fe1078f0fe");
        assertBlock("c0ff83eca1b8ff2d00f000fff0ffffff", // mode 6 with alpha
                "fec828fefec828fefec828fe1e3cdc5afec828fefec828fe1e3cdc5a1e3cdc5afec828fe1e3cdc5a1e3cdc5a1e3cdc5a1e3cdc5a1e3cdc5a1e3cdc5a1e3cdc5a");
        assertBlock("8000f642087c4e089cffffff010a141e", // mode 7, two-colour split
                "c7140cffc7140cff143ce7ff143ce7ffd74f0cffd74f0cff143ce7ff143ce7ffe78c0cffe78c0cff143ce7ff143ce7fff7c70cfff7c70cff143ce7ff143ce7ff");
    }
    private static void assertBlock(String block, String pixels) {
        assertEquals(pixels, HexFormat.of().formatHex(ClaudeTextureCodec.decode(HexFormat.of().parseHex(block), 4, 4)));
    }

    @Test void smoothGradientsAndOddSizesRoundTripClosely() {
        int w = 37, h = 19; byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            rgba[i] = (byte) (x * 6); rgba[i + 1] = (byte) (y * 12); rgba[i + 2] = (byte) (255 - x * 3);
            rgba[i + 3] = (byte) Math.min(255, (x + y) * 6);
        }
        byte[] blocks = ClaudeTextureCodec.encode(rgba, w, h);
        assertEquals(10 * 5 * 16, blocks.length);
        assertEquals(blocks.length, ClaudeTextureCodec.compressedBytes(w, h));
        // Three channels varying independently in 2D is the worst case for line-based BC7; still ~RMS 3/255.
        assertTrue(ClaudeTextureCodec.visiblePsnr(rgba, ClaudeTextureCodec.decode(blocks, w, h)) > 37);
        assertArrayEquals(blocks, ClaudeTextureCodec.encode(rgba, w, h), "Encoding is deterministic across threads");
        byte[] glow = glow(48, 40);
        assertTrue(ClaudeTextureCodec.visiblePsnr(glow, ClaudeTextureCodec.decode(ClaudeTextureCodec.encode(glow, 48, 40), 48, 40)) > 45);
        assertNotNull(ClaudeTextureCodec.compressIfFaithful(glow, 48, 40));
    }
    /** A typical particle sprite: tinted colour with a soft radial alpha falloff. */
    private static byte[] glow(int w, int h) {
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double d = Math.hypot((x - w / 2.0) / (w / 2.0), (y - h / 2.0) / (h / 2.0)), a = Math.max(0, 1 - d);
            int i = (y * w + x) * 4;
            rgba[i] = (byte) 255; rgba[i + 1] = (byte) (150 + 100 * a); rgba[i + 2] = (byte) (60 + 120 * a * a); rgba[i + 3] = (byte) (255 * a * a);
        }
        return rgba;
    }

    @Test void hardEdgedBlocksUseTwoSubsets() {
        byte[] rgba = new byte[64];
        for (int i = 0; i < 16; i++) {
            boolean left = (i & 3) < 2; int y = i >> 2;
            rgba[i * 4] = (byte) (left ? 200 + y * 15 : 20); rgba[i * 4 + 1] = (byte) (left ? 20 + y * 60 : 60);
            rgba[i * 4 + 2] = (byte) (left ? 10 : 230); rgba[i * 4 + 3] = (byte) 255;
        }
        byte[] block = ClaudeTextureCodec.encode(rgba, 4, 4);
        int mode = Integer.numberOfTrailingZeros(block[0] & 0xff);
        assertTrue(mode == 1 || mode == 7, "two-subset mode expected, got " + mode);
        assertTrue(ClaudeTextureCodec.visiblePsnr(rgba, ClaudeTextureCodec.decode(block, 4, 4)) > 38);
    }

    /** Regression (2026-10-16): empty texels decoded as alpha 1..12 and showed a faint quad outline under bloom. */
    @Test void emptyTexelsStayFullyTransparent() {
        int[][] edge = {{221,100,23,0},{221,100,23,0},{222,100,23,0},{222,101,23,1},{222,100,23,0},{222,101,23,1},{223,102,24,2},{224,104,24,4},
                {223,103,24,3},{224,105,25,5},{226,108,26,9},{229,114,28,14},{227,110,27,11},{231,117,29,17},{236,126,32,27},{244,138,37,40}};
        byte[] block = new byte[64];
        for (int i = 0; i < 16; i++) for (int c = 0; c < 4; c++) block[i * 4 + c] = (byte) edge[i][c];
        byte[] decoded = ClaudeTextureCodec.decode(ClaudeTextureCodec.encode(block, 4, 4), 4, 4);
        for (int i = 0; i < 16; i++) if (edge[i][3] == 0) assertEquals(0, decoded[i * 4 + 3] & 0xff, "texel " + i);
        // A coloured but fully transparent area (white RGB, alpha 0) must not become alpha 1.
        byte[] white = new byte[64 * 4];
        for (int i = 0; i < 64; i++) { white[i * 4] = (byte) 255; white[i * 4 + 1] = (byte) 255; white[i * 4 + 2] = (byte) 255; }
        byte[] whiteDecoded = ClaudeTextureCodec.decode(ClaudeTextureCodec.encode(white, 8, 8), 8, 8);
        for (int i = 3; i < whiteDecoded.length; i += 4) assertEquals(0, whiteDecoded[i] & 0xff);
        byte[] glow = glow(48, 40), glowDecoded = ClaudeTextureCodec.decode(ClaudeTextureCodec.encode(glow, 48, 40), 48, 40);
        for (int i = 3; i < glow.length; i += 4) if (glow[i] == 0) assertEquals(0, glowDecoded[i] & 0xff);
    }

    @Test void noiseStaysUncompressed() {
        int w = 64, h = 64; byte[] rgba = new byte[w * h * 4]; new Random(7).nextBytes(rgba);
        assertNull(ClaudeTextureCodec.compressIfFaithful(rgba, w, h));
    }

    @Test void diskCacheSkipsReencodingAndTinyTexturesStayRgba(@TempDir Path cache) throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        byte[] glow = glow(64, 32);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) {
            int i = ((31 - y) * 64 + x) * 4; // decoder rows are bottom-up
            image.setRGB(x, y, (glow[i + 3] & 0xff) << 24 | (glow[i] & 0xff) << 16 | (glow[i + 1] & 0xff) << 8 | (glow[i + 2] & 0xff));
        }
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); byte[] png = out.toByteArray();
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        ClaudeTexture.cacheDirectory(cache);
        try {
            ClaudeTexture.resetSummary();
            var first = ClaudeTexture.prepare(png, digest, 64L << 20);
            assertEquals(ClaudeTexture.BC7, first.format());
            assertEquals(64 * 32, first.data().length);
            assertTrue(Files.exists(cache.resolve(digest + ".v" + ClaudeTextureCodec.ENCODER_VERSION + ".pvtx")));
            var second = ClaudeTexture.prepare(png, digest, 64L << 20);
            assertArrayEquals(first.data(), second.data());
            assertTrue(ClaudeTexture.summary().contains("diskHits=1"), ClaudeTexture.summary());
            Files.write(cache.resolve(digest + ".v" + ClaudeTextureCodec.ENCODER_VERSION + ".pvtx"), new byte[]{1, 2, 3}); // corrupt entry: ignored, re-encoded
            assertArrayEquals(first.data(), ClaudeTexture.prepare(png, digest, 64L << 20).data());

            BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            var tinyOut = new ByteArrayOutputStream(); ImageIO.write(tiny, "png", tinyOut);
            byte[] tinyPng = tinyOut.toByteArray();
            String tinyDigest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tinyPng));
            assertEquals(ClaudeTexture.RGBA8, ClaudeTexture.prepare(tinyPng, tinyDigest, 64L << 20).format());
        } finally { ClaudeTexture.cacheDirectory(null); }
    }
}
