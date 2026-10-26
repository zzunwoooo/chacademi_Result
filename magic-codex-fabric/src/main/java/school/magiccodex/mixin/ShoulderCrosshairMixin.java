package school.magiccodex.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import school.magiccodex.client.ShoulderCrosshair;

/** In the shoulder view, block breaking/placing, attacks, use and the block outline follow the crosshair. */
@Mixin(GameRenderer.class)
public abstract class ShoulderCrosshairMixin {
    @Inject(method = "findCrosshairTarget", at = @At("HEAD"), cancellable = true)
    private void magiccodex$shoulderTarget(Entity camera, double blockRange, double entityRange, float tickDelta, CallbackInfoReturnable<HitResult> cir) {
        HitResult shoulder = ShoulderCrosshair.find(camera, blockRange, entityRange, tickDelta);
        if (shoulder != null) cir.setReturnValue(shoulder);
    }
}
