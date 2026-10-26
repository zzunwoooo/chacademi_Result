package school.magiccodex.mixin;
import java.util.*;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(HandledScreen.class)
public abstract class HandledItemTooltipMixin {
    @Shadow protected Slot focusedSlot;
    @Redirect(method="drawMouseoverTooltip",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/DrawContext;drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/util/Identifier;)V"))
    private void magiccodex$hover(DrawContext c,TextRenderer font,List<Text> text,Optional<TooltipData> data,int x,int y,Identifier style){
        if(focusedSlot==null||!school.magiccodex.client.ItemTooltipRenderer.render(c,font,focusedSlot.getStack(),x,y,text))c.drawTooltip(font,text,data,x,y,style);
    }
}
