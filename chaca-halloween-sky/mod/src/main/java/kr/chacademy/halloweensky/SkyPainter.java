package kr.chacademy.halloweensky;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * 셰이더를 안 켠 사람용 할로윈 하늘 (오너 참고 그림 느낌: 짙은 남색→보라 하늘, 청록·분홍 성운 구름, 십자로 반짝이는 별, 호박달 주황빛).
 * 바닐라 하늘을 다 그린 직후(지형보다 먼저) 카메라 둘레 반구에 덧그린다. 빛나는 것은 가산 합성으로 겹쳐 빛 번짐을 흉내 낸다.
 * 밝기는 실제 시간을 따라간다 (낮엔 같은 색을 밝게, 별은 희미하게).
 */
public final class SkyPainter {
    static final ResourceLocation MOON = ResourceLocation.fromNamespaceAndPath("chaca_sky", "textures/pumpkin_moon.png");
    /** 호박달 방향 (동쪽 +x, 위 +y, 남쪽 +z). 셰이더도 같은 값. */
    public static final Vector3f MOON_DIR = new Vector3f(0.75f, 0.45f, 0.35f).normalize();
    static final float MOON_RADIUS = 0.075f, R = 100f;
    static final int SEG = 96, RINGS = 36, STARS = 1400;
    private static final float[][] STAR_DIRS = new float[STARS][4];
    /** 이번 프레임 값. */
    private static float day, sweep, time;

    static {
        RandomSource random = RandomSource.create(20261031L);
        for (float[] s : STAR_DIRS) {
            float y = 0.03f + random.nextFloat() * 0.97f;
            float a = random.nextFloat() * (float) (Math.PI * 2);
            float h = (float) Math.sqrt(1 - y * y);
            s[0] = h * (float) Math.cos(a);
            s[1] = y;
            s[2] = h * (float) Math.sin(a);
            s[3] = random.nextFloat();
        }
    }

    private SkyPainter() {}

    /** 정점 하나의 색 (r, g, b, a) 와 그림 좌표 (u, v). */
    private interface Paint {
        float[] at(float x, float y, float z, float t);
    }

    public static void render() {
        sweep = SkyState.sweep();
        if (sweep <= -SkyState.EDGE) return;
        if (IrisCheck.shadersOn()) return; // 셰이더가 하늘을 직접 그린다
        time = (Util.getMillis() % 1000000L) / 1000f;
        day = SkyState.day();
        float dim = lerp(1f, 1.9f, day);

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // 1) 하늘 바탕 (쓸린 만큼만)
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        BufferBuilder base = begin(false);
        dome(base, false, (x, y, z, t) -> {
            float[] c = skyColor(x, y, z, dim);
            return new float[]{c[0], c[1], c[2], SkyState.mask(t, sweep), 0, 0};
        });
        draw(base);

        // 2) 성운 구름 세 겹 (가산): 파랑 넓게, 청록, 분홍. 천천히 흐른다
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, NebulaTexture.get());
        BufferBuilder clouds = begin(true);
        nebula(clouds, 0.55f, 0.010f, 0.004f, false, 0.10f, 0.18f, 1.00f, 0.30f * dim);
        nebula(clouds, 0.95f, 0.012f, -0.006f, false, 0.02f, 0.70f, 1.00f, 0.42f * dim);
        nebula(clouds, 0.80f, -0.009f, 0.011f, true, 1.00f, 0.12f, 0.72f, 0.48f * dim);
        draw(clouds);

        // 3) 빛나는 것들 (가산): 쓸림 앞줄 빛 띠, 별, 달무리
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        BufferBuilder glow = begin(false);
        float fade = (1f - SkyState.smooth(0.98f, 1f + SkyState.EDGE + 0.02f, sweep))
                * SkyState.smooth(-SkyState.EDGE - 0.02f, 0.02f, sweep);
        if (fade > 0.001f) {
            dome(glow, false, (x, y, z, t) -> {
                float lift = 0.35f + 0.65f * SkyState.smooth(-0.1f, 0.2f, y);
                return new float[]{1.0f, 0.5f, 0.15f, Math.min(1f, 1.2f * SkyState.glow(t, sweep) * fade * lift), 0, 0};
            });
        }
        buildStars(glow);
        float moonMask = SkyState.mask((MOON_DIR.x + 1f) / 2f, sweep);
        if (moonMask > 0.01f) {
            float pulse = 0.92f + 0.08f * (float) Math.sin(time * 1.3f);
            halo(glow, MOON_RADIUS * 10f, 1.0f, 0.38f, 0.06f, 0.20f * moonMask * pulse);
            halo(glow, MOON_RADIUS * 3.5f, 1.0f, 0.45f, 0.10f, 0.40f * moonMask * pulse);
            halo(glow, MOON_RADIUS * 1.7f, 1.0f, 0.65f, 0.25f, 0.45f * moonMask * pulse);
        }
        draw(glow);

