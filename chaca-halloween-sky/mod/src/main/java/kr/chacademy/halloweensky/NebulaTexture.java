package kr.chacademy.halloweensky;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 셰이더 안 켠 사람용 "마법 구름" 그림. 처음 쓸 때 한 번 만든다 (이음매 없이 반복되는 흰색 성운, 알파 = 진하기).
 * 색은 그릴 때 정점 색으로 칠한다 (청록·분홍·파랑 세 겹).
 */
final class NebulaTexture {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("chaca_sky", "dynamic/nebula");
    private static final int N = 256, PERIOD = 8;
    private static boolean made;

    private NebulaTexture() {}

    static ResourceLocation get() {
        if (!made) {
            made = true;
            NativeImage image = new NativeImage(N, N, false);
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    float u = (float) x / N * PERIOD, v = (float) y / N * PERIOD;
                    float wx = fbm(u * 0.8f, v * 0.8f, 3.1f), wy = fbm(u * 0.8f + 5.2f, v * 0.8f, 7.7f);
                    float n = fbm(u + wx * 1.6f, v + wy * 1.6f, 0f);
                    float a = smooth(0.50f, 0.82f, n);
                    float dust = smooth(0.80f, 0.95f, fbm(u * 4f, v * 4f, 13f)) * a;
                    int alpha = (int) (Math.min(1f, a + dust * 0.6f) * 255);
                    image.setPixel(x, y, (alpha << 24) | 0xFFFFFF);
                }
            }
            DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
            texture.setFilter(true, false);
            texture.setClamp(false);
        }
        return ID;
    }

    // 격자를 period 칸마다 감는 값 노이즈 → 그림이 이음매 없이 반복된다
    private static float hash(int x, int y, float seed, int period) {
        x = Math.floorMod(x, period);
        y = Math.floorMod(y, period);
        double s = Math.sin(x * 127.1 + y * 311.7 + seed * 74.7 + period * 1.37) * 43758.5453;
        return (float) (s - Math.floor(s));
    }

    private static float noise(float x, float y, float seed, int scale) {
        int period = PERIOD * scale;
        float px = x * scale, py = y * scale;
        int ix = (int) Math.floor(px), iy = (int) Math.floor(py);
        float fx = px - ix, fy = py - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy, seed, period), b = hash(ix + 1, iy, seed, period);
        float c = hash(ix, iy + 1, seed, period), d = hash(ix + 1, iy + 1, seed, period);
        float top = a + (b - a) * fx, bottom = c + (d - c) * fx;
        return top + (bottom - top) * fy;
    }

    /** x, y 는 0..PERIOD 가 한 바퀴. 옥타브마다 칸 수를 두 배로 → 모두 같은 주기에서 감긴다. */
    private static float fbm(float x, float y, float seed) {
        float s = 0, amp = 0.5f;
        for (int i = 0, scale = 1; i < 5; i++, scale *= 2) {
            s += amp * noise(x, y, seed, scale);
            amp *= 0.5f;
        }
        return s;
    }

    private static float smooth(float a, float b, float x) {
        float k = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return k * k * (3f - 2f * k);
    }
}
