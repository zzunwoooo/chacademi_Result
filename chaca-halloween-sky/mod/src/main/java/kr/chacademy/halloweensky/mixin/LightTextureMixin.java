package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyState;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 셰이더 안 켠 사람: 하늘빛(SkyLightColor)에 주황빛만 살짝. 밝기(시간 따라감)는 그대로. */
@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/AbstractUniform;set(Lorg/joml/Vector3f;)V"), require = 0)
    private Vector3f chacaSky$tint(Vector3f skyLight) {
        float k = SkyState.amount();
        if (k <= 0f) return skyLight;
        return new Vector3f(skyLight).mul(1f + 0.06f * k, 1f - 0.07f * k, 1f - 0.20f * k);
    }
}
