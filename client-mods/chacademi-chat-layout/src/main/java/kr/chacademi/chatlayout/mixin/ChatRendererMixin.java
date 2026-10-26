package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chatwindows.ChatWindow;
import com.ebicep.chatplus.features.chatwindows.GeneralSettings;
import kr.chacademi.chatlayout.IdleFade;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.ebicep.chatplus.hud.ChatRenderer;
import kr.chacademi.chatlayout.ChatLayout;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ChatRenderer.class,remap=false)
public abstract class ChatRendererMixin {
    @Redirect(method="render",at=@At(value="INVOKE",target="Lcom/ebicep/chatplus/hud/ChatManager;isChatFocused()Z"),remap=false,require=1)
    private boolean chacademi$altHistory(com.ebicep.chatplus.hud.ChatManager manager){
        return manager.isChatFocused()||kr.chacademi.chatlayout.InteractionMode.isMagicCursor(net.minecraft.client.Minecraft.getInstance().screen);
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lcom/ebicep/chatplus/features/chatwindows/GeneralSettings;getUpdatedTextOpacity()F"),remap=false,require=1)
    private float chacademi$idleText(GeneralSettings settings,ChatWindow window,GuiGraphics graphics,int ticks,int mx,int my){
        return settings.getUpdatedTextOpacity()*ChatLayout.windowOpacity(window);
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lcom/ebicep/chatplus/features/chatwindows/GeneralSettings;getUpdatedBackgroundColor()I"),remap=false,require=1)
    private int chacademi$idleBackground(GeneralSettings settings,ChatWindow window,GuiGraphics graphics,int ticks,int mx,int my){
        // The addon draws one controllable pane background; native row fills must not compound its alpha.
        return ChatLayout.managesWindow(window)?0:IdleFade.color(settings.getUpdatedBackgroundColor(),ChatLayout.windowOpacity(window));
    }
    @Inject(method="render",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$before(ChatWindow window,GuiGraphics graphics,int ticks,int mx,int my,CallbackInfo ci) {
        if(ChatLayout.beforeWindowRender(window,graphics,mx,my))ci.cancel();
    }
    @Inject(method="render",at=@At("RETURN"),remap=false,require=1)
    private void chacademi$after(ChatWindow window,GuiGraphics graphics,int ticks,int mx,int my,CallbackInfo ci) {
        ChatLayout.afterWindowRender(window,graphics,mx,my);
    }
}
