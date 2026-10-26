package school.magiccodex.mixin;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntityRenderer.class)
public abstract class TamingNameMixin {
    @Inject(method="hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z",at=@At("HEAD"),cancellable=true)
    private void magiccodex$tamingName(LivingEntity entity,double distance,CallbackInfoReturnable<Boolean> result){
        if(school.magiccodex.client.NameplateClient.suppressVanilla(entity)||school.magiccodex.client.TamingClient.hidesVanillaName(entity.getId()))result.setReturnValue(false);
    }
}
