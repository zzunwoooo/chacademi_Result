package kr.chacademi.chatlayout.mixin;

import kr.chacademi.chatlayout.ChatLayout;
import kr.chacademi.chatlayout.InputLayout;
import kr.chacademi.chatlayout.InputSuggestionAccess;
import kr.chacademi.chatlayout.LayoutMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Re-anchor native suggestions without re-requesting completions or losing their selected entry. */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin implements InputSuggestionAccess {
    @Shadow @Final private Screen screen;
    @Shadow @Final private EditBox input;
    @Shadow private CommandSuggestions.SuggestionsList suggestions;
    @Shadow @Final private net.minecraft.client.gui.Font font;
    @Shadow @Final private java.util.List<net.minecraft.util.FormattedCharSequence> commandUsage;
    @Shadow @Final private int fillColor;
    @Shadow private int commandUsagePosition;
    @Shadow private int commandUsageWidth;
    @Shadow @Final @Mutable private int suggestionLineLimit;
    @Unique private int chacademi$configuredRows;
    @Unique private boolean chacademi$synced;
    @Unique private int chacademi$inputX,chacademi$inputY;
    @Unique private CommandSuggestions.SuggestionsList chacademi$lastList;

    @Unique private boolean chacademi$active(){
        return screen==Minecraft.getInstance().screen&&ChatLayout.hasManagedInput();
    }

    @Override public void chacademi$inputMoved(){
        if(!chacademi$active()){
            chacademi$synced=false;chacademi$lastList=null;return;
        }
        if(chacademi$configuredRows==0)chacademi$configuredRows=Math.max(1,suggestionLineLimit);
        suggestionLineLimit=InputLayout.suggestionRows(chacademi$configuredRows,screen.height);
        int dx=chacademi$synced?input.getX()-chacademi$inputX:0;
        int dy=chacademi$synced?input.getY()-chacademi$inputY:0;
        if(chacademi$synced)commandUsagePosition+=dx;
        chacademi$fitUsage();
        if(suggestions!=null){
            Rect2i rect=((SuggestionsListAccessor)(Object)suggestions).chacademi$getRect();
            boolean existing=suggestions==chacademi$lastList;
            int count=((SuggestionsListAccessor)(Object)suggestions).chacademi$getSuggestions().size();
            var bounds=InputLayout.anchoredPopup(
                    new LayoutMath.Rect(input.getX(),input.getY(),input.getWidth(),input.getHeight()),
                    rect.getX()+(existing?dx:0),rect.getWidth(),
                    Math.min(count,chacademi$configuredRows),screen.width,screen.height);
            suggestionLineLimit=Math.max(1,bounds.height()/12);
            rect.setPosition(bounds.x(),bounds.top());
            rect.setWidth(bounds.width());rect.setHeight(bounds.height());
        }
        chacademi$inputX=input.getX();chacademi$inputY=input.getY();
        chacademi$lastList=suggestions;chacademi$synced=true;
    }

    @Unique private void chacademi$fitUsage(){
        int width=Math.max(1,input.getInnerWidth());
        commandUsageWidth=Math.max(1,Math.min(commandUsageWidth,width));
        commandUsagePosition=Math.max(input.getX(),
                Math.min(commandUsagePosition,input.getX()+width-commandUsageWidth));
    }

    @Inject(method="renderUsage",at=@At("HEAD"),cancellable=true,require=1)
    private void chacademi$anchoredUsage(GuiGraphics graphics,CallbackInfo ci){
        if(!chacademi$active())return;
        chacademi$fitUsage();
        if(!commandUsage.isEmpty()){
            var bounds=InputLayout.anchoredPopup(
                    new LayoutMath.Rect(input.getX(),input.getY(),input.getWidth(),input.getHeight()),
                    commandUsagePosition-1,commandUsageWidth+2,commandUsage.size(),screen.width,screen.height);
            graphics.enableScissor(bounds.x(),bounds.top(),bounds.right(),bounds.bottom());
            try{
                int rows=Math.min(commandUsage.size(),bounds.height()/12);
                for(int i=0;i<rows;i++){
                    int y=bounds.top()+i*12;
                    graphics.fill(bounds.x(),y,bounds.right(),y+12,fillColor);
                    graphics.drawString(font,commandUsage.get(i),bounds.x()+1,y+2,-1);
                }
            }finally{graphics.disableScissor();}
        }
        ci.cancel();
    }
    @Inject(method="updateUsageInfo",at=@At("RETURN"),require=1)
    private void chacademi$localUsageWidth(CallbackInfo ci){
        if(chacademi$active())chacademi$fitUsage();
    }
    @Inject(method="showSuggestions",at=@At("RETURN"),require=1)
    private void chacademi$fitNewList(boolean narrate,CallbackInfo ci){chacademi$inputMoved();}
    @Inject(method="render",at=@At("HEAD"),require=1)
    private void chacademi$keepListVisible(GuiGraphics graphics,int mx,int my,CallbackInfo ci){chacademi$inputMoved();}
    @Inject(method="mouseClicked",at=@At("HEAD"),require=1)
    private void chacademi$moveBeforeClick(double mx,double my,int button,CallbackInfoReturnable<Boolean> cir){chacademi$inputMoved();}
    @Inject(method="keyPressed",at=@At("HEAD"),require=1)
    private void chacademi$moveBeforeKey(int key,int scan,int modifiers,CallbackInfoReturnable<Boolean> cir){chacademi$inputMoved();}
}
