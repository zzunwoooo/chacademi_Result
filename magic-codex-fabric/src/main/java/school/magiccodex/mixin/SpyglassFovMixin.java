package school.magiccodex.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import school.magiccodex.client.SpyglassZoom;

/** 접이식 망원경 아티팩트: 사용 키를 누르고 있는 동안 바닐라 망원경과 같은 시야 배율(0.1). 이름이 바뀐 버전에서는 조용히 빠진다(require 0). */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class SpyglassFovMixin {
    @Inject(method = "getFovMultiplier", at = @At("RETURN"), cancellable = true, require = 0)
    private void magiccodex$foldingSpyglass(boolean firstPerson, float fovEffectScale, CallbackInfoReturnable<Float> cir) {
        if (firstPerson && SpyglassZoom.active()) cir.setReturnValue(SpyglassZoom.FOV);
    }
}
