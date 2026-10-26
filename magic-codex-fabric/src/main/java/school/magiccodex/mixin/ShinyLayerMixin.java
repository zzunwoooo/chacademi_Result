package school.magiccodex.mixin;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import school.magiccodex.client.ShinyClient;
@Mixin(RenderLayer.class)
public abstract class ShinyLayerMixin {
 @ModifyVariable(method={"getEntitySolid","getEntitySolidZOffsetForward","getEntityCutout","getEntityCutoutNoCull","getEntityCutoutNoCullZOffset","getEntityTranslucent","getEntityTranslucentEmissive","getEntitySmoothCutout","getEntityDecal","getEntityNoOutline","getEntityAlpha","getEyes","getBreezeWind","getEnergySwirl","getOutline"},at=@At("HEAD"),argsOnly=true,ordinal=0)
 private static Identifier shiny$texture(Identifier id){return ShinyClient.texture(id);}
}
