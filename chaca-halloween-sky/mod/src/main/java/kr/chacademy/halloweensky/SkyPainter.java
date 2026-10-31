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
 * 셰이더를 안 켠 사람용 할로윈 하늘. 바닐라 하늘을 다 그린 직후(지형보다 먼저) 카메라 둘레 반구에 덧그린다.
 * 셰이더 같은 빛 번짐은 없으니, 빛나는 것(쓸림 앞줄·호박달·별)은 가산 합성으로 여러 겹 겹쳐 흉내 낸다.
 */
public final class SkyPainter {
    static final ResourceLocation MOON = ResourceLocation.fromNamespaceAndPath("chaca_sky", "textures/pumpkin_moon.png");
    /** 호박달 방향 (동쪽 +x, 위 +y, 남쪽 +z). 셰이더도 같은 값. */
    public static final Vector3f MOON_DIR = new Vector3f(0.75f, 0.45f, 0.35f).normalize();
    static final float MOON_RADIUS = 0.07f, R = 100f;
    static final int SEG = 72, RINGS = 28, STARS = 900;
    private static final float[][] STAR_DIRS = new float[STARS][4];
    /** 이번 프레임 실제 시간 밝기 (0 밤 ~ 1 낮). */
    private static float day;

    static {
        RandomSource random = RandomSource.create(20261031L);
        for (float[] s : STAR_DIRS) {
            float y = 0.04f + random.nextFloat() * 0.96f;
            float a = random.nextFloat() * (float) (Math.PI * 2);
            float h = (float) Math.sqrt(1 - y * y);
            s[0] = h * (float) Math.cos(a);
            s[1] = y;
            s[2] = h * (float) Math.sin(a);
            s[3] = random.nextFloat();
        }
    }

    private SkyPainter() {}

    /** 할로윈 하늘 색. 실제 시간 밝기를 따라 밤 색과 낮 색을 섞는다. */
    static float[] skyColor(float x, float y, float z, float day) {
        float[] n = nightColor(x, y, z), d = dayColor(y);
        return new float[]{lerp(n[0], d[0], day), lerp(n[1], d[1], day), lerp(n[2], d[2], day)};
    }

    /** 낮: 지평선 밝은 주황 → 분홍 → 라벤더 (밤이 되지 않게 밝게). */
    static float[] dayColor(float y) {
        if (y < 0f) return new float[]{0.80f, 0.55f, 0.45f};
        if (y < 0.15f) {
            float k = y / 0.15f;
            return new float[]{lerp(1.0f, 0.95f, k), lerp(0.70f, 0.62f, k), lerp(0.42f, 0.66f, k)};
        }
        float k = Math.min(1f, (y - 0.15f) / 0.85f);
        return new float[]{lerp(0.95f, 0.62f, k), lerp(0.62f, 0.52f, k), lerp(0.66f, 0.85f, k)};
    }

    /** 밤: 지평선 주황 → 자홍 → 보라 → 남색. */
    static float[] nightColor(float x, float y, float z) {
        float r, g, b;
        if (y < 0f) {
            float k = Math.min(1f, -y * 4f);
            r = lerp(1.0f, 0.10f, k); g = lerp(0.36f, 0.03f, k); b = lerp(0.10f, 0.08f, k);
        } else if (y < 0.12f) {
            float k = y / 0.12f;
            r = lerp(1.0f, 0.78f, k); g = lerp(0.36f, 0.17f, k); b = lerp(0.10f, 0.34f, k);
        } else if (y < 0.4f) {
            float k = (y - 0.12f) / 0.28f;
            r = lerp(0.78f, 0.30f, k); g = lerp(0.17f, 0.09f, k); b = lerp(0.34f, 0.42f, k);
        } else {
            float k = Math.min(1f, (y - 0.4f) / 0.6f);
            r = lerp(0.30f, 0.06f, k); g = lerp(0.09f, 0.03f, k); b = lerp(0.42f, 0.15f, k);
        }
        // 호박달 쪽이 달빛으로 은은하게 밝다 (대기 산란 흉내)
        float d = Math.max(0f, x * MOON_DIR.x + y * MOON_DIR.y + z * MOON_DIR.z);
        float m = (float) Math.pow(d, 10) * 0.45f + (float) Math.pow(d, 60) * 0.5f;
        return new float[]{Math.min(1f, r + m * 1.0f), Math.min(1f, g + m * 0.5f), Math.min(1f, b + m * 0.18f)};
    }

