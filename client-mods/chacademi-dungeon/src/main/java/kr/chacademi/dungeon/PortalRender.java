package kr.chacademi.dungeon;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

/**
 * 던전 입장 포탈 홀로그램 (본인 화면에만, 서버가 보낸 포탈 위치). 스토리 퀘스트 목표 포탈(웨이포인트)과 같은 그림을
 * 리치 연구실 색(보라·진홍)으로: 바닥 링 두 겹이 반대로 돌고, 링 둘레 빛 띠가 위로 흘러 오르며, 위에 던전 이름.
 * 그림은 스토리 모드의 portal_ring·portal_glow·portal_band·halo·glint 를 빌려 쓴다 (없으면 그리지 않음).
 */
final class PortalRender {
    private PortalRender() {}

    private static final MultiBufferSource.BufferSource OWN = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
    private static final double MAX_DIST_SQ = 96 * 96;
    private static final float RING_IN_IMAGE = 0.66f, STAR_IN_IMAGE = 0.65f;
    private static final int SEGMENTS = 20;
    /** 보라빛 (링·띠), 진홍 (번짐). */
    private static final float[] VIOLET = {0.78f, 0.55f, 1f}, CRIMSON = {1f, 0.35f, 0.5f};

    static void render(WorldRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || DungeonClient.portals.isEmpty()) return;
        String here = mc.level.dimension().location().toString();
        PoseStack pose = ctx.matrixStack() != null ? ctx.matrixStack() : new PoseStack();
        var camera = ctx.camera();
        var cam = camera.getPosition();
        float t = (DungeonClient.now() % 3_600_000L) / 1000f;
        boolean drew = false;
        for (var p : DungeonClient.portals) {
            if (!p.dimension().equals(here) || cam.distanceToSqr(p.x(), p.y(), p.z()) > MAX_DIST_SQ) continue;
            drawPortal(pose, p.x() - cam.x, p.y() - cam.y, p.z() - cam.z, p.radius(), t, camera.rotation());
            double dist = Math.sqrt(cam.distanceToSqr(p.x(), p.y(), p.z()));
            drawLabel(pose, camera.rotation(), p.x() - cam.x, p.y() - cam.y, p.z() - cam.z, p.radius(), t, p.label(), Mth.clamp((float) (48 - dist) / 12f, 0, 1));
            drew = true;
        }
        if (drew) OWN.endBatch();
    }

    private static void drawPortal(PoseStack pose, double x, double y, double z, float radius, float t, org.joml.Quaternionf camRot) {
        float r = Math.max(0.8f, radius);
        float half = r / RING_IN_IMAGE;
        float breath = 0.86f + 0.14f * Mth.sin(t * 1.7f);
        float hum = 0.5f + 0.5f * Mth.sin(t * 1.25f);
        float glow = 0.75f + 0.35f * hum;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.pushPose();
        pose.translate(0, 0.02, 0);
        flat(pose, "portal_glow", true, half * 1.02f, Art.argb(0.55f * glow, CRIMSON[0], CRIMSON[1], CRIMSON[2]));
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 0.04, 0);
        pose.mulPose(Axis.YP.rotationDegrees(t * 12f));
        flat(pose, "portal_ring_glow", true, half * 1.03f, Art.argb(0.5f * glow, VIOLET[0], VIOLET[1], VIOLET[2]));
        flat(pose, "portal_ring", false, half, Art.argb(breath, VIOLET[0], VIOLET[1], VIOLET[2]));
        flat(pose, "portal_ring", true, half, Art.argb(0.35f * glow, 1f, 1f, 1f));
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 0.05, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-t * 20f + 45f));
        flat(pose, "portal_ring", false, half * 0.78f, Art.argb(0.42f * breath, CRIMSON[0], CRIMSON[1], CRIMSON[2]));
        pose.popPose();
        for (int k = 0; k < 2; k++) {
            float phase = ((t * 0.32f) + k * 0.5f) % 1f;
            float a = Mth.sin(phase * Mth.PI);
            pose.pushPose();
            pose.translate(0, 0.04 + phase * 0.45f, 0);
            pose.mulPose(Axis.YP.rotationDegrees(k * 37f + t * 6f * (k == 0 ? 1 : -1)));
            band(pose, false, r * 0.93f, 2.1f, a * 0.85f * breath);
            band(pose, true, r * 0.93f, 2.1f, a * 0.7f * glow);
            pose.popPose();
        }
        pose.popPose();
        double turn = Math.toRadians(t * 12f);
        float d = half * STAR_IN_IMAGE;
        for (int i = 0; i < 4; i++) {
            float wave = 0.5f + 0.5f * Mth.sin(t * 2.2f - i * Mth.HALF_PI);
            double ang = i * Math.PI / 2 + turn;
            float size = 0.2f + 0.16f * wave;
            billboard(pose, camRot, Art.quest("halo", false).orElse(null), true, x + Math.sin(ang) * d, y + 0.16, z + Math.cos(ang) * d, size * 2.6f, size * 2.6f,
                    Art.argb((0.18f + 0.4f * wave) * glow, VIOLET[0], VIOLET[1], VIOLET[2]));
            billboard(pose, camRot, Art.quest("glint", false).orElse(null), true, x + Math.sin(ang) * d, y + 0.16, z + Math.cos(ang) * d, size, size,
                    Art.argb(0.25f + 0.75f * wave, 1f, 1f, 1f));
        }
    }

    private static void drawLabel(PoseStack pose, org.joml.Quaternionf camRot, double x, double y, double z, float radius, float t, String label, float alpha) {
        if (alpha <= 0.02f) return;
        var text = Art.worldText(label);
        if (text.isEmpty()) return;
        var tex = text.get();
        double ly = y + 2.5 + Math.min(0.5, Math.max(0, radius - 1) * 0.25) + Mth.sin(t * 1.6f) * 0.05;
        float h = 0.34f, w = h * tex.width() / (float) tex.height();
        billboard(pose, camRot, Art.quest("halo", false).orElse(null), true, x, ly, z, w * 1.5f, h * 3.2f, Art.argb(alpha * 0.25f, VIOLET[0], VIOLET[1], VIOLET[2]));
        float o = h * 0.045f;
        int outline = Art.argb(alpha * 0.85f, 0.04f, 0.04f, 0.06f);
        for (float[] off : new float[][]{{-o, 0}, {o, 0}, {0, -o}, {0, o}}) billboard(pose, camRot, tex, false, x, ly, z, w, h, outline, off[0], off[1]);
        billboard(pose, camRot, tex, false, x, ly, z, w, h, Art.argb(alpha, 0.95f, 0.9f, 1f), 0, 0);
    }

    private static VertexConsumer buffer(Art.Tex tex, boolean additive) {
        return OWN.getBuffer(Art.shaderPack() ? RenderType.entityTranslucentEmissive(tex.id()) : Art.worldLayer(tex, additive));
    }

    private static void flat(PoseStack pose, String name, boolean additive, float half, int color) {
        if (additive && Art.shaderPack()) return;
        var tex = Art.quest(name, false).orElse(null);
        if (tex == null || (color >>> 24) == 0) return;
        var vc = buffer(tex, additive);
        var last = pose.last();
        vertex(vc, last, -half, 0, -half, 0, 0, color, 0, 1, 0);
        vertex(vc, last, -half, 0, half, 0, 1, color, 0, 1, 0);
        vertex(vc, last, half, 0, half, 1, 1, color, 0, 1, 0);
        vertex(vc, last, half, 0, -half, 1, 0, color, 0, 1, 0);
    }

    private static void band(PoseStack pose, boolean additive, float radius, float height, float alpha) {
        if (alpha <= 0.01f || (additive && Art.shaderPack())) return;
        var tex = Art.quest("portal_band", false).orElse(null);
        if (tex == null) return;
        var vc = buffer(tex, additive);
        var last = pose.last();
        int bottom = Art.argb(Math.min(1f, alpha), VIOLET[0], VIOLET[1], VIOLET[2]), top = Art.argb(0f, VIOLET[0], VIOLET[1], VIOLET[2]);
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2 / SEGMENTS), a1 = (float) ((i + 1) * Math.PI * 2 / SEGMENTS);
            float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius, x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
            float u0 = (float) i / SEGMENTS * 2f, u1 = (float) (i + 1) / SEGMENTS * 2f;
            float nx = Mth.cos((a0 + a1) / 2), nz = Mth.sin((a0 + a1) / 2);
            vertex(vc, last, x0, 0, z0, u0, 1, bottom, nx, 0, nz);
            vertex(vc, last, x1, 0, z1, u1, 1, bottom, nx, 0, nz);
            vertex(vc, last, x1, height, z1, u1, 0, top, nx, 0, nz);
            vertex(vc, last, x0, height, z0, u0, 0, top, nx, 0, nz);
        }
    }

    private static void billboard(PoseStack pose, org.joml.Quaternionf camRot, Art.Tex tex, boolean additive, double x, double y, double z, float w, float h, int color) {
        billboard(pose, camRot, tex, additive, x, y, z, w, h, color, 0, 0);
    }

    private static void billboard(PoseStack pose, org.joml.Quaternionf camRot, Art.Tex tex, boolean additive, double x, double y, double z,
                                  float w, float h, int color, float dx, float dy) {
        if (tex == null || (color >>> 24) == 0 || (additive && Art.shaderPack())) return;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(camRot);
        var vc = buffer(tex, additive);
        var last = pose.last();
        float hw = w / 2, hh = h / 2;
        vertex(vc, last, dx - hw, dy - hh, 0, 0, 1, color, 0, 0, 1);
        vertex(vc, last, dx + hw, dy - hh, 0, 1, 1, color, 0, 0, 1);
        vertex(vc, last, dx + hw, dy + hh, 0, 1, 0, color, 0, 0, 1);
        vertex(vc, last, dx - hw, dy + hh, 0, 0, 0, color, 0, 0, 1);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose last, float x, float y, float z, float u, float v, int color, float nx, float ny, float nz) {
        vc.addVertex(last, x, y, z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, nx, ny, nz);
    }
}
