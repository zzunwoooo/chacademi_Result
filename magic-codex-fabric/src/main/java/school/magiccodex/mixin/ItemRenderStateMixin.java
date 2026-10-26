package school.magiccodex.mixin;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import school.magiccodex.client.HiResItemState;
import school.magiccodex.client.ItemIconTextures;

/**
 * Hand, third-person, dropped and framed items all end in ItemRenderState.render. For high-res
 * (&gt;32px) flat icons, draw one smooth card instead of vanilla's per-pixel extruded mesh.
 * Also covers GUI items that the JSON-based ItemIconMixin path does not recognise
 * (e.g. ItemsAdder range_dispatch / custom_model_data definitions).
 * require=0: a mapping mismatch leaves items vanilla instead of crashing.
 */
@Mixin(ItemRenderState.class)
public abstract class ItemRenderStateMixin implements HiResItemState {
    @Shadow boolean leftHand;
    @Shadow int layerCount;
    @Shadow private ItemRenderState.LayerRenderState[] layers;
    @Shadow public abstract Transformation getTransformation();

    @Unique private Identifier magiccodex$hiRes;

    @Override public void magiccodex$setHiResModel(Identifier model) { this.magiccodex$hiRes = model; }
    @Override public Identifier magiccodex$hiResModel() { return this.magiccodex$hiRes; }

    @Override public net.minecraft.client.render.model.BakedModel magiccodex$singleFlatModel() {
        if (layerCount != 1 || layers == null || layers.length == 0 || layers[0] == null) return null;
        var layer = (ItemLayerAccessor) (Object) layers[0];
        if (layer.magiccodex$special() != null) return null;
        return layer.magiccodex$model();
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 0)
    private void magiccodex$clearHiRes(CallbackInfo ci) { this.magiccodex$hiRes = null; }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void magiccodex$renderHiRes(MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay,
                                        CallbackInfo ci) {
        if (magiccodex$hiRes == null || layerCount == 0) return;
        if (ItemIconTextures.renderWorld(magiccodex$hiRes, matrices, consumers, light, overlay, getTransformation(), leftHand))
            ci.cancel();
    }
}
