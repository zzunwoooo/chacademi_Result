package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyPainter;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 바닐라 하늘 패스(addSkyPass 안의 람다)가 끝난 직후, 지형보다 먼저 할로윈 하늘을 덧그린다. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSkyMixin {
    @Inject(method = "method_62215", at = @At("TAIL"))
    private void chacaSky$paint(FogParameters fog, DimensionSpecialEffects.SkyType skyType, float partialTick,
                                DimensionSpecialEffects effects, CallbackInfo ci) {
        if (skyType == DimensionSpecialEffects.SkyType.OVERWORLD) SkyPainter.render();
    }
}
