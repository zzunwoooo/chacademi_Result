package com.diamondserver.mixin;

import com.diamondserver.client.St;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 머리 위 이름표: 서버가 알려 준 한글 닉네임으로 보여 준다 (계정 이름은 감춘다). */
@Mixin(PlayerEntity.class)
public abstract class PlayerNameMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true, require = 0)
    private void diamondserver$nameTag(CallbackInfoReturnable<Text> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!self.getWorld().isClient || St.names.isEmpty()) return;
        String nick = St.names.get(self.getUuidAsString());
        if (nick != null && !nick.isEmpty()) cir.setReturnValue(Text.literal(nick));
    }
}
