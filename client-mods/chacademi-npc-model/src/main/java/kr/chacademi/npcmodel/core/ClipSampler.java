package kr.chacademi.npcmodel.core;

/** 클립 하나를 특정 시각에 샘플링해 Pose 에 덮어쓴다. 클립에 없는 노드는 그대로 둔다(보조 뼈 포함 유지). */
public final class ClipSampler {
    private ClipSampler() {
    }

    /** loop 이면 시간을 길이로 감고, 아니면 [0, 길이]로 자른다. */
    public static float wrap(ModelData.Clip clip, float time, boolean loop) {
        float d = clip.duration;
        if (d <= 1e-6f) {
            return 0;
        }
        if (loop) {
            float x = time % d;
            return x < 0 ? x + d : x;
        }
        return Math.max(0, Math.min(d, time));
    }

    public static void sample(ModelData.Clip clip, float time, Pose out) {
        for (ModelData.Channel ch : clip.channels) {
            if (ch.node >= out.size) {
                continue;
            }
            float[] dst;
            int comps, off;
            switch (ch.path) {
                case ModelData.PATH_T -> { dst = out.t; comps = 3; off = ch.node * 3; }
                case ModelData.PATH_R -> { dst = out.r; comps = 4; off = ch.node * 4; }
                default -> { dst = out.s; comps = 3; off = ch.node * 3; }
            }
            sampleChannel(ch, comps, time, dst, off);
        }
    }

    static void sampleChannel(ModelData.Channel ch, int comps, float time, float[] dst, int off) {
        float[] ts = ch.times;
        int n = ts.length;
        boolean cubic = ch.interpolation == ModelData.INTERP_CUBIC;
        int stride = cubic ? comps * 3 : comps;
        int valueOff = cubic ? comps : 0; // CUBIC: [in, value, out]
        if (n == 1 || time <= ts[0]) {
            System.arraycopy(ch.values, valueOff, dst, off, comps);
            return;
        }
        if (time >= ts[n - 1]) {
            System.arraycopy(ch.values, (n - 1) * stride + valueOff, dst, off, comps);
            return;
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float span = ts[hi] - ts[lo];
        float f = span <= 0 ? 0 : (time - ts[lo]) / span;
        if (ch.interpolation == ModelData.INTERP_STEP) {
            System.arraycopy(ch.values, lo * stride, dst, off, comps);
            return;
        }
        if (cubic) {
            float f2 = f * f, f3 = f2 * f;
            float h00 = 2 * f3 - 3 * f2 + 1, h10 = f3 - 2 * f2 + f, h01 = -2 * f3 + 3 * f2, h11 = f3 - f2;
            int a = lo * stride, b = hi * stride;
            for (int c = 0; c < comps; c++) {
                float p0 = ch.values[a + comps + c], m0 = ch.values[a + 2 * comps + c] * span;
                float p1 = ch.values[b + comps + c], m1 = ch.values[b + c] * span;
                dst[off + c] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
            }
            if (comps == 4) {
                MathUtil.normalizeQuat(dst, off);
            }
            return;
        }
        if (comps == 4) {
            MathUtil.slerp(ch.values, lo * 4, ch.values, hi * 4, f, dst, off);
        } else {
            for (int c = 0; c < comps; c++) {
                float a = ch.values[lo * comps + c];
                dst[off + c] = a + (ch.values[hi * comps + c] - a) * f;
            }
        }
    }
}
