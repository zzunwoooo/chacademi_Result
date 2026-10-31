package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.SkyState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 구름은 밝기(시간 따라감)는 그대로 두고 주황·자주빛만 살짝. 세상 밝기는 건드리지 않는다 (오너 요청). */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void chacaSky$clouds(float partialTick, CallbackInfoReturnable<Integer> cir) {
        float k = SkyState.amount();
        if (k <= 0f) return;
        int c = cir.getReturnValueI();
        int tinted = ARGB.color(ARGB.alpha(c), Math.min(255, (int) (ARGB.red(c) * 1.04f)),
                (int) (ARGB.green(c) * 0.84f), (int) (ARGB.blue(c) * 0.80f));
        cir.setReturnValue(ARGB.lerp(k, c, tinted));
    }
}
