package kr.kkaegul.client.ui;

/** 애니메이션 보조 */
public final class Anim {
    private Anim() {}

    public static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** start 부터 dur ms 동안 0→1 */
    public static float t(long now, long start, long dur) { return dur <= 0 ? 1 : clamp((now - start) / (float) dur); }

    public static float outCubic(float t) { t = clamp(t); float u = 1 - t; return 1 - u * u * u; }

    public static float inCubic(float t) { t = clamp(t); return t * t * t; }

    public static float inOut(float t) { t = clamp(t); return t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }

    /** 살짝 튀어나왔다 돌아오는 */
    public static float outBack(float t) {
        t = clamp(t);
        float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** 통통 튀는 착지 */
    public static float outBounce(float t) {
        t = clamp(t);
        float n = 7.5625f, d = 2.75f;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) { t -= 1.5f / d; return n * t * t + .75f; }
        if (t < 2.5 / d) { t -= 2.25f / d; return n * t * t + .9375f; }
        t -= 2.625f / d;
        return n * t * t + .984375f;
    }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** 시작/끝 페이드 알파 (0~1) */
    public static float fade(long now, long start, long life, long in, long out) {
        long e = now - start;
        if (e < 0) return 0;
        if (e < in) return e / (float) in;
        if (e > life - out) return clamp((life - e) / (float) out);
        return 1;
    }

    public static int alpha(int argb, float a) {
        int base = argb >>> 24;
        return ((int) (base * clamp(a)) << 24) | (argb & 0xFFFFFF);
    }

    public static int white(float a) { return alpha(0xFFFFFFFF, a); }

    /** 좌우 흔들림 */
    public static float shake(long now, float amp, float hz) { return (float) Math.sin(now / 1000.0 * Math.PI * 2 * hz) * amp; }

    public static boolean in(float mx, float my, float x, float y, float w, float h) { return mx >= x && my >= y && mx < x + w && my < y + h; }
}
