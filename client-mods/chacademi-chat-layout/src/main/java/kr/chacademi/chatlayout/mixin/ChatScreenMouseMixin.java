package kr.chacademi.chatlayout.mixin;
import kr.chacademi.chatlayout.ChatLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Handles our controls before native command-suggestion click processing. */
@Mixin(value=ChatScreen.class,priority=1100)
public abstract class ChatScreenMouseMixin {
    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true,require=1)
    private void chacademi$mouseClicked(double mx,double my,int button,CallbackInfoReturnable<Boolean> cir) {
        if(ChatLayout.handleClick(mx,my,button))cir.setReturnValue(true);
    }
}
