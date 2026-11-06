package kr.kkaegul.client.mixin;

import kr.kkaegul.client.mc.KkaegulClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 채팅창을 연 상태에서도 HUD(▼ 메뉴·우편·다이아 칸)를 누를 수 있게 */
@Mixin(ChatScreen.class)
public abstract class ChatClickMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void kkaegul$hudClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        KkaegulClient k = KkaegulClient.get();
        if (button == 0 && k != null && k.clickHud(mx, my)) cir.setReturnValue(true);
    }
}
