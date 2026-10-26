package school.magiccodex.mixin;

import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.HiResItemState;
import school.magiccodex.client.ItemIconTextures;

/**
 * Every item render state (first person, living-entity hands/head, item entities, frames) is
 * filled by these update overloads (updateFor* delegate to them).
 * Tags the state with the high-res item model after vanilla filled it (ItemRenderStateMixin draws it).
 */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {
    @Inject(method = "update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;ZLnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V",
            at = @At("RETURN"), require = 0)
    private void magiccodex$tagHiRes(ItemRenderState state, ItemStack stack, ModelTransformationMode mode, boolean leftHand,
                                     World world, LivingEntity entity, int seed, CallbackInfo ci) {
        var duck = (HiResItemState) state;
        duck.magiccodex$setHiResModel(ItemIconTextures.stateKey(stack, mode, duck.magiccodex$singleFlatModel()));
    }

    /** Same for the overload without leftHand, in case it does not delegate in this version. */
    @Inject(method = "update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V",
            at = @At("RETURN"), require = 0)
    private void magiccodex$tagHiResNoHand(ItemRenderState state, ItemStack stack, ModelTransformationMode mode,
                                           World world, LivingEntity entity, int seed, CallbackInfo ci) {
        var duck = (HiResItemState) state;
        duck.magiccodex$setHiResModel(ItemIconTextures.stateKey(stack, mode, duck.magiccodex$singleFlatModel()));
    }
}
