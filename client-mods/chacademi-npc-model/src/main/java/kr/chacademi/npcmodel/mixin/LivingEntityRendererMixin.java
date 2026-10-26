package kr.chacademi.npcmodel.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.chacademi.npcmodel.client.NpcInstance;
import kr.chacademi.npcmodel.client.NpcModelRenderer;
import kr.chacademi.npcmodel.client.NpcModelState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 생물 렌더 시작에서 3D 모델을 그린다. 이후 바닐라 처리는 그대로 진행되고(몸은 위에서 숨겼음), 이름표도 바닐라가 그린다.
 * 포즈 스택은 이미 엔티티 발밑으로 옮겨진 상태.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void chacademi$render(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
        NpcModelState ext = (NpcModelState) (Object) state;
        NpcInstance inst = ext.chacademi$npc();
        if (inst != null) {
            try {
                NpcModelRenderer.render(inst, state, ext, poseStack, buffers, light);
            } catch (Throwable t) {
                // 게임을 멈추지 않는다: 이 모델을 실패로 두고 다음 프레임부터 원래 외형
                kr.chacademi.npcmodel.NpcModelClient.bindings().failModel(inst.model().entry.id(), t);
            }
        }
    }
}
