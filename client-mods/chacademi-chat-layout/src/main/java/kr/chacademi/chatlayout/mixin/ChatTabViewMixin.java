package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chattabs.ChatTab;
import kr.chacademi.chatlayout.ChatLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Retains a moved tab's scroll through native reflow without changing its history. */
@Mixin(value=ChatTab.class,remap=false)
public abstract class ChatTabViewMixin {
    @Inject(method="rescaleChat",at=@At("RETURN"),remap=false,require=1)
    private void chacademi$rescale(CallbackInfo ci){ChatLayout.afterTabRescale((ChatTab)(Object)this);}
    @Inject(method="refreshDisplayMessages",at=@At("RETURN"),remap=false,require=1)
    private void chacademi$refresh(CallbackInfo ci){ChatLayout.afterTabRefresh((ChatTab)(Object)this);}
}
