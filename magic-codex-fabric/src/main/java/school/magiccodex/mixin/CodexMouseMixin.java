package school.magiccodex.mixin;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.CastingClient;
/** Aim preview clicks, then thumb buttons through the common gameplay-gated casting input path. */
@Mixin(Mouse.class)
public abstract class CodexMouseMixin {
 @Unique private final school.magiccodex.client.TopMenuMouseInput magiccodex$menuInput=new school.magiccodex.client.TopMenuMouseInput();
 @Inject(method="onMouseButton",at=@At("HEAD"),cancellable=true)
 private void magiccodex$thumb(long window,int button,int action,int modifiers,CallbackInfo ci){
  var client=MinecraftClient.getInstance();
  if(window!=client.getWindow().getHandle())return;
  // ChatPlus and chat-layout consume overlapping clicks before Screen callbacks.
  // The menu is drawn on top, so it owns these gestures before chat dispatch.
  if(magiccodex$menuInput.handle(button,action,()->school.magiccodex.client.TopMenuClient.click(
      school.magiccodex.client.HudPointer.x(),school.magiccodex.client.HudPointer.y(),button))){
   ci.cancel();return;
  }
  // Aimed casting first: left = cast / HUD click with the magic cursor, right = cancel.
  if(school.magiccodex.client.AimClient.mouse(client,button,action)||CastingClient.handleMouse(client,button,action))ci.cancel();
 }
}
