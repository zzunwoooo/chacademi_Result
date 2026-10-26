package school.magiccodex.client;

/** High-quality icon shrink for {@link ExactItemIcons} (worker thread, no Minecraft types). */
final class IconResampler {
    private IconResampler() {}


    /**
     * Separable Lanczos-3 resize of straight-alpha ARGB to premultiplied ARGB (w x h). Works on premultiplied colour
     * so transparent texels never darken or tint the edge.
     */
    static int[] resample(int[] argb, int sw, int sh, int dw, int dh) {
        float[] src = new float[sw * sh * 4];
        for (int i = 0; i < sw * sh; i++) {
            int c = argb[i];
            float a = (c >>> 24) / 255f;
            src[i * 4] = a;
            src[i * 4 + 1] = ((c >>> 16) & 255) / 255f * a;
            src[i * 4 + 2] = ((c >>> 8) & 255) / 255f * a;
            src[i * 4 + 3] = (c & 255) / 255f * a;
        }
        float[] mid = pass(src, sw, sh, dw, true);      // dw x sh
        float[] out = pass(mid, dw, sh, dh, false);     // dw x dh
        int[] result = new int[dw * dh];
        for (int i = 0; i < dw * dh; i++) {
            float a = clamp01(out[i * 4]);
            int ai = Math.round(a * 255);
            int r = Math.round(Math.min(clamp01(out[i * 4 + 1]), a) * 255);
            int g = Math.round(Math.min(clamp01(out[i * 4 + 2]), a) * 255);
            int b = Math.round(Math.min(clamp01(out[i * 4 + 3]), a) * 255);
            result[i] = ai < 1 ? 0 : ai << 24 | r << 16 | g << 8 | b;
        }
        return result;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static float lanczos(double x) {
        x = Math.abs(x);
        if (x < 1e-6) return 1;
        if (x >= 3) return 0;
        double px = Math.PI * x;
        return (float) (3 * Math.sin(px) * Math.sin(px / 3) / (px * px));
    }

    /** One direction. horizontal: (sw x sh) -> (dst x sh); vertical: (sw x sh) -> (sw x dst). */
    private static float[] pass(float[] src, int sw, int sh, int dst, boolean horizontal) {
        int srcLen = horizontal ? sw : sh, other = horizontal ? sh : sw;
        float[] out = new float[(horizontal ? dst * sh : sw * dst) * 4];
        double scale = (double) srcLen / dst, support = 3 * Math.max(1, scale), filterScale = Math.max(1, scale);
        for (int o = 0; o < dst; o++) {
            double center = (o + 0.5) * scale - 0.5;
            int start = (int) Math.floor(center - support), end = (int) Math.ceil(center + support);
            int n = end - start + 1;
            float[] weights = new float[n];
            float sum = 0;
            for (int k = 0; k < n; k++) { weights[k] = lanczos((start + k - center) / filterScale); sum += weights[k]; }
            for (int line = 0; line < other; line++) {
                float a = 0, r = 0, g = 0, b = 0;
                for (int k = 0; k < n; k++) {
                    int s = Math.clamp(start + k, 0, srcLen - 1);
                    int idx = (horizontal ? line * sw + s : s * sw + line) * 4;
                    float w = weights[k];
                    a += src[idx] * w; r += src[idx + 1] * w; g += src[idx + 2] * w; b += src[idx + 3] * w;
                }
                int to = (horizontal ? line * dst + o : o * sw + line) * 4;
                out[to] = a / sum; out[to + 1] = r / sum; out[to + 2] = g / sum; out[to + 3] = b / sum;
            }
        }
        return out;
    }
}
