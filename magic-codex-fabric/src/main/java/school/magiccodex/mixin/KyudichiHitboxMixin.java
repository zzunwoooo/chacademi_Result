package school.magiccodex.mixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.KyudichiClient;
/** 큐디치 / 1:1 (owner 2026-10-19): F3+B hitboxes cannot be turned on during a match (KyudichiClient also turns them off when one starts). */
@Mixin(EntityRenderDispatcher.class)
public abstract class KyudichiHitboxMixin {
 @Inject(method="setRenderHitboxes",at=@At("HEAD"),cancellable=true)
 private void kyudichi$noHitboxes(boolean value,CallbackInfo ci){
  if(value&&KyudichiClient.hitboxesLocked()){
   ci.cancel();
   var c=MinecraftClient.getInstance();
   if(c.player!=null)c.player.sendMessage(Text.literal("큐디치 경기 중에는 히트박스를 볼 수 없어요."),true);
  }
 }
}