    public static void render() {
        float f = SkyState.sweep();
        if (f <= -SkyState.EDGE) return;
        if (IrisCheck.shadersOn()) return; // 셰이더가 하늘을 직접 그린다
        float time = (Util.getMillis() % 100000L) / 1000f;
        day = SkyState.day();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // 1) 할로윈 하늘 반구 (쓸린 만큼만 보이게)
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        BufferBuilder dome = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        buildDome(dome, f, false);
        draw(dome);

        // 2) 빛나는 것들 (가산 합성): 쓸림 앞줄 빛 띠, 별, 달무리
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        BufferBuilder glow = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        buildDome(glow, f, true);
        buildStars(glow, f, time);
        float moonMask = SkyState.mask((MOON_DIR.x + 1f) / 2f, f);
        if (moonMask > 0.01f) {
            float pulse = 0.9f + 0.1f * (float) Math.sin(time * 1.3f);
            halo(glow, MOON_RADIUS * 9f, 1.0f, 0.45f, 0.12f, 0.22f * moonMask * pulse);
            halo(glow, MOON_RADIUS * 3.5f, 1.0f, 0.60f, 0.20f, 0.35f * moonMask * pulse);
            halo(glow, MOON_RADIUS * 1.6f, 1.0f, 0.80f, 0.40f, 0.45f * moonMask * pulse);
        }
        draw(glow);

        // 3) 호박달 그림
        if (moonMask > 0.01f) {
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
            RenderSystem.setShaderTexture(0, MOON);
            BufferBuilder moon = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            moonQuad(moon, MOON_RADIUS * 1.15f, moonMask);
            draw(moon);
            // 속에서 빛나는 느낌: 같은 그림을 가산으로 한 번 더
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            BufferBuilder bloom = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            moonQuad(bloom, MOON_RADIUS * 1.15f, moonMask * 0.35f);
            moonQuad(bloom, MOON_RADIUS * 1.35f, moonMask * 0.15f);
            draw(bloom);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void draw(BufferBuilder builder) {
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** 반구(조금 아래까지) 격자. glowPass 면 앞줄 빛 띠만. */
    private static void buildDome(BufferBuilder b, float f, boolean glowPass) {
        float[][] ring = new float[SEG + 1][];
        float[][] prev = null;
        for (int j = 0; j <= RINGS; j++) {
            // 아래 -0.3 에서 꼭대기 1 까지, 지평선 근처를 촘촘하게
            float v = (float) j / RINGS;
            float y = -0.3f + 1.3f * (float) Math.pow(v, 1.6);
            y = Math.min(1f, y);
            float h = (float) Math.sqrt(Math.max(0f, 1f - y * y));
            ring = new float[SEG + 1][];
            for (int i = 0; i <= SEG; i++) {
                float a = (float) (i * Math.PI * 2 / SEG);
                float x = h * (float) Math.cos(a), z = h * (float) Math.sin(a);
                float t = (x + 1f) / 2f;
                float[] c;
                float alpha;
                if (glowPass) {
                    // 앞줄이 끝까지 가면(또는 막 시작하면) 빛 띠가 남지 않게 사라진다
                    float fade = (1f - SkyState.smooth(0.98f, 1f + SkyState.EDGE + 0.02f, f))
                            * SkyState.smooth(-SkyState.EDGE - 0.02f, 0.02f, f);
                    float g = SkyState.glow(t, f) * fade;
                    float lift = 1f - Math.min(1f, Math.max(0f, y)) * 0.5f; // 지평선 쪽이 더 진하게
                    c = new float[]{1.0f, 0.55f, 0.16f};
                    alpha = 0.85f * g * lift;
                } else {
                    c = skyColor(x, y, z, day);
                    alpha = SkyState.mask(t, f);
                }
                ring[i] = new float[]{x * R, y * R, z * R, c[0], c[1], c[2], alpha};
            }
            if (prev != null) {
                for (int i = 0; i < SEG; i++) {
                    tri(b, prev[i], ring[i], ring[i + 1]);
                    tri(b, prev[i], ring[i + 1], prev[i + 1]);
                }
            }
            prev = ring;
        }
    }

    private static void tri(BufferBuilder b, float[] p, float[] q, float[] r) {
        if (p[6] <= 0.002f && q[6] <= 0.002f && r[6] <= 0.002f) return;
        vertex(b, p);
        vertex(b, q);
        vertex(b, r);
    }

    private static void vertex(BufferBuilder b, float[] p) {
        b.addVertex(p[0], p[1], p[2]).setColor(p[3], p[4], p[5], p[6]);
    }

    private static void buildStars(BufferBuilder b, float f, float time) {
        for (float[] s : STAR_DIRS) {
            float m = SkyState.mask((s[0] + 1f) / 2f, f);
            if (m <= 0.01f) continue;
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * (1.5f + s[3] * 3f) + s[3] * 40f);
            float bright = m * twinkle * (1f - day) * Math.min(1f, s[1] * 3f) * (0.4f + 0.6f * s[3]);
            float size = 0.0025f + 0.004f * s[3];
            Vector3f dir = new Vector3f(s[0], s[1], s[2]);
            Vector3f[] basis = basis(dir);
            Vector3f c = new Vector3f(dir).mul(R * 0.98f);
            Vector3f u = new Vector3f(basis[0]).mul(size * R), w = new Vector3f(basis[1]).mul(size * R);
            float[] p0 = {c.x - u.x - w.x, c.y - u.y - w.y, c.z - u.z - w.z, 1f, 0.9f, 0.85f, bright};
            float[] p1 = {c.x + u.x - w.x, c.y + u.y - w.y, c.z + u.z - w.z, 1f, 0.9f, 0.85f, bright};
            float[] p2 = {c.x + u.x + w.x, c.y + u.y + w.y, c.z + u.z + w.z, 1f, 0.9f, 0.85f, bright};
            float[] p3 = {c.x - u.x + w.x, c.y - u.y + w.y, c.z - u.z + w.z, 1f, 0.9f, 0.85f, bright};
            tri(b, p0, p1, p2);
            tri(b, p0, p2, p3);
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
                vertex(b, center);
                vertex(b, last);
                vertex(b, p);
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
