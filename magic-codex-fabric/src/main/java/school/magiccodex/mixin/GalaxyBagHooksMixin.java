package school.magiccodex.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.GalaxyBagScreen;

/**
 * 아공간 배낭 only. Vanilla draws the slot highlight squares and the carried stack on the 16px vanilla grid;
 * the bag draws both on its own layout. Other screens are untouched.
 * require=0: if a mapping changes, the bag still works with vanilla highlight squares.
 */
@Mixin(HandledScreen.class)
public abstract class GalaxyBagHooksMixin {
    @Inject(method = "drawSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void magiccodex$bagHighlightBack(DrawContext context, CallbackInfo ci) {
        if ((Object) this instanceof GalaxyBagScreen) ci.cancel();
    }

    @Inject(method = "drawSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void magiccodex$bagHighlightFront(DrawContext context, CallbackInfo ci) {
        if ((Object) this instanceof GalaxyBagScreen) ci.cancel();
    }

    @Inject(method = "drawItem(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void magiccodex$bagCursorStack(DrawContext context, ItemStack stack, int x, int y, String amount, CallbackInfo ci) {
        if ((Object) this instanceof GalaxyBagScreen screen) { screen.drawCursorStack(context, stack, x, y, amount); ci.cancel(); }
    }
}
