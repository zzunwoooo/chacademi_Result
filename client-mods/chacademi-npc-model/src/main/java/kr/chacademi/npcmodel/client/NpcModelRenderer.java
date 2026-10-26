package kr.chacademi.npcmodel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import kr.chacademi.npcmodel.NpcModelClient;
import kr.chacademi.npcmodel.core.ModelData;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.ResourceLocation;

/**
 * 변형된 정점을 바닐라 엔티티 버퍼로 그린다 (블록·하늘빛, 깊이, 피격 붉은빛, 셰이더팩 엔티티 처리 그대로).
 * 위치는 엔티티 렌더 위치(발밑), 방향은 서버 고정 방향(있으면) 아니면 몸 회전(bodyRot). 모델 정면 +Z 가 마인크래프트 yaw 0(남쪽)에 맞는다.
 */
public final class NpcModelRenderer {
    private static long frameNanos;
    private static int frameUpdates;

    private NpcModelRenderer() {
    }

    public static void beginFrame() {
        frameNanos = 0;
        frameUpdates = 0;
    }

    public static void render(NpcInstance inst, LivingEntityRenderState state, NpcModelState ext, PoseStack ps,
                              MultiBufferSource buffers, int light) {
        ModelManager.Settings s = NpcModelClient.models().settings();
        double distance = ext.chacademi$distance();
        int lod = inst.lodFor(distance);
        boolean budgetOk = frameNanos < (long) (s.frameBudgetMs * 1_000_000L);
        long t0 = System.nanoTime();
        if (inst.update(state.ageInTicks / 20f, ext.chacademi$speed(), distance, lod, s, budgetOk)) {
            frameUpdates++;
        }
        frameNanos += System.nanoTime() - t0;

        ModelData m = inst.data(lod);
        NpcInstance.Skinned sk = inst.skinned(lod);
        if (!sk.valid) {
            return;
        }
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0f);
        float scale = inst.totalScale();
        ResourceLocation[] tex = inst.model().textures.get(lod);

        ps.pushPose();
        try {
        // 고정 방향이 있으면 그 각도 (동상 등: 클라이언트 몸 회전 보정·재추적에 흔들리지 않게), 없으면 몸 방향
        float yaw = Float.isNaN(inst.fixedYaw()) ? state.bodyRot : inst.fixedYaw();
        ps.mulPose(Axis.YP.rotationDegrees(-yaw));
        ps.scale(scale, scale, scale);
        ps.translate(0, -m.restMinY, 0);
        PoseStack.Pose last = ps.last();
        for (int i = 0; i < m.meshNodes.length; i++) {
            ModelData.MeshNode mn = m.meshNodes[i];
            for (int p = 0; p < mn.primitives.length; p++) {
                ModelData.Primitive pr = mn.primitives[p];
                ModelData.Material mat = pr.material >= 0 ? m.materials[pr.material] : null;
                int img = mat == null || mat.image < 0 ? tex.length - 1 : mat.image;
                boolean cull = mat != null && !mat.doubleSided;
                RenderType rt = mat != null && mat.alphaMode == ModelData.ALPHA_BLEND
                        ? NpcRenderTypes.translucent(tex[img], cull) : NpcRenderTypes.cutout(tex[img], cull);
                int r = 255, g = 255, b = 255, a = 255;
                if (mat != null) {
                    r = (int) (mat.baseColor[0] * 255);
                    g = (int) (mat.baseColor[1] * 255);
                    b = (int) (mat.baseColor[2] * 255);
                    a = (int) (mat.baseColor[3] * 255);
                }
                emit(buffers.getBuffer(rt), last, pr, sk.pos[i][p], sk.nrm[i][p], r, g, b, a, overlay, light);
            }
        }
        } finally {
            ps.popPose(); // 예외가 나도 포즈 스택 균형 유지
        }
    }

    /** 삼각형을 TRIANGLES 버퍼에 넣는다. */
    private static void emit(VertexConsumer vc, PoseStack.Pose pose, ModelData.Primitive pr, float[] pos, float[] nrm,
                             int r, int g, int b, int a, int overlay, int light) {
        int[] idx = pr.indices;
        float[] uv = pr.uvs;
        for (int t = 0; t < idx.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                int v = idx[t + k];
                vc.addVertex(pose, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                        .setColor(r, g, b, a)
                        .setUv(uv[v * 2], uv[v * 2 + 1])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]);
            }
        }
    }

    public static int frameUpdates() {
        return frameUpdates;
    }

    public static long frameNanos() {
        return frameNanos;
    }
}
