package school.magiccodex.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.ItemIconTextures;

/** One GUI path, after vanilla resolves predicates, custom model data and tint layers. */
@Mixin(DrawContext.class)
public abstract class ItemIconMixin {
    @Shadow @Final private ItemRenderState itemRenderState;

    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;IIII)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/item/ItemModelManager;update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;ZLnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V", shift = At.Shift.AFTER),
            cancellable = true, require = 1)
    private void magiccodex$highResIcon(LivingEntity entity, World world, ItemStack stack,
                                      int x, int y, int seed, int z, CallbackInfo ci) {
        if (ItemIconTextures.drawResolved((DrawContext) (Object) this, stack, x, y, z, itemRenderState)) ci.cancel();
    }
}
