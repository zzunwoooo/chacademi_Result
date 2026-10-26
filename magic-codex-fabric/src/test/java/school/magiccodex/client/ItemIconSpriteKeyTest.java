package school.magiccodex.client;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemIconSpriteKeyTest {
    private static int[] quad(float... z) {
        int[] data = new int[z.length * 8];
        for (int v = 0; v < z.length; v++) data[v * 8 + 2] = Float.floatToIntBits(z[v]);
        return data;
    }
    @Test void generatedItemPlaneIsFlat() {
        assertTrue(ItemIconTextures.flatZ(quad(7.5f / 16, 7.5f / 16, 8.5f / 16, 8.5f / 16)));
    }
    @Test void elementModelIsNotFlat() {
        assertFalse(ItemIconTextures.flatZ(quad(0, 0, 1, 1)));
        assertFalse(ItemIconTextures.flatZ(quad(7.5f / 16, 7.5f / 16, 12f / 16, 12f / 16)));
    }
    @Test void spriteKeyRoundTripsToAtlasSpriteId() {
        var key = Identifier.of(ItemIconTextures.SPRITE_NS, "itemsadder/item/artifact_key");
        assertEquals(Identifier.of("itemsadder", "item/artifact_key"), ItemIconTextures.spriteTexture(key).orElseThrow());
        assertTrue(ItemIconTextures.spriteTexture(Identifier.of(ItemIconTextures.SPRITE_NS, "nopath")).isEmpty());
    }
}
