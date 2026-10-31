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

/** 먼 지형이 녹아드는 안개 색을 할로윈 지평선(어두운 자주·주황)으로. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Inject(method = "computeFogColor", at = @At("RETURN"))
    private static void chacaSky$fog(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                     float darkenWorld, CallbackInfoReturnable<Vector4f> cir) {
        float k = SkyState.amount();
        if (k <= 0f) return;
        Vector4f c = cir.getReturnValue();
        c.x += (0.42f - c.x) * k;
        c.y += (0.14f - c.y) * k;
        c.z += (0.24f - c.z) * k;
    }
}
