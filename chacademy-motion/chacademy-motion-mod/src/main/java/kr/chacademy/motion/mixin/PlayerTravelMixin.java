package kr.chacademy.motion.mixin;

import kr.chacademy.motion.broom.BroomFlight;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 빗자루를 타는 동안 내 캐릭터의 바닐라 이동 계산(걷기·비행)을 빗자루 물리로 바꾼다.
 * 다른 플레이어에게는 영향 없음 (그들의 위치는 서버가 보내 준 값으로 보간됨).
 */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void chacademy$broomTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer local && BroomFlight.travel(local)) {
            ci.cancel();
        }
    }
}
