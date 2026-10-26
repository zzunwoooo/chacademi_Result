package school.magiccodex.mixin;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(DrawContext.class)
public abstract class ItemTooltipMixin {
    @Inject(method="drawItemTooltip",at=@At("HEAD"),cancellable=true)
    private void magiccodex$itemTooltip(TextRenderer font,ItemStack stack,int x,int y,CallbackInfo ci){
        if(school.magiccodex.client.ItemTooltipRenderer.render((DrawContext)(Object)this,font,stack,x,y))ci.cancel();
    }
}
