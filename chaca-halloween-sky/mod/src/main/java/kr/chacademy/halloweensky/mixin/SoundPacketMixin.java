package kr.chacademy.halloweensky.mixin;

import kr.chacademy.halloweensky.HalloweenSkyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** chaca_sky:* 소리 = 하늘 신호. 재생하지 않고 가로챈다. */
@Mixin(ClientPacketListener.class)
abstract class SoundPacketMixin {
    @Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
    private void chacaSky$signal(ClientboundSoundPacket packet, CallbackInfo ci) {
        // 네트워크 스레드에서 한 번, 메인 스레드에서 한 번 불린다. 메인 스레드에서만 처리.
        if (!Minecraft.getInstance().isSameThread()) return;
        if (HalloweenSkyClient.handleSignal(packet.getSound().value().location())) ci.cancel();
    }
}
