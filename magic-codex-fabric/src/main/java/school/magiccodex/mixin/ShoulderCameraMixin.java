package school.magiccodex.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.ShoulderCamera;

/** Replaces only vanilla's third-person "move back 4 blocks" step; the front view (inverse) stays vanilla. */
@Mixin(Camera.class)
public abstract class ShoulderCameraMixin {
    @Shadow private BlockView area;
    @Shadow @Final private Quaternionf rotation;
    @Shadow public abstract Vec3d getPos();
    @Shadow protected abstract void setPos(Vec3d pos);
    @Unique private boolean magiccodex$inverse;
    @Unique private Entity magiccodex$focused;

    @Inject(method = "update", at = @At("HEAD"))
    private void magiccodex$remember(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        magiccodex$inverse = inverseView;
        magiccodex$focused = focused;
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;moveBy(FFF)V", ordinal = 0))
    private void magiccodex$shoulder(Camera camera, float forward, float up, float right, Operation<Void> original) {
        if (magiccodex$inverse || magiccodex$focused == null || !ShoulderCamera.active()) {
            original.call(camera, forward, up, right);
            return;
        }
        setPos(ShoulderCamera.position(area, magiccodex$focused, getPos(), rotation));
    }
}
