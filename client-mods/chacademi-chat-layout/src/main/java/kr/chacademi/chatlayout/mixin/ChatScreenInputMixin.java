package kr.chacademi.chatlayout.mixin;
import kr.chacademi.chatlayout.ChatLayout;
import kr.chacademi.chatlayout.InputSuggestionAccess;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves the original field; native typing, history, selection and IME stay intact. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenInputMixin {
    @Shadow protected EditBox input;
    @Shadow private CommandSuggestions commandSuggestions;
    @Inject(method="init",at=@At("TAIL"),require=1)
    private void chacademi$openInsidePane(CallbackInfo ci){
        ChatLayout.beginInput();
        chacademi$placeInput();
    }
    @Inject(method="render",at=@At("HEAD"),require=1)
    private void chacademi$followSelectedPane(GuiGraphics graphics,int mx,int my,float delta,CallbackInfo ci){
        chacademi$placeInput();
    }
    private void chacademi$placeInput(){
        var bounds=ChatLayout.inputBounds();
        if(input==null||bounds==null)return;
        input.setX(bounds.x());input.setY(bounds.top());input.setHeight(bounds.height());
        if(input.getWidth()!=bounds.width()){
            input.setWidth(bounds.width());
            // Recalculate horizontal text scroll without changing selection or invoking the responder.
            input.setCursorPosition(input.getCursorPosition());
        }
        input.setBordered(false);
        input.setCanLoseFocus(false);
        input.setTextColor(0xFFEDE8D8);
        if(commandSuggestions!=null)((InputSuggestionAccess)(Object)commandSuggestions).chacademi$inputMoved();
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V",ordinal=0),require=1)
    private void chacademi$replaceBottomBar(GuiGraphics graphics,int x1,int y1,int x2,int y2,int color){
        if(!ChatLayout.hasManagedInput())graphics.fill(x1,y1,x2,y2,color);
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/EditBox;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),require=1)
    private void chacademi$renderAbovePanes(EditBox field,GuiGraphics graphics,int mx,int my,float delta){
        chacademi$placeInput();
        if(!ChatLayout.hasManagedInput()){
            field.render(graphics,mx,my,delta);
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0,0,ChatLayout.inputDepth());
        try{
            ChatLayout.paintInput(graphics);
            var clip=kr.chacademi.chatlayout.InputLayout.background(ChatLayout.inputBounds());
            graphics.enableScissor(clip.x()+1,clip.top()+1,clip.right()-1,clip.bottom()-1);
            try{
            field.render(graphics,mx,my,delta);
            }finally{graphics.disableScissor();}
        }finally{
            graphics.pose().popPose();
        }
    }
}
