package kr.kkaegul.client.mixin;

import kr.kkaegul.client.mc.KkaegulClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 머리 위 이름표를 서버 닉네임(한글)으로 */
@Mixin(PlayerEntity.class)
public abstract class PlayerNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void kkaegul$nickname(CallbackInfoReturnable<Text> cir) {
        Text nick = KkaegulClient.displayName((PlayerEntity) (Object) this);
        if (nick != null) cir.setReturnValue(nick);
    }
}
