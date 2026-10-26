package school.magiccodex.client;

import net.minecraft.util.Identifier;

/** Duck interface added to ItemRenderState by ItemRenderStateMixin. */
public interface HiResItemState {
    /** Icon key drawn as a smooth flat card (see ItemIconTextures#renderWorld), or null for vanilla. */
    void magiccodex$setHiResModel(Identifier model);
    /** The key set by the last update, or null (used for exact-size GUI icons, see ExactItemIcons). */
    Identifier magiccodex$hiResModel();
    /** Single-layer flat (item/generated-like) model whose texture is larger than 32px, else null. */
    net.minecraft.client.render.model.BakedModel magiccodex$singleFlatModel();
}
