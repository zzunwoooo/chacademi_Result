package school.magiccodex.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import school.magiccodex.protocol.KyudichiProtocol;

/**
 * 경기장 경계 제한선 (오너 2026-10-20): 경계 가까이 가면 그 벽에 반투명 홀로그램 격자가 나타난다.
 * 벽까지 {@value #SHOW}블록 안에서 서서히 진해지고, 내 위치를 중심으로 {@value #RADIUS}블록 원 안만 보인다 (멀수록 흐려짐).
 * 벽 4면 + 천장. 바닥은 땅에 서 있으면 늘 가까우므로 그리지 않는다. 경계는 서버가 BOUNDS 로 보내 준다.
 */
final class KyudichiBoundary {
    private KyudichiBoundary() {}

    static final Identifier TEXTURE = Identifier.of("magiccodex", "textures/kyudichi/boundary.png");
    static final double SHOW = 10, RADIUS = 9, CELL = 2, INSET = .02;
    /** Hologram tint (cyan-white) and the strongest alpha right at the wall. */
    static final int RGB = 0x8FE6FF;
    static final float MAX_ALPHA = .65f;

    private static KyudichiProtocol.Bounds bounds;

    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> bounds = null);
        WorldRenderEvents.AFTER_ENTITIES.register(KyudichiBoundary::render);
    }

    static void set(KyudichiProtocol.Bounds b) { bounds = b; }
    static void clear() { bounds = null; }

    private static void render(WorldRenderContext context) {
        var b = bounds;
        if (b == null || !KyudichiClient.active()) return;
        var c = MinecraftClient.getInstance();
        var consumers = context.consumers();
        if (consumers == null || context.matrixStack() == null || c.world == null || c.options.hudHidden) return;
        var cam = context.camera().getPos();
        double[] p = {cam.x, cam.y, cam.z};
        double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()};
        // nothing to draw when far from every wall (outside the box too: spectators flying around)
        boolean near = false;
        for (int a = 0; a < 3; a++) if (p[a] - lo[a] < SHOW && a != 1 || hi[a] - p[a] < SHOW) near = true;
        if (!near) return;
        float pulse = .88f + .12f * (float) Math.sin(Util.getMeasuringTimeMs() / 260.0);
        var matrices = context.matrixStack();
        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f m = matrices.peek().getPositionMatrix();
            VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TEXTURE));
            for (int a = 0; a < 3; a++) {
                if (a != 1) wall(vc, m, p, lo, hi, a, lo[a] + INSET, p[a] - lo[a], pulse);
                wall(vc, m, p, lo, hi, a, hi[a] - INSET, hi[a] - p[a], pulse);
            }
        } finally { matrices.pop(); }
    }

    /** One face: axis `a` fixed at `w`; d = how far the camera is from it (inside = positive). */
    private static void wall(VertexConsumer vc, Matrix4f m, double[] p, double[] lo, double[] hi, int a, double w, double d, float pulse) {
        if (d > SHOW || d < -SHOW) return;
        float fade = (float) (1 - Math.abs(d) / SHOW);
        int ua = a == 0 ? 2 : 0, va = a == 1 ? 2 : 1; // in-plane axes
        double pu = p[ua], pv = p[va];
        int u0 = (int) Math.floor((Math.max(lo[ua], pu - RADIUS) - lo[ua]) / CELL), u1 = (int) Math.ceil((Math.min(hi[ua], pu + RADIUS) - lo[ua]) / CELL);
        int v0 = (int) Math.floor((Math.max(lo[va], pv - RADIUS) - lo[va]) / CELL), v1 = (int) Math.ceil((Math.min(hi[va], pv + RADIUS) - lo[va]) / CELL);
        for (int i = u0; i < u1; i++) for (int j = v0; j < v1; j++) {
            double ca = lo[ua] + i * CELL, cb = Math.min(hi[ua], ca + CELL), da = lo[va] + j * CELL, db = Math.min(hi[va], da + CELL);
            if (cb <= ca || db <= da) continue;
            double r = Math.hypot((ca + cb) / 2 - pu, (da + db) / 2 - pv);
            float cell = (float) Math.clamp(1 - r / RADIUS, 0, 1);
            if (cell <= 0) continue;
            float alpha = MAX_ALPHA * fade * cell * cell * pulse;
            int color = (Math.round(alpha * 255) << 24) | RGB;
            float su = (float) ((cb - ca) / CELL), sv = (float) ((db - da) / CELL);
            float[][] q = {point(a, ua, va, w, ca, da, 0, 0), point(a, ua, va, w, cb, da, su, 0), point(a, ua, va, w, cb, db, su, sv), point(a, ua, va, w, ca, db, 0, sv)};
            // front face toward the camera (counter-clockwise as seen from it), so it shows from inside and outside
            // whether or not the layer culls, and is never drawn twice
            double[] n = cross(q);
            double toCam = n[0] * (p[0] - q[0][0]) + n[1] * (p[1] - q[0][1]) + n[2] * (p[2] - q[0][2]);
            float s = toCam >= 0 ? 1 : -1;
            float nx = (float) Math.signum(n[0]) * s, ny = (float) Math.signum(n[1]) * s, nz = (float) Math.signum(n[2]) * s;
            if (toCam >= 0) for (int k = 0; k < 4; k++) emit(vc, m, q[k], color, nx, ny, nz);
            else for (int k = 3; k >= 0; k--) emit(vc, m, q[k], color, nx, ny, nz);
        }
    }

    private static double[] cross(float[][] q) {
        double ax = q[1][0] - q[0][0], ay = q[1][1] - q[0][1], az = q[1][2] - q[0][2];
        double bx = q[2][0] - q[0][0], by = q[2][1] - q[0][1], bz = q[2][2] - q[0][2];
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static float[] point(int a, int ua, int va, double w, double u, double v, float tu, float tv) {
        double[] xyz = new double[3]; xyz[a] = w; xyz[ua] = u; xyz[va] = v;
        return new float[]{(float) xyz[0], (float) xyz[1], (float) xyz[2], tu, tv};
    }

    private static void emit(VertexConsumer vc, Matrix4f m, float[] v, int color, float nx, float ny, float nz) {
        vc.vertex(m, v[0], v[1], v[2]).color(color).texture(v[3], v[4]).overlay(OverlayTexture.DEFAULT_UV)
          .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(nx, ny, nz);
    }
}
