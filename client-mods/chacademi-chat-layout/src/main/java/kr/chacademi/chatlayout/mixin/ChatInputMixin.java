package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.hud.ChatPlusScreenAdapter;
import kr.chacademi.chatlayout.ChatLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ChatPlusScreenAdapter.class,remap=false)
public abstract class ChatInputMixin {
    @Inject(method="handleChatInput",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$privateInput(ChatScreen screen,String text,CallbackInfoReturnable<Boolean> cir){
        if(kr.chacademi.chatlayout.PrivateConversations.send(text)||kr.chacademi.chatlayout.PartyChatInput.send(text)||kr.chacademi.chatlayout.ChannelChatInput.send(text))cir.setReturnValue(true);
    }
    @Inject(method="handleMouseClicked",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$click(ChatScreen screen,double mx,double my,int button,CallbackInfoReturnable<Boolean> cir) {
        if(button==1&&ChatLayout.handleClick(mx,my,button))cir.setReturnValue(true);
    }
    @Inject(method="handleMouseDragged",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$drag(ChatScreen screen,double mx,double my,int button,double dx,double dy,CallbackInfo ci) {
        if(ChatLayout.handleDrag(mx,my,button))ci.cancel();
    }
    @Inject(method="handleMouseReleased",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void chacademi$release(ChatScreen screen,double mx,double my,int button,CallbackInfoReturnable<Boolean> cir) {
        if(ChatLayout.handleRelease(mx,my,button))cir.setReturnValue(true);
    }
    @Inject(method="handleRemoved",at=@At("HEAD"),remap=false,require=1)
    private void chacademi$removed(ChatScreen screen,CallbackInfo ci) { ChatLayout.handleRemoved(); }
}
