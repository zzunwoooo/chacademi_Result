package kr.chacademi.chatlayout.mixin;
import kr.chacademi.chatlayout.ChatLayout;
import kr.chacademi.chatlayout.EditGesturePolicy;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observe modifier releases without cancelling, remapping or otherwise changing gameplay key handling. */
@Mixin(KeyboardHandler.class)
public abstract class EditModifierKeyMixin {
    @Inject(method="keyPress",at=@At("HEAD"),require=1)
    private void chacademi$endEdit(long window,int key,int scanCode,int action,int modifiers,CallbackInfo ci){
        if(EditGesturePolicy.modifierReleased(key,action))ChatLayout.editModifierReleased();
    }
}
