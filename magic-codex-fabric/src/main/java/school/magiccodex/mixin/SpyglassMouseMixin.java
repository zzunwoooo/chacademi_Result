package school.magiccodex.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import school.magiccodex.client.SpyglassZoom;

/** 접이식 망원경 아티팩트: 확대 중 마우스 감도를 바닐라 망원경처럼 낮춘다. 대상이 없으면 조용히 빠진다(require 0, 확대 자체는 그대로). */
@Mixin(Mouse.class)
public abstract class SpyglassMouseMixin {
    @ModifyExpressionValue(method = "updateMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingSpyglass()Z"), require = 0)
    private boolean magiccodex$foldingSpyglass(boolean original) {
        return original || SpyglassZoom.active();
    }
}
