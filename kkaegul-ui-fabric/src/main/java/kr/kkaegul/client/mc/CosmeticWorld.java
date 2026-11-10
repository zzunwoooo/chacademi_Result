package kr.kkaegul.client.mc;

import kr.kkaegul.client.ui.Cosmetics;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 풍선 치장품 (월드 렌더 AFTER_ENTITIES). 엔티티 없이 클라가 위치를 들고 부드럽게 따라가게:
 *  목표 = 플레이어(보간 위치) 발 + 몸 방향 기준 오른쪽 0.45 · 뒤 0.3 · 위 2.5칸, 현재 위치는 매 프레임 지수 보간(시간상수 0.18초)으로 다가감 + 살짝 둥실.
 *  8칸 넘게 벌어지면(텔포·리스폰·월드 이동) 바로 붙임.
 *  그림 = 갑옷거치대 머리 칸과 같은 변환(몸 방향으로 돌림 → 모델 공간 뒤집기 → HeadFeatureRenderer.translate → HEAD 모드)을 풍선 위치를 머리 피벗으로 삼아.
 *  끈 = 오른손 높이(발 위 0.75칸, 몸 오른쪽 0.35)에서 풍선 아래(피벗 0.35칸 아래)까지 얇은 띠 두 장(십자), 흰 텍스처에 끈 색.
 * inWorld = 지금 월드를 그리는 중인지 (CosmeticFeatureRenderer 의 1인칭 숨김 판단용 — 인벤토리·옷장 미리보기는 월드 렌더 밖).
 */
public final class CosmeticWorld {
    private CosmeticWorld() {}

    public static volatile boolean inWorld;

    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");
    private static final int STRING = 0xFFE8E2D6;
    private static final ItemRenderState ITEM = new ItemRenderState();
    private static final Map<String, Vec3d> POS = new HashMap<>();
    private static final Map<String, Long> SEEN = new HashMap<>();
    private static long lastNs;

    static void render(WorldRenderContext ctx) {
        try {
            render0(ctx);
        } catch (Throwable t) {
            Cosmetics.fail("풍선 그리기", t);
        }
    }

    private static void render0(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !Cosmetics.any()) return;
        MatrixStack m = ctx.matrixStack();
        VertexConsumerProvider vcp = ctx.consumers();
        if (m == null || vcp == null) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(.2f, (now - lastNs) / 1e9f);
        lastNs = now;
        float alpha = 1 - (float) Math.exp(-dt / .18f);
        float tickDelta = ctx.tickCounter().getTickDelta(true);
        Vec3d cam = ctx.camera().getPos();
        long ms = System.currentTimeMillis();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p.isInvisible() || p.isSpectator()) continue;
            String name = p.getGameProfile().getName().toLowerCase(java.util.Locale.ROOT);
            String icon = Cosmetics.icon(name, Cosmetics.BALLOON);
            if (icon == null) continue;
            ItemRenderState shape = ItemIcons.head(icon);
            if (shape == null) continue;
            Vec3d feet = p.getLerpedPos(tickDelta);
            float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, p.prevBodyYaw, p.bodyYaw);
            double rad = Math.toRadians(bodyYaw);
            // 몸 방향 기준: 앞 = (-sin, cos), 오른쪽 = (-cos, -sin)
            double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad);
            Vec3d target = feet.add(rx * .45 - fx * .3, p.isInSneakingPose() ? 2.2 : 2.5, rz * .45 - fz * .3);
            Vec3d cur = POS.get(name);
            if (cur == null || cur.squaredDistanceTo(target) > 64) cur = target;
            else cur = cur.add(target.subtract(cur).multiply(alpha));
            POS.put(name, cur);
            SEEN.put(name, ms);
            double bob = Math.sin(ms / 600.0 + name.hashCode()) * .06;
            Vec3d at = cur.add(0, bob, 0);
            Vec3d hand = feet.add(rx * .35, .75, rz * .35);
            int light = WorldRenderer.getLightmapCoordinates(mc.world, BlockPos.ofFloored(at));

            {
                m.push();
                m.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
                // 갑옷거치대와 같은 순서: 몸 방향 → 모델 공간(뒤집기) → 머리 칸 변환
                m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - bodyYaw));
                m.scale(-1, -1, 1);
                try {
                    HeadFeatureRenderer.translate(m, HeadFeatureRenderer.HeadTransformation.DEFAULT);
                    shape.render(m, vcp, light, OverlayTexture.DEFAULT_UV);
                } finally {
                    m.pop();
                }
            }
            string(m, vcp, cam, hand, at.add(0, -.35, 0), light);
        }
        // 안 보인 지 10초 넘은 사람 위치는 지움
        for (Iterator<Map.Entry<String, Long>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (ms - e.getValue() > 10_000) { POS.remove(e.getKey()); it.remove(); }
        }
    }

    /** 끈: a → b 얇은 띠 두 장 (가로·세로로 엇갈려 어느 방향에서도 보이게) */
    private static void string(MatrixStack m, VertexConsumerProvider vcp, Vec3d cam, Vec3d a, Vec3d b, int light) {
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        m.push();
        m.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z);
        MatrixStack.Entry e = m.peek();
        Matrix4f mat = e.getPositionMatrix();
        float dx = (float) (b.x - a.x), dy = (float) (b.y - a.y), dz = (float) (b.z - a.z);
        float w = .012f;
        try {
            ribbon(vc, mat, e, dx, dy, dz, w, 0, 0, light);
            ribbon(vc, mat, e, dx, dy, dz, 0, 0, w, light);
        } finally {
            m.pop();
        }
    }

    private static void ribbon(VertexConsumer vc, Matrix4f mat, MatrixStack.Entry e, float dx, float dy, float dz, float ox, float oy, float oz, int light) {
        vc.vertex(mat, -ox, -oy, -oz).color(STRING).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, 0, 1, 0);
        vc.vertex(mat, ox, oy, oz).color(STRING).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, 0, 1, 0);
        vc.vertex(mat, dx + ox, dy + oy, dz + oz).color(STRING).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, 0, 1, 0);
        vc.vertex(mat, dx - ox, dy - oy, dz - oz).color(STRING).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, 0, 1, 0);
    }
}
