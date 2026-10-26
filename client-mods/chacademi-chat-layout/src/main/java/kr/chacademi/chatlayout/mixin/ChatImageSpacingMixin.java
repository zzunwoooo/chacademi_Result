package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chatwindows.GeneralSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Use ChatPlus native dimensions so scissor, scroll, selection and hover all share the same row height. */
@Mixin(value=GeneralSettings.class,remap=false)
public abstract class ChatImageSpacingMixin {
 @Inject(method="getLineSpacing",at=@At("RETURN"),cancellable=true,remap=false,require=1)
 private void chacademi$imageRow(CallbackInfoReturnable<Float> cir){cir.setReturnValue(Math.max(0.5f,cir.getReturnValue()));}
}
