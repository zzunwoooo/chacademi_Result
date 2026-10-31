package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyState;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 안개 밝기는 그대로(시간 따라감), 색만 주황빛으로 살짝. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(method = "computeFogColor", at = @At("RETURN"))
    private static void chacaSky$fog(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                     float darkenWorld, CallbackInfoReturnable<Vector4f> cir) {
        float k = SkyState.amount();
        if (k <= 0f) return;
        Vector4f c = cir.getReturnValue();
        c.x = Math.min(1f, c.x * (1f + 0.12f * k));
        c.y *= 1f - 0.10f * k;
        c.z *= 1f - 0.22f * k;
    }
}
