package kr.chacademy.motion.broom;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import kr.chacademy.motion.broom.model.BroomModel;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * 빗자루 그리기. 엔티티 없이, 탄 플레이어의 보간 위치에 bbmodel 을 직접 그린다.
 * 그래서 남의 빗자루도 그 플레이어 움직임에 그대로 붙어 부드럽고, 1인칭에서도 내 빗자루 손잡이가 보인다.
 */
public final class BroomRenderer {
    /** 발 위치에서 좌석(p_pilot)까지 높이 (블록). 몸이 빗자루 위에 앉아 보이도록 맞춘 값. */
    public static final float SEAT_Y = 0.66f;
    /** 큐디치 매달림: 머리 위로 뻗은 손 높이까지 빗자루를 올리는 양 (발에서 손 ≈ 2.0블록). */
    public static final float HANG_LIFT = 1.36f;
    private static final double MAX_DIST_SQ = 96 * 96;

    private BroomRenderer() {}

    public static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (BroomRiders.riders().isEmpty() && BroomRiders.drops().isEmpty()) return;
        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = ctx.matrixStack() != null ? ctx.matrixStack() : new PoseStack();
        Vec3 cam = ctx.camera().getPosition();
        float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        Set<RenderType> used = new HashSet<>();

        for (var r : BroomRiders.riders().values()) {
            Player p = mc.level.getPlayerByUUID(r.id);
            if (p == null || p.isInvisible()) continue;
            Vec3 pos = p.getPosition(pt);
            if(r.approachFrom!=null){
                float t=Mth.clamp((r.age+pt)/10f,0,1);t=t*t*(3-2*t);
                pos=r.approachFrom.add(0,-SEAT_Y,0).lerp(pos,t);
            }
            if (pos.distanceToSqr(cam) > MAX_DIST_SQ) continue;
            BroomModel model = BroomModels.get(r.broom);
            if (model == null) continue;
            float bob = Mth.sin((p.tickCount + pt) * 0.157f) * 0.03f * (1f - Mth.clamp(r.speed / 0.6f, 0f, 1f));
            bob += r.hangLift(pt);
            float scale = r.summonScale(pt);
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.x, pos.y + SEAT_Y, pos.z));
            draw(pose, buffers, used, model, pos.x - cam.x, pos.y + SEAT_Y + bob - cam.y, pos.z - cam.z,
                    r.yaw(pt), r.pitch(pt), r.bank(pt) + r.spin(pt), scale, light);
        }
        for (var d : BroomRiders.drops()) {
            BroomModel model = BroomModels.get(d.broom);
            if (model == null) continue;
            Vec3 pos = d.pos(pt);
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos));
            draw(pose, buffers, used, model, pos.x - cam.x, pos.y - cam.y, pos.z - cam.z,
                    Mth.lerp(pt, d.pYaw, d.yaw), Mth.lerp(pt, d.pPitch, d.pitch), Mth.lerp(pt, d.pRoll, d.roll), d.scale(pt), light);
        }
        // 다른 엔티티와 같은 묶음에 넣었으므로, 늦게 그려지지 않도록 지금 그림
        if (buffers instanceof MultiBufferSource.BufferSource src) {
            for (RenderType t : used) src.endBatch(t);
        }
    }

    /**
     * @param x,y,z 좌석 위치 (카메라 기준)
     * @param yaw   빗자루가 향하는 방향 (마인크래프트 yaw)
     * @param pitch 양수 = 앞이 아래로
     * @param bank  양수 = 왼쪽이 아래로
     */
    private static void draw(PoseStack pose, MultiBufferSource buffers, Set<RenderType> used, BroomModel model,
                             double x, double y, double z, float yaw, float pitch, float bank, float scale, int light) {
        if (scale <= 0.01f) return;
        RenderType type = RenderType.entityCutout(BroomModels.texture(model));
        used.add(type);
        VertexConsumer vc = buffers.getBuffer(type);
        pose.pushPose();
        pose.translate(x, y, z);
        // 모델의 앞(-Z)이 플레이어가 보는 방향이 되도록
        pose.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        pose.mulPose(Axis.XP.rotationDegrees(-pitch));
        pose.mulPose(Axis.ZP.rotationDegrees(bank));
        float s = scale / 16f;
        pose.scale(s, s, s);
        pose.translate(-model.seat[0], -model.seat[1], -model.seat[2]);
        var last = pose.last();
        for (var q : model.quads) {
            float[] p = q.pos(), uv = q.uv(), n = q.normal();
            for (int i = 0; i < 4; i++) {
                vc.addVertex(last, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(uv[i * 2], uv[i * 2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, n[0], n[1], n[2]);
            }
        }
        pose.popPose();
    }
}
