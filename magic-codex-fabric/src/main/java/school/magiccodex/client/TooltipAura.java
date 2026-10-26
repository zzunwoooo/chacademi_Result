package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 아이템 툴팁 그림 둘레의 등급 연출 (오너 2026-10-26: 선·원 말고 구름 같은 파티클로 화려하게).
 * 고급 → 유일 → 전설 순으로 구름 수·크기·밝기가 늘고, 유일부터 떠오르는 빛가루, 전설은 뒤쪽 금빛 후광과 앞을 지나는 구름까지.
 * 일반 등급은 아무것도 그리지 않는다. 상태 없이 시간만으로 계산 (매 프레임 할당 없음, 고정 개수).
 */
final class TooltipAura {
    private TooltipAura() {}

    private static Identifier tex(String name) { return Identifier.of("magiccodex", "textures/gui/tooltip/fx/" + name + ".png"); }
    private static final FishingArt.Art[] CLOUD = {
            new FishingArt.Art(tex("cloud_0"), 128, 128), new FishingArt.Art(tex("cloud_1"), 128, 128), new FishingArt.Art(tex("cloud_2"), 128, 128)};
    private static final FishingArt.Art SPARK = new FishingArt.Art(tex("sparkle"), 64, 64);

    /** puffs 구름 수 · rx/ry 구름 띠 반지름 · size 구름 크기 · alpha 구름 밝기 · motes 빛가루 · sparks 반짝임 · core 후광. */
    private record Style(int puffs, float rx, float ry, float size, float alpha, int motes, int sparks, float core, int front) {}
    private static final Style HIGH = new Style(11, 26, 10, 20, .40f, 3, 3, .35f, 0);
    private static final Style UNIQUE = new Style(16, 28, 11, 23, .48f, 8, 4, .48f, 2);
    private static final Style LEGEND = new Style(22, 30, 12, 26, .58f, 14, 7, .62f, 3);

    private static Style style(TooltipRarity.Grade g) {
        return g == null ? null : switch (g) { case HIGH -> HIGH; case UNIQUE -> UNIQUE; case LEGENDARY -> LEGEND; default -> null; };
    }

    static double hash(int i, int seed) { double v = Math.sin(i * 12.9898 + seed * 78.233) * 43758.5453; return v - Math.floor(v); }
    private static double frac(double v) { return v - Math.floor(v); }

    /** 아이템 그림 뒤: 후광 · 구름 띠 · 빛가루 · 반짝임. (cx, cy) = 아이템 가운데. */
    static void back(DrawContext c, float cx, float cy, long nowMs, TooltipRarity.Grade grade) {
        Style s = style(grade);
        if (s == null) return;
        double t = nowMs / 1000.0;
        int color = grade.color, light = FishingArt.mix(color, 0xFFFFFF, .45f) & 0xFFFFFF;
        FishingArt.bloom(c, cx, cy, 34, color, s.core() * (.85f + .15f * (float) Math.sin(t * 1.7)));
        FishingArt.bloom(c, cx, cy, 15, light, s.core() * .6f);
        for (int i = 0; i < s.puffs(); i++) puff(c, cx, cy, t, s, color, i, 1, false);
        for (int i = 0; i < s.motes(); i++) {
            double period = 2.2 + 1.6 * hash(i, 11), ph = frac(t / period + hash(i, 12));
            float a = (float) Math.sin(ph * Math.PI);
            float px = cx + (float) ((hash(i, 13) - .5) * 1.6 * s.rx() + Math.sin(t * 2 + i) * 3);
            float py = cy + 15 - (float) (ph * 32);
            FishingArt.bloom(c, px, py, 3.6f, color, .55f * a);
            FishingArt.bloom(c, px, py, 1.4f, 0xFFFFFF, .85f * a);
        }
        for (int i = 0; i < s.sparks(); i++) {
            double period = 1.5 + 1.3 * hash(i, 21), v = t / period + hash(i, 22), ph = frac(v);
            int k = (int) Math.floor(v);
            double ang = hash(i * 31 + k, 23) * Math.PI * 2, rad = .55 + .55 * hash(i * 31 + k, 24);
            float px = cx + (float) (Math.cos(ang) * s.rx() * rad), py = cy + (float) (Math.sin(ang) * s.ry() * 1.5 * rad);
            float sc = (float) Math.pow(Math.sin(ph * Math.PI), 2);
            float size = (float) (9 + 6 * hash(i, 25)) * (.6f + .4f * sc);
            FishingArt.drawCentered(c, SPARK, px, py, size, size, FishingArt.argb(.95f * sc, light), false, (float) (ph * .9), true);
        }
    }

    /** 아이템 그림 앞: 아래쪽을 천천히 지나가는 옅은 구름 (유일·전설). */
    static void front(DrawContext c, float cx, float cy, long nowMs, TooltipRarity.Grade grade) {
        Style s = style(grade);
        if (s == null || s.front() == 0) return;
        double t = nowMs / 1000.0;
        for (int i = 0; i < s.front(); i++) puff(c, cx, cy, t, s, grade.color, i, 2, true);
    }

    private static void puff(DrawContext c, float cx, float cy, double t, Style s, int color, int i, int seed, boolean front) {
        double period = 3.2 + 2.6 * hash(i, seed * 10 + 1), ph = frac(t / period + hash(i, seed * 10 + 2));
        double ang = ((double) i / s.puffs() + .35 * hash(i, seed * 10 + 3)) * Math.PI * 2 + t * .18 * (i % 2 == 0 ? 1 : -1);
        float px, py;
        if (front) {   // 아래 반쪽을 왼쪽→오른쪽으로
            px = cx + (float) ((ph - .5) * 2.2 * s.rx());
            py = cy + 7 + (float) (hash(i, 27) * 5);
        } else {
            px = cx + (float) (Math.cos(ang) * (s.rx() + ph * 6));
            py = cy + 3 + (float) (Math.sin(ang) * s.ry() - ph * 7);
        }
        float size = s.size() * (float) (.7 + .6 * hash(i, seed * 10 + 4)) * (float) (.8 + .45 * ph);
        float a = s.alpha() * (float) Math.pow(Math.sin(ph * Math.PI), 1.3) * (front ? .45f : 1);
        int col = FishingArt.mix(color, 0xFFFFFF, (float) (.05 + .25 * hash(i, seed * 10 + 5))) & 0xFFFFFF;
        FishingArt.drawCentered(c, CLOUD[(i + seed) % 3], px, py, size * 1.6f, size, FishingArt.argb(a, col),
                hash(i, seed * 10 + 6) > .5, (float) ((hash(i, seed * 10 + 7) - .5) * .5 + ph * .25), true);
    }
}
