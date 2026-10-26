package school.magiccodex.mixin;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import school.magiccodex.client.KyudichiClient;
/**
 * 큐디치 / 1:1 (owner 2026-10-19): players and the balls stay drawn at any distance during a match (vanilla hides small
 * entities past ~64 blocks). The server must still send them: arena spigot.yml entity-tracking-range (see the handoff).
 */
@Mixin(Entity.class)
public abstract class KyudichiRenderDistanceMixin {
 @Inject(method="shouldRender(DDD)Z",at=@At("HEAD"),cancellable=true)
 private void kyudichi$far(double x,double y,double z,CallbackInfoReturnable<Boolean> cir){
  if(KyudichiClient.renderFar((Entity)(Object)this))cir.setReturnValue(true);
 }
}
