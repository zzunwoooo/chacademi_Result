package school.magiccodex.mixin;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.BakedGlyph;
import net.minecraft.util.Identifier;
import school.magiccodex.client.ItemIconTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Only the private-mail glyph uses the smooth icon renderer; text keeps its original font. */
@Mixin(FontStorage.class)
public abstract class WhisperGlyphMixin {
 @Shadow @Final private Identifier id;
 @Inject(method="getBaked",at=@At("HEAD"),cancellable=true)
 private void magiccodex$whisper(int codepoint,CallbackInfoReturnable<BakedGlyph> cir){
  if(codepoint!=0xE101||!id.equals(Identifier.of("magiccodex","whisper")))return;
  var glyph=ItemIconTextures.whisperGlyph();if(glyph!=null)cir.setReturnValue(glyph);
 }
}