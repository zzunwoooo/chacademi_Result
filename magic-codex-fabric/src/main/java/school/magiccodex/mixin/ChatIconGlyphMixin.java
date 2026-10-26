package school.magiccodex.mixin;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.BakedGlyph;
import net.minecraft.util.Identifier;
import school.magiccodex.client.ItemIconTextures;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(FontStorage.class)
public abstract class ChatIconGlyphMixin {
 @Shadow @Final private Identifier id;
 @Inject(method="getBaked",at=@At("HEAD"),cancellable=true)
 private void magiccodex$chatIcon(int codepoint,CallbackInfoReturnable<BakedGlyph> cir){
  if(!id.equals(Identifier.of("magiccodex","chat")))return;
  var glyph=ItemIconTextures.chatGlyph(codepoint);if(glyph!=null)cir.setReturnValue(glyph);
 }
 @Inject(method="getGlyph",at=@At("RETURN"),cancellable=true)
 private void magiccodex$chatAdvance(int codepoint,boolean validate,CallbackInfoReturnable<net.minecraft.client.font.Glyph> cir){
  if(id.equals(Identifier.of("magiccodex","chat"))&&school.magiccodex.client.ChatImageMetrics.isIcon(codepoint))
   {
    var original=cir.getReturnValue();
    cir.setReturnValue(new net.minecraft.client.font.Glyph(){
     public float getAdvance(){return school.magiccodex.client.ChatImageMetrics.ADVANCE;}
     public float getBoldOffset(){return 0;}
     public float getShadowOffset(){return 0;}
     public BakedGlyph bake(java.util.function.Function<net.minecraft.client.font.RenderableGlyph,BakedGlyph> baker){return original.bake(baker);}
    });
   }
 }
}
