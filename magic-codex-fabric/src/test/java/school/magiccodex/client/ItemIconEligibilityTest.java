package school.magiccodex.client;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemIconEligibilityTest {
    private Optional<?> resolve(Map<String, String> textures) throws Exception {
        Method method = ItemIconTextures.class.getDeclaredMethod("flatLayer", Map.class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(null, textures);
    }

    @Test void sparseSecondLayerRemainsVanilla() throws Exception {
        assertTrue(resolve(Map.of("layer0", "test:item/base", "layer2", "test:item/extra")).isEmpty());
    }

    @Test void ordinaryMultipleLayersRemainVanilla() throws Exception {
        assertTrue(resolve(Map.of("layer0", "test:item/base", "layer1", "test:item/extra")).isEmpty());
    }
}
