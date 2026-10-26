package kr.chacademy.cutscene.client;

/**
 * 잉크 번짐 전환용 "언제 칠해지는지" 지도.
 * 값이 작은 칸부터 먼저 새 그림이 드러난다. 줌 중심에서 퍼져 나가고, 노이즈로 가장자리가 번진다.
 *
 * <p>편집기(editor/cutscene-editor.html)의 inkMask() 와 같은 계산이라 미리보기와 게임이 똑같이 보인다.
 * 한쪽을 고치면 다른 쪽도 같이 고칠 것.
 */
public final class InkMask {
    public static final int MASK_W = 160;
    public static final double SOFT = 0.12;

    private InkMask() {}

    static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return h;
    }

    static double h01(int x, int y, int seed) {
        return (hash(x, y, seed) & 0xFFFFFF) / 16777215.0;
    }

    static double valueNoise(double x, double y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = h01(ix, iy, seed), b = h01(ix + 1, iy, seed);
        double c = h01(ix, iy + 1, seed), d = h01(ix + 1, iy + 1, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    static double fbm(double x, double y, int seed) {
        double sum = 0, amp = 0.5, freq = 3;
        for (int o = 0; o < 4; o++) {
            sum += valueNoise(x * freq, y * freq, seed + o * 17) * amp;
            amp *= 0.5;
            freq *= 2;
        }
        return sum / 0.9375;
    }

    /** 작은 해상도(MASK_W × maskH) 지도. 0~1로 정규화됨. */
    public static float[] build(int imgW, int imgH, double cx, double cy, int seed) {
        int mw = MASK_W;
        int mh = Math.max(1, (int) Math.round(MASK_W * (double) imgH / imgW));
        double aspect = (double) imgW / imgH;
        double maxD = 0;
        double[][] corners = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (double[] c : corners) maxD = Math.max(maxD, Math.hypot((c[0] - cx) * aspect, c[1] - cy));

        float[] t = new float[mw * mh];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int my = 0; my < mh; my++) {
            for (int mx = 0; mx < mw; mx++) {
                double u = (mx + 0.5) / mw, v = (my + 0.5) / mh;
                double d = Math.hypot((u - cx) * aspect, v - cy) / maxD;
                double val = d * 0.7 + fbm(u * aspect, v, seed) * 0.45;
                t[my * mw + mx] = (float) val;
                min = Math.min(min, val);
                max = Math.max(max, val);
            }
        }
        double range = Math.max(1e-6, max - min);
        for (int i = 0; i < t.length; i++) t[i] = (float) ((t[i] - min) / range);
        return t;
    }

    /** 작은 지도를 이미지 해상도로 부드럽게 늘린다. */
    public static float[] upscale(float[] mask, int imgW, int imgH) {
        int mw = MASK_W;
        int mh = mask.length / mw;
        float[] out = new float[imgW * imgH];
        for (int y = 0; y < imgH; y++) {
            double sy = (y + 0.5) / imgH * mh - 0.5;
            int y0 = Math.max(0, Math.min(mh - 1, (int) Math.floor(sy)));
            int y1 = Math.min(mh - 1, y0 + 1);
            double fy = Math.max(0, Math.min(1, sy - y0));
            for (int x = 0; x < imgW; x++) {
                double sx = (x + 0.5) / imgW * mw - 0.5;
                int x0 = Math.max(0, Math.min(mw - 1, (int) Math.floor(sx)));
                int x1 = Math.min(mw - 1, x0 + 1);
                double fx = Math.max(0, Math.min(1, sx - x0));
                double a = mask[y0 * mw + x0], b = mask[y0 * mw + x1];
                double c = mask[y1 * mw + x0], d = mask[y1 * mw + x1];
                double top = a + (b - a) * fx, bot = c + (d - c) * fx;
                out[y * imgW + x] = (float) (top + (bot - top) * fy);
            }
        }
        return out;
    }

    /** 진행도 p(0~1)에서 지도 값 t인 칸의 불투명도. */
    public static double alpha(double p, double t) {
        double a = (p * (1 + SOFT) - t) / SOFT;
        a = Math.max(0, Math.min(1, a));
        return a * a * (3 - 2 * a);
    }
}
