package kr.chacademi.npcmodel.mixin;

import kr.chacademi.npcmodel.NpcModelClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 화면 밖 판정(컬링): 3D 모델이 붙은 NPC는 플레이어 히트박스(0.6×1.8) 대신 모델 크기 상자로 판정한다.
 * 안 하면 10배 동상처럼 큰 모델은 발밑 히트박스가 화면 밖으로 나가는 순간 머리까지 통째로 사라진다.
 * 모델이 아직 없거나 일반 엔티티면 바닐라 판정 그대로.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullMixin {

    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void chacademi$modelCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof AbstractClientPlayer) || NpcModelClient.bindings() == null) {
            return;
        }
        double top;
        try {
            top = NpcModelClient.bindings().topHeight(entity);
        } catch (RuntimeException ex) {
            return;
        }
        if (!(top > 0)) {
            return; // 모델 준비 전 → 바닐라 (처음 보일 때 인스턴스가 만들어짐)
        }
        // 거리 판정: 바닐라(히트박스 크기 × 64 × 엔티티 거리 설정)를 모델 키 기준으로
        double size = Math.max(entity.getBoundingBox().getSize(), top);
        double far = size * 64.0 * Entity.getViewScale();
        if (entity.distanceToSqr(camX, camY, camZ) >= far * far) {
            cir.setReturnValue(false);
            return;
        }
        double half = Math.max(0.5, top * 0.4); // 폭은 모르니 넉넉히 (팔·지팡이·받침대)
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        AABB box = new AABB(x - half, y - 0.5, z - half, x + half, y + top + 0.5, z + half);
        cir.setReturnValue(frustum.isVisible(box));
    }
}
