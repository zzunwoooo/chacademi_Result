package school.magiccodex.mixin;

import net.minecraft.client.render.RenderPhase;
import net.minecraft.util.Identifier;
import net.minecraft.util.TriState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Smooth UI/font assets while respecting the requested filter for pixel-art pet textures. */
@Mixin(RenderPhase.Texture.class)
public abstract class CodexTextureFilterMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static TriState magiccodex$smoothOwnTextures(TriState original, Identifier id,
                                                       TriState requested, boolean mipmap) {
        if (!id.getNamespace().equals("magiccodex")) return original;
        String path = id.getPath();
        if (path.startsWith("pet_model/") || path.startsWith("textures/vanilla_shiny/")) {
            return original;
        }
        return TriState.TRUE;
    }

    /**
     * High-res item icons drawn in the world (hand, ground, frames) go through vanilla item layers,
     * which request no mipmaps. Their runtime textures carry a full mip chain; use it so distant
     * dropped items do not shimmer. Literal prefix = ItemIconTextures.RUNTIME_PREFIX (not referenced
     * here to keep that class out of RenderLayer static init).
     */
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static boolean magiccodex$mipmapItemIcons(boolean original, Identifier id,
                                                     TriState requested, boolean mipmap) {
        return original || (id.getNamespace().equals("magiccodex") && id.getPath().startsWith("runtime_item_icon/"));
    }
}
