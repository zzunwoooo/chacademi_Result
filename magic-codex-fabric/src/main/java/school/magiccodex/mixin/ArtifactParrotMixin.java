package school.magiccodex.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.passive.ParrotEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.ArtifactFxClient;

/**
 * 수다 앵무새 (아티팩트, 2026-10-26): 장착한 플레이어의 오른쪽 어깨에 바닐라 어깨 앵무새를 그린다 (서버 엔티티 없음,
 * 진짜 어깨 앵무새가 이미 있으면 건드리지 않음). require = 0: 대상이 바뀌어도 모드 전체가 멈추지 않고 앵무새만 안 보인다.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class ArtifactParrotMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void magiccodex$artifactParrot(AbstractClientPlayerEntity player, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (state.rightShoulderParrotVariant == null && ArtifactFxClient.parrot(player.getUuid())) state.rightShoulderParrotVariant = ParrotEntity.Variant.RED_BLUE;
    }
}
