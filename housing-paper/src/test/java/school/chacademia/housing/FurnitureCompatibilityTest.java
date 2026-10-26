package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;

class FurnitureCompatibilityTest {
    Catalog load(String resource) {
        var in = getClass().getResourceAsStream(resource);
        assertNotNull(in, resource);
        return Catalog.parse(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)), Logger.getAnonymousLogger());
    }
    @Test void everyLegacyDefinitionRetainsGeometryLimitsAndValidSavedLayouts() {
        var old = load("/furniture-before-followups.yml");
        var now = load("/furniture.yml");
        assertEquals(14, old.all().size());
        assertEquals(106, now.all().size());
        for (var d : old.all()) {
            var n = now.get(d.id());
            assertNotNull(n, d.id());
            assertEquals(d.category(),n.category(),d.id()); assertEquals(d.sub(),n.sub(),d.id());
            assertEquals(d.w(),n.w(),d.id()); assertEquals(d.d(),n.d(),d.id());
            assertEquals(d.height(),n.height(),d.id()); assertEquals(d.max(),n.max(),d.id());
            for (int rot=0;rot<(d.wall()?2:4);rot++) {
                var layout = d.surface() ? (d.sub()==0 ? new Layout(d.id(),"",List.of()) : new Layout("",d.id(),List.of()))
                    : new Layout("","",List.of(new Placement(d.id(),0,0,0,rot)));
                assertEquals(layout,HousingRules.prune(layout,32,now::get,old.everything()),d.id());
            }
        }
    }
}
