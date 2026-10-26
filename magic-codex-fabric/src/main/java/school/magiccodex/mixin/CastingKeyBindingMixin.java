package school.magiccodex.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import school.magiccodex.client.CastingClient;

@Mixin(KeyBinding.class)
public abstract class CastingKeyBindingMixin {
    @Shadow private int timesPressed;
    @Inject(method="isPressed",at=@At("HEAD"),cancellable=true)
    private void magiccodex$blockHeldVanillaKey(CallbackInfoReturnable<Boolean> ci) {
        if(CastingClient.suppressed((KeyBinding)(Object)this)) ci.setReturnValue(false);
    }
    @Inject(method="wasPressed",at=@At("HEAD"),cancellable=true)
    private void magiccodex$blockQueuedVanillaKey(CallbackInfoReturnable<Boolean> ci) {
        if(CastingClient.suppressed((KeyBinding)(Object)this)) { timesPressed=0; ci.setReturnValue(false); }
    }
}
