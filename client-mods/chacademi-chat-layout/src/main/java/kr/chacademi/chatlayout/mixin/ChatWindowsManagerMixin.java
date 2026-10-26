package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chatwindows.ChatWindowsManager;
import com.ebicep.chatplus.features.chatwindows.ChatWindow;
import kr.chacademi.chatlayout.ChatLayout;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ChatWindowsManager.class,remap=false)
public abstract class ChatWindowsManagerMixin {
    @Inject(method="renderAll",at=@At("HEAD"),remap=false,require=1)
    private void chacademi$layout(GuiGraphics graphics,int ticks,int mx,int my,CallbackInfo ci){ChatLayout.beforeRender(graphics);}
    @Inject(method="renderAll",at=@At("RETURN"),remap=false,require=1)
    private void chacademi$dragPreview(GuiGraphics graphics,int ticks,int mx,int my,CallbackInfo ci){ChatLayout.afterRender(graphics,mx,my);}
    @Inject(method="insideWindow",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$hit(ChatWindow window,double mx,double my,CallbackInfoReturnable<Boolean> cir){
        Boolean hit=ChatLayout.managedHitArea(window,mx,my);if(hit!=null)cir.setReturnValue(hit);
    }
}
