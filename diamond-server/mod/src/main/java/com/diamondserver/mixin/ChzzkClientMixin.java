package com.diamondserver.mixin;

import com.diamondserver.client.BroadcastVote;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 후원 모드(Donation)의 치지직 연결이 SYSTEM 신호를 받을 때 그 내용을 엿본다 (connected 신호의 세션 키로 채팅 구독을 걸기 위해).
 * 후원 모드가 없으면(@Pseudo) 아무 일도 하지 않는다. 원래 동작은 그대로 진행된다.
 */
@Pseudo
@Mixin(targets = "bot.colla.chzzk.api.ChzzkClient", remap = false)
public abstract class ChzzkClientMixin {
    @Inject(method = "handleSystemMessage", at = @At("HEAD"), remap = false, require = 0)
    private void diamondserver$onSystem(Object[] args, CallbackInfo ci) {
        BroadcastVote.onChzzkSystem(this, args);
    }
}
