package school.magiccodex.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.PlayerHudClient;

@Mixin(InGameHud.class)
public abstract class PlayerHudMixin {
    @Inject(method="render",at=@At("TAIL"))
    private void magiccodex$discovery(DrawContext ctx,RenderTickCounter ticks,CallbackInfo ci){school.magiccodex.client.DiscoveryClient.render(ctx);}
    @Inject(method="render",at=@At("HEAD"))
    private void magiccodex$screenVfx(DrawContext ctx,RenderTickCounter ticks,CallbackInfo ci){
        school.magiccodex.client.TemperatureVfxRenderer.render(ctx);
    }
    @Inject(method="renderHotbar",at=@At("HEAD"),cancellable=true)
    private void magiccodex$hotbar(DrawContext ctx,RenderTickCounter ticks,CallbackInfo ci){
        if(PlayerHudClient.active()){PlayerHudClient.hotbar(ctx);ci.cancel();}
    }
    @Inject(method="renderChat",at=@At("HEAD"),cancellable=true)
    private void magiccodex$npcChat(DrawContext ctx,RenderTickCounter ticks,CallbackInfo ci){
        if(net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof school.magiccodex.client.NpcTalkScreen)ci.cancel();
    }
    @Redirect(method="renderHeldItemTooltip",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/DrawContext;drawTextWithBackground(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIII)I"))
    private int magiccodex$itemName(DrawContext context,net.minecraft.client.font.TextRenderer renderer,net.minecraft.text.Text name,int x,int y,int width,int color){
        if(!PlayerHudClient.active())return context.drawTextWithBackground(renderer,name,x,y,width,color);
        return school.magiccodex.client.HeldItemNameHud.draw(context,renderer,name,color);
    }
    // Air, mount health and status effects remain available. HUD off restores all vanilla bars.
    @Inject(method={"renderHealthBar","renderFood","renderExperienceBar","renderExperienceLevel"},at=@At("HEAD"),cancellable=true)
    private void magiccodex$replaceVitals(CallbackInfo ci){
        if(PlayerHudClient.active())ci.cancel();
    }
    @Inject(method="renderArmor",at=@At("HEAD"),cancellable=true)
    private static void magiccodex$hideArmor(CallbackInfo ci){
        if(PlayerHudClient.active())ci.cancel();
    }
}
