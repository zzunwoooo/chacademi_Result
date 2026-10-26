package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chatwindows.TabSettings;
import com.ebicep.chatplus.features.chattabs.ChatTab;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import kr.chacademi.chatlayout.ChatLayout;
import com.ebicep.chatplus.hud.ChatRenderer;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=TabSettings.class,remap=false)
public abstract class TabSettingsMixin {
    // Native getClickedTab rejects Y against renderer.getUpdatedY before checking cached tab bounds.
    @Inject(method="getClickedTab",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$visibleTabHit(double mx,double my,CallbackInfoReturnable<ChatTab> cir){
        TabSettings settings=(TabSettings)(Object)this;
        if(ChatLayout.managesTabs(settings))cir.setReturnValue(ChatLayout.clickedManagedTab(settings,mx,my));
    }
    @Inject(method="renderTabs",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$selectedFill(GuiGraphics graphics,CallbackInfo ci){
        TabSettings settings=(TabSettings)(Object)this;
        if(!ChatLayout.shouldRenderTabs(settings)){ci.cancel();return;}
        ChatLayout.beforeTabsRender(settings,graphics);
    }
    @Redirect(method="renderTabs",at=@At(value="INVOKE",target="Lcom/ebicep/chatplus/hud/ChatRenderer;getInternalY()I"),remap=false,require=2)
    private int chacademi$afterInputRow(ChatRenderer renderer){
        return renderer.getInternalY()+ChatLayout.tabYOffset((TabSettings)(Object)this);
    }
    @Redirect(method="renderTabs",at=@At(value="INVOKE",target="Lcom/ebicep/chatplus/hud/ChatRenderer;getInternalX()I"),remap=false,require=1)
    private int chacademi$insetTabRow(ChatRenderer renderer){
        return renderer.getInternalX()+ChatLayout.tabXOffset((TabSettings)(Object)this);
    }
}
