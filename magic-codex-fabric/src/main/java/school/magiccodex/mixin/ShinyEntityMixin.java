package school.magiccodex.mixin;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.ShinyClient;
@Mixin(EntityRenderDispatcher.class)
public abstract class ShinyEntityMixin {
 @Inject(method="render",at=@At("HEAD"))private void shiny$begin(Entity e,double x,double y,double z,float delta,MatrixStack matrices,VertexConsumerProvider vertices,int light,CallbackInfo ci){ShinyClient.begin(e);}
 @Inject(method="render",at=@At("RETURN"))private void shiny$end(Entity e,double x,double y,double z,float delta,MatrixStack matrices,VertexConsumerProvider vertices,int light,CallbackInfo ci){ShinyClient.end();}
}