        // 4) 호박달 그림 + 속에서 빛나는 느낌 (같은 그림 가산 한 번 더)
        if (moonMask > 0.01f) {
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
            RenderSystem.setShaderTexture(0, MOON);
            BufferBuilder moon = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            moonQuad(moon, MOON_RADIUS, moonMask);
            draw(moon);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            BufferBuilder bloom = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            moonQuad(bloom, MOON_RADIUS, moonMask * 0.30f);
            moonQuad(bloom, MOON_RADIUS * 1.2f, moonMask * 0.12f);
            draw(bloom);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    /** 하늘 바탕색: 위 짙은 남색 → 파랑·보라 → 지평선 자홍, 호박달 쪽만 주황빛. dim = 시간 밝기. */
    static float[] skyColor(float x, float y, float z, float dim) {
        float up = Math.max(0f, y), down = 1f - up;
        float r = 0.020f, g = 0.012f, b = 0.070f;                     // 꼭대기
        float k = SkyState.smooth(0f, 0.7f, down);                    // → 파랑
        r = lerp(r, 0.05f, k); g = lerp(g, 0.05f, k); b = lerp(b, 0.50f, k);
        k = (float) Math.pow(down, 4);                                // → 보라
        r = lerp(r, 0.20f, k); g = lerp(g, 0.07f, k); b = lerp(b, 0.72f, k);
        k = (float) Math.pow(down, 12);                               // → 지평선 자홍
        r = lerp(r, 0.72f, k); g = lerp(g, 0.16f, k); b = lerp(b, 0.82f, k);
        float md = Math.max(0f, x * MOON_DIR.x + y * MOON_DIR.y + z * MOON_DIR.z);
        float orange = (float) (Math.pow(md, 5) * 0.20 + Math.pow(md, 40) * 0.45
                + Math.pow(down, 14) * Math.pow(md, 1.5) * 0.6);
        r += orange; g += orange * 0.40f; b += orange * 0.09f;
        if (y < 0f) { float d = Math.min(1f, -y * 4f); r *= 1 - d * 0.7f; g *= 1 - d * 0.7f; b *= 1 - d * 0.6f; }
        return new float[]{Math.min(1f, r * dim), Math.min(1f, g * dim), Math.min(1f, b * dim)};
    }

    /** 성운 한 겹: 반구를 평면에 펼친 좌표(그림 반복)로 흐르게 칠한다. swap = 다른 방향으로 펼쳐 겹침이 덜 보이게. */
    private static void nebula(BufferBuilder b, float scale, float flowU, float flowV, boolean swap,
                               float r, float g, float bl, float strength) {
        dome(b, true, (x, y, z, t) -> {
            float band = SkyState.smooth(0f, 0.18f, y) * (1f - SkyState.smooth(0.5f, 0.9f, y)) * 0.85f + 0.15f;
            band *= SkyState.smooth(-0.04f, 0.02f, y);
            float den = Math.max(0.2f, y + 0.25f);
            float u = (swap ? z : x) / den * scale + time * flowU;
            float v = (swap ? -x : z) / den * scale + time * flowV;
            float a = Math.min(1f, strength * band * SkyState.mask(t, sweep));
            return new float[]{r, g, bl, a, u, v};
        });
    }

    private static BufferBuilder begin(boolean textured) {
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
    }

    private static void draw(BufferBuilder builder) {
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** 반구(조금 아래까지) 격자를 paint 로 칠한다. 지평선 근처를 촘촘하게. */
    private static void dome(BufferBuilder b, boolean textured, Paint paint) {
        float[][] prev = null;
        for (int j = 0; j <= RINGS; j++) {
            float v = (float) j / RINGS;
            float y = Math.min(1f, -0.3f + 1.3f * (float) Math.pow(v, 1.6));
            float h = (float) Math.sqrt(Math.max(0f, 1f - y * y));
            float[][] ring = new float[SEG + 1][];
            for (int i = 0; i <= SEG; i++) {
                float a = (float) (i * Math.PI * 2 / SEG);
                float x = h * (float) Math.cos(a), z = h * (float) Math.sin(a);
                float[] c = paint.at(x, y, z, (x + 1f) / 2f);
                ring[i] = new float[]{x * R, y * R, z * R, c[0], c[1], c[2], c[3], c[4], c[5]};
            }
            if (prev != null) {
                for (int i = 0; i < SEG; i++) {
                    tri(b, textured, prev[i], ring[i], ring[i + 1]);
                    tri(b, textured, prev[i], ring[i + 1], prev[i + 1]);
                }
            }
            prev = ring;
        }
    }

    private static void tri(BufferBuilder b, boolean textured, float[] p, float[] q, float[] r) {
        if (p[6] <= 0.002f && q[6] <= 0.002f && r[6] <= 0.002f) return;
        vertex(b, textured, p);
        vertex(b, textured, q);
        vertex(b, textured, r);
    }

    private static void vertex(BufferBuilder b, boolean textured, float[] p) {
        if (textured) b.addVertex(p[0], p[1], p[2]).setUv(p[7], p[8]).setColor(p[3], p[4], p[5], p[6]);
        else b.addVertex(p[0], p[1], p[2]).setColor(p[3], p[4], p[5], p[6]);
    }

    /** 잔별 + 큰 별은 십자 빛살 (분홍·하양). 낮엔 희미하게. */
    private static void buildStars(BufferBuilder b) {
        float vis = lerp(1f, 0.35f, day);
        for (float[] s : STAR_DIRS) {
            float m = SkyState.mask((s[0] + 1f) / 2f, sweep);
            if (m <= 0.01f) continue;
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * (1.2f + s[3] * 3f) + s[3] * 90f);
            float bright = m * twinkle * vis * SkyState.smooth(0f, 0.25f, s[1]);
            boolean big = s[3] > 0.93f;
            float pink = (s[3] * 7.3f) % 1f;
            float cr = 1f, cg = lerp(0.90f, 0.75f, pink), cb = lerp(1f, 0.95f, pink);
            Vector3f dir = new Vector3f(s[0], s[1], s[2]);
            Vector3f[] basis = basis(dir);
            Vector3f c = new Vector3f(dir).mul(R * 0.98f);
            float size = big ? 0.006f : 0.0022f + 0.0025f * s[3];
            quad(b, c, basis[0], basis[1], size, size, cr, cg, cb, bright * (big ? 1f : 0.85f));
            if (big) {
                // 십자 빛살 두 개 + 은은한 분홍 번짐
                quad(b, c, basis[0], basis[1], 0.045f, 0.0012f, cr, cg, cb, bright * 0.75f);
                quad(b, c, basis[0], basis[1], 0.0012f, 0.045f, cr, cg, cb, bright * 0.75f);
                quad(b, c, basis[0], basis[1], 0.02f, 0.02f, 1f, 0.45f, 0.85f, bright * 0.18f);
            }
        }
    }

    /** 가운데 밝고 가장자리 0 인 사각 (가산용). */
    private static void quad(BufferBuilder b, Vector3f c, Vector3f right, Vector3f up, float w, float h,
                             float r, float g, float bl, float alpha) {
        float[] center = {c.x, c.y, c.z, r, g, bl, alpha};
        float[][] corners = new float[4][];
        float[][] sign = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            float sx = sign[i][0] * w * R, sy = sign[i][1] * h * R;
            corners[i] = new float[]{c.x + right.x * sx + up.x * sy, c.y + right.y * sx + up.y * sy,
                    c.z + right.z * sx + up.z * sy, r, g, bl, 0f};
        }
        for (int i = 0; i < 4; i++) {
            vertex(b, false, center);
            vertex(b, false, corners[i]);
            vertex(b, false, corners[(i + 1) % 4]);
        }
    }

