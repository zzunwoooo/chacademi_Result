package school.magiccodex.mixin;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EnderDragonEntityRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import school.magiccodex.client.ShinyClient;
@Mixin(EnderDragonEntityRenderer.class)
public abstract class ShinyDragonMixin {
 @Shadow @Final private static RenderLayer DRAGON_CUTOUT;
 @Shadow @Final private static RenderLayer DRAGON_DECAL;
 @Shadow @Final private static RenderLayer DRAGON_EYES;
 @Shadow @Final private static Identifier TEXTURE;
 @Shadow @Final private static Identifier EYE_TEXTURE;
 @ModifyArg(method="render(Lnet/minecraft/client/render/entity/state/EnderDragonEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/VertexConsumerProvider;getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;"),index=0)
 private RenderLayer shiny$dragon(RenderLayer layer){if(!ShinyClient.active())return layer;if(layer==DRAGON_CUTOUT)return RenderLayer.getEntityCutoutNoCull(ShinyClient.texture(TEXTURE));if(layer==DRAGON_DECAL)return RenderLayer.getEntityDecal(ShinyClient.texture(TEXTURE));if(layer==DRAGON_EYES)return RenderLayer.getEyes(ShinyClient.texture(EYE_TEXTURE));return layer;}
}
