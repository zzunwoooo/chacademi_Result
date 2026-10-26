package school.magiccodex.mixin;

import java.lang.reflect.Method;
import net.minecraft.util.Identifier;
import net.minecraft.util.TriState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodexTextureFilterTest {
    private static TriState filter(String id, TriState requested) throws Exception {
        Method method = CodexTextureFilterMixin.class.getDeclaredMethod("magiccodex$smoothOwnTextures",
                TriState.class, Identifier.class, TriState.class, boolean.class);
        method.setAccessible(true);
        return (TriState) method.invoke(null, requested, Identifier.of(id), requested, false);
    }
    @Test void guiPngAndFontAssetsStillUseSmoothFiltering() throws Exception {
        for (String path : new String[]{"textures/gui/pets/title_plate.png", "textures/gui/codex_base.png",
                "textures/gui/social/friends_panel.png", "font/atlas_0", "textures/spells/fire.png"}) {
            for (TriState requested : TriState.values()) assertEquals(TriState.TRUE, filter("magiccodex:"+path,requested));
        }
    }
    @Test void actualDynamicPetAndAuthoredShinyPathsKeepTheirRequestedFilter() throws Exception {
        for (String path : new String[]{"pet_model/0", "pet_model/12345",
                "textures/vanilla_shiny/villager/villager/villager.png",
                "textures/vanilla_shiny/wolf/wolf/wolf.png"}) {
            for (TriState requested : TriState.values()) assertEquals(requested,filter("magiccodex:"+path,requested));
        }
    }
    @Test void pathAndNamespaceBoundariesDoNotBroadenTheException() throws Exception {
        for (String path : new String[]{"pet_models/0", "textures/vanilla_shiny_ui/panel.png",
                "textures/gui/pet_model/panel.png", "shiny/villager/textures/entity/villager/villager.png"}) {
            assertEquals(TriState.TRUE,filter("magiccodex:"+path,TriState.FALSE));
        }
        for (TriState requested : TriState.values()) {
            assertEquals(requested,filter("minecraft:textures/entity/villager/villager.png",requested));
            assertEquals(requested,filter("other:pet_model/0",requested));
        }
    }

    private static boolean mipmap(String id, boolean requested) throws Exception {
        Method method = CodexTextureFilterMixin.class.getDeclaredMethod("magiccodex$mipmapItemIcons",
                boolean.class, Identifier.class, TriState.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, requested, Identifier.of(id), TriState.FALSE, requested);
    }
    @Test void runtimeItemIconsAlwaysUseTheirMipChain() throws Exception {
        assertTrue(mipmap("magiccodex:runtime_item_icon/0/12", false));
        assertTrue(mipmap("magiccodex:runtime_item_icon/3/0", true));
        assertFalse(mipmap("magiccodex:textures/gui/codex_base.png", false));
        assertFalse(mipmap("other:runtime_item_icon/0/1", false));
        assertTrue(mipmap("minecraft:textures/atlas/blocks.png", true));
        assertEquals(school.magiccodex.client.ItemIconTextures.RUNTIME_PREFIX, "runtime_item_icon/");
    }
}
