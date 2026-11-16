package com.diamondserver.mixin;

import com.diamondserver.client.St;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 거꾸로 세상: 서버가 정해 준 시간 동안 앞뒤·좌우 이동 조작을 반대로 뒤집는다. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void diamondserver$reverse(CallbackInfo ci) {
        if (System.currentTimeMillis() >= St.reverseUntil) return;
        Input self = (Input) (Object) this;
        self.movementForward = -self.movementForward;
        self.movementSideways = -self.movementSideways;
    }
}
