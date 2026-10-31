package kr.chacademy.halloweensky;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

/**
 * 할로윈 하늘 진행 상태.
 * 쓸림 앞줄 f: 서쪽 끝(t=0)에서 동쪽 끝(t=1)으로 움직인다. 방향 d 의 t = (d.x + 1) / 2, t < f 인 곳이 할로윈 하늘.
 * 켜면 f 가 -EDGE → 1+EDGE 로, 끄면 거꾸로 돌아간다. 셰이더도 같은 값(chacaSweep)·같은 식을 쓴다.
 */
public final class SkyState {
    public static final float EDGE = 0.06f;
    static final float SWEEP_SECONDS = 7f;
    private static final float START = -EDGE - 0.02f, END = 1f + EDGE + 0.02f;

    private static boolean target;
    private static float front = START;
    private static long lastMillis = -1;

    private SkyState() {}

    public static void turnOn(boolean instant) {
        target = true;
        if (instant) front = END;
    }

    public static void turnOff() { target = false; }

    public static void reset() {
        target = false;
        front = START;
    }

    /** 매 프레임 한 번 (하늘 그릴 때 / 셰이더 값 읽을 때) 시간만큼 앞줄을 움직인다. */
    private static void advance() {
        long now = Util.getMillis();
        float dt = lastMillis < 0 ? 0f : Math.min(0.25f, (now - lastMillis) / 1000f);
        lastMillis = now;
        float step = dt * (END - START) / SWEEP_SECONDS;
        front = target ? Math.min(END, front + step) : Math.max(START, front - step);
    }

    /** 앞줄을 부드럽게(가속·감속) 보정한 값. 꺼져 있으면 START. */
    public static float sweep() {
        advance();
        if (!skyAllowed()) return START;
        float x = (front - START) / (END - START);
        x = x * x * (3f - 2f * x);
        return START + x * (END - START);
    }

    /** 화면 전체 분위기(조명·안개·구름) 정도 0..1. */
    public static float amount() {
        float f = sweep();
        float x = Math.max(0f, Math.min(1f, f));
        return x * x * (3f - 2f * x);
    }

    /** 앞줄 기준 방향 t 위치의 할로윈 비율 0..1. */
    public static float mask(float t, float f) {
        return 1f - smooth(f - EDGE, f + EDGE, t);
    }

    public static float glow(float t, float f) {
        float u = (t - f) / EDGE;
        return (float) Math.exp(-u * u);
    }

    static float smooth(float a, float b, float x) {
        float k = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return k * k * (3f - 2f * k);
    }

    /** 보통 하늘(오버월드형)인 세계에서만. */
    private static boolean skyAllowed() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.effects().skyType() == DimensionSpecialEffects.SkyType.OVERWORLD;
    }
}
