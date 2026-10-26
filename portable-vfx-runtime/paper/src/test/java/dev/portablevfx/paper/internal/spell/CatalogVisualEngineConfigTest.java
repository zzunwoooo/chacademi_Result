package dev.portablevfx.paper.internal.spell;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogVisualEngineConfigTest {
    private static MemoryConfiguration config(double width, double linkLength) {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("test").set("phases", List.of(Map.of(
                "effect", "claude:test/main", "width", width, "link-length", linkLength)));
        return root;
    }
    @Test void widthUsesWireBound() {
        assertDoesNotThrow(() -> CatalogVisualEngine.read(config(64, -1)));
        assertThrows(IllegalArgumentException.class, () -> CatalogVisualEngine.read(config(64.01, -1)));
    }
    @Test void linkLengthAcceptsOnlyUnsetOrNonnegative() {
        assertDoesNotThrow(() -> CatalogVisualEngine.read(config(0, -1)));
        assertDoesNotThrow(() -> CatalogVisualEngine.read(config(0, 0)));
        assertDoesNotThrow(() -> CatalogVisualEngine.read(config(0, 256)));
        assertThrows(IllegalArgumentException.class, () -> CatalogVisualEngine.read(config(0, -0.5)));
    }
}
