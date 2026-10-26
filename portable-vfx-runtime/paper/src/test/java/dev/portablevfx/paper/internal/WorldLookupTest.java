package dev.portablevfx.paper.internal;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldLookupTest {
    private final Map<String,String> names=Map.of("town","town-world","world","overworld-world","world_nether","nether-world","LegacyTown","legacy-world");
    private final Map<String,String> keys=Map.of("minecraft:town","town-world","minecraft:overworld","overworld-world","minecraft:the_nether","nether-world","academy:town","custom-town-world");
    private String resolve(String world){return WorldLookup.resolve(world,names::get,key->keys.get(key.toString()));}
    @Test void resolvesExactMinecraftTownKeyUsedByCatalogAndFollowRequests(){
        assertNull(names.get("minecraft:town"),"old Bukkit name-only lookup reproduces Unknown world");
        assertEquals("town-world",resolve("minecraft:town"));
        assertEquals(resolve("town"),resolve("minecraft:town"),"follow target resolves to same world");
    }
    @Test void preservesPlainWorldNamesAndVanillaDimensionKeys(){
        assertEquals("overworld-world",resolve("world"));
        assertEquals("overworld-world",resolve("minecraft:overworld"));
        assertEquals("nether-world",resolve("world_nether"));
        assertEquals("nether-world",resolve("minecraft:the_nether"));
        assertEquals("legacy-world",resolve("LegacyTown"));
    }
    @Test void namespacesRemainDistinctAndUnknownWorldsNeverFallBackToAnotherWorld(){
        assertEquals("custom-town-world",resolve("academy:town"));
        assertNotEquals(resolve("academy:town"),resolve("minecraft:town"));
        assertNull(resolve("missing:town"));assertNull(resolve("minecraft:missing"));
        assertNull(resolve("Invalid:town"));assertNull(resolve(""));assertNull(resolve(null));
    }
}
