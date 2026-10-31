package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 할로윈 하늘인 만큼 세상 밝기를 저녁처럼 낮추고, 바닐라 하늘·구름 색을 보랏빛으로. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "getSkyDarken", at = @At("RETURN"), cancellable = true)
    private void chacaSky$darken(float partialTick, CallbackInfoReturnable<Float> cir) {
        float k = SkyState.amount();
        float v = cir.getReturnValueF();
        if (k > 0f && v > 0.42f) cir.setReturnValue(v + (0.42f - v) * k);
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void chacaSky$sky(Vec3 pos, float partialTick, CallbackInfoReturnable<Integer> cir) {
        float k = SkyState.amount();
        if (k > 0f) cir.setReturnValue(ARGB.lerp(k, cir.getReturnValueI(), 0xFF1E0E34));
    }

    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void chacaSky$clouds(float partialTick, CallbackInfoReturnable<Integer> cir) {
        float k = SkyState.amount();
        if (k > 0f) cir.setReturnValue(ARGB.lerp(k, cir.getReturnValueI(), 0xFF5A3048));
    }
}
