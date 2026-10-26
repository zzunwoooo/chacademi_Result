package school.magiccodex.mixin;

import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.item.model.special.SpecialModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.client.render.item.ItemRenderState.LayerRenderState.class)
public interface ItemLayerAccessor {
    @Accessor("model") BakedModel magiccodex$model();
    @Accessor("specialModelType") SpecialModelRenderer<?> magiccodex$special();
}
