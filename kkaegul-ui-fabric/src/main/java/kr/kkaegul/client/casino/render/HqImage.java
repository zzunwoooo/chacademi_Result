package kr.kkaegul.client.casino.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 원본 PNG 픽셀 읽기 + 고품질 리샘플 (알파 보정, 축소 시 영역 평균에 가까운 삼각 필터).
 */
public final class HqImage {

    public final int w, h;
    public final int[] argb;

    private HqImage(int w, int h, int[] argb) {
        this.w = w;
        this.h = h;
        this.argb = argb;
    }

    private static final Map<String, SoftReference<HqImage>> SOURCES = new ConcurrentHashMap<>();

    /** 리소스 PNG 읽기 (실패하면 null) */
    public static HqImage load(Identifier id) {
        String k = id.toString();
        SoftReference<HqImage> ref = SOURCES.get(k);
        HqImage img = ref == null ? null : ref.get();
        if (img != null) return img;
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(id);
            if (res.isEmpty()) return null;
            BufferedImage bi;
            try (InputStream in = res.get().getInputStream()) {
                bi = ImageIO.read(in);
            }
            if (bi == null) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
            img = new HqImage(w, h, px);
            SOURCES.put(k, new SoftReference<>(img));
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 원본의 (sx, sy, sw, sh) 영역을 out(ow × oh)의 (dx, dy, dw, dh)에 리샘플해서 채운다.
     * 영역 밖 픽셀은 섞지 않는다 (9-slice 조각끼리 번지지 않게).
     */
    public void resampleInto(double sx, double sy, double sw, double sh, int[] out, int ow, int dx, int dy, int dw, int dh) {
        if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
        int x0 = clamp((int) Math.floor(sx + 1e-6), 0, w - 1);
        int x1 = clamp((int) Math.ceil(sx + sw - 1e-6) - 1, x0, w - 1);
        int y0 = clamp((int) Math.floor(sy + 1e-6), 0, h - 1);
        int y1 = clamp((int) Math.ceil(sy + sh - 1e-6) - 1, y0, h - 1);
        Weights wx = weights(sx, sw, dw, x0, x1);
        Weights wy = weights(sy, sh, dh, y0, y1);
        int rows = y1 - y0 + 1;
        // 가로 먼저 (알파 곱한 값으로)
        float[] tmp = new float[rows * dw * 4];
        for (int r = 0; r < rows; r++) {
            int srow = (y0 + r) * w;
            int trow = r * dw * 4;
            for (int ox = 0; ox < dw; ox++) {
                float a = 0, rr = 0, g = 0, b = 0;
                int st = wx.start[ox];
                float[] ws = wx.w[ox];
                for (int k = 0; k < ws.length; k++) {
                    int c = argb[srow + st + k];
                    float wt = ws[k];
                    float ca = ((c >>> 24) & 255) * wt;
                    a += ca;
                    rr += ((c >> 16) & 255) * ca;
                    g += ((c >> 8) & 255) * ca;
                    b += (c & 255) * ca;
                }
                int t = trow + ox * 4;
                tmp[t] = a;
                tmp[t + 1] = rr;
                tmp[t + 2] = g;
                tmp[t + 3] = b;
            }
        }
        // 세로
        for (int oy = 0; oy < dh; oy++) {
            int st = wy.start[oy] - y0;
            float[] ws = wy.w[oy];
            int orow = (dy + oy) * ow + dx;
            for (int ox = 0; ox < dw; ox++) {
                float a = 0, rr = 0, g = 0, b = 0;
                for (int k = 0; k < ws.length; k++) {
                    int t = ((st + k) * dw + ox) * 4;
                    float wt = ws[k];
                    a += tmp[t] * wt;
                    rr += tmp[t + 1] * wt;
                    g += tmp[t + 2] * wt;
                    b += tmp[t + 3] * wt;
                }
                int ia = Math.round(a);
                int c;
                if (ia <= 0) c = 0;
                else {
                    int ir = clamp(Math.round(rr / a), 0, 255);
                    int ig = clamp(Math.round(g / a), 0, 255);
                    int ib = clamp(Math.round(b / a), 0, 255);
                    c = (Math.min(255, ia) << 24) | (ir << 16) | (ig << 8) | ib;
                }
                out[orow + ox] = c;
            }
        }
    }

    private record Weights(int[] start, float[][] w) {
    }

    /** 출력 n칸 각각에 대해 원본 [lo, hi] 픽셀 가중치 */
    private static Weights weights(double s0, double len, int n, int lo, int hi) {
        int[] start = new int[n];
        float[][] w = new float[n][];
        double scale = len / n;
        double support = Math.max(1.0, scale);
        for (int i = 0; i < n; i++) {
            double center = s0 + (i + 0.5) * scale;
            int a = (int) Math.floor(center - support);
            int b = (int) Math.ceil(center + support);
            a = Math.max(a, lo);
            b = Math.min(b, hi);
            if (b < a) b = a;
            float[] ws = new float[b - a + 1];
            double sum = 0;
            for (int k = a; k <= b; k++) {
                double d = Math.abs(k + 0.5 - center) / support;
                double v = Math.max(0, 1 - d);
                ws[k - a] = (float) v;
                sum += v;
            }
            if (sum <= 0) {
                // 가장 가까운 픽셀
                int nearest = clamp((int) Math.floor(center), a, b);
                java.util.Arrays.fill(ws, 0);
                ws[nearest - a] = 1;
            } else {
                for (int k = 0; k < ws.length; k++) ws[k] /= (float) sum;
            }
            start[i] = a;
            w[i] = ws;
        }
        return new Weights(start, w);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