    /** 호박달 둘레의 둥근 빛 (가운데 진하고 가장자리 0). */
    private static void halo(BufferBuilder b, float radius, float r, float g, float bl, float alpha) {
        Vector3f[] basis = basis(MOON_DIR);
        Vector3f c = new Vector3f(MOON_DIR).mul(R * 0.97f);
        float[] center = {c.x, c.y, c.z, r, g, bl, alpha};
        int n = 40;
        float[] last = null;
        for (int i = 0; i <= n; i++) {
            float a = (float) (i * Math.PI * 2 / n);
            float cx = (float) Math.cos(a) * radius * R, cy = (float) Math.sin(a) * radius * R;
            float[] p = {c.x + basis[0].x * cx + basis[1].x * cy, c.y + basis[0].y * cx + basis[1].y * cy,
                    c.z + basis[0].z * cx + basis[1].z * cy, r, g, bl, 0f};
            if (last != null) {
                vertex(b, false, center);
                vertex(b, false, last);
                vertex(b, false, p);
            }
            last = p;
        }
    }

    private static void moonQuad(BufferBuilder b, float radius, float alpha) {
        Vector3f[] basis = basis(MOON_DIR);
        Vector3f c = new Vector3f(MOON_DIR).mul(R * 0.96f);
        float s = radius * R;
        Vector3f right = new Vector3f(basis[0]).mul(s), up = new Vector3f(basis[1]).mul(s);
        b.addVertex(c.x - right.x + up.x, c.y - right.y + up.y, c.z - right.z + up.z).setUv(0f, 0f).setColor(1f, 1f, 1f, alpha);
        b.addVertex(c.x - right.x - up.x, c.y - right.y - up.y, c.z - right.z - up.z).setUv(0f, 1f).setColor(1f, 1f, 1f, alpha);
        b.addVertex(c.x + right.x - up.x, c.y + right.y - up.y, c.z + right.z - up.z).setUv(1f, 1f).setColor(1f, 1f, 1f, alpha);
        b.addVertex(c.x + right.x + up.x, c.y + right.y + up.y, c.z + right.z + up.z).setUv(1f, 0f).setColor(1f, 1f, 1f, alpha);
    }

    /** dir 을 바라볼 때의 오른쪽·위쪽 (위쪽은 하늘 방향). */
    private static Vector3f[] basis(Vector3f dir) {
        Vector3f right = new Vector3f(dir).cross(0f, 1f, 0f);
        if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f);
        right.normalize();
        Vector3f up = new Vector3f(right).cross(dir).normalize();
        return new Vector3f[]{right, up};
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }
}
