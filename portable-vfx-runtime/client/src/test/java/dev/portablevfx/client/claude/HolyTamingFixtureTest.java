package dev.portablevfx.client.claude;

import java.nio.file.*;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Optional authored asset fixture; host integration must set PVFX_HOLYTAMING_DIR. */
class HolyTamingFixtureTest {
    @Test void authoredPackLoadsAllEightSystemsWithoutDroppingFeatures() throws Exception {
        String configured=System.getenv("PVFX_HOLYTAMING_DIR");
        assumeTrue(configured!=null&&!configured.isBlank(),"Set PVFX_HOLYTAMING_DIR to the extracted effects parent directory");
        Path root=Path.of(configured),pack=root.resolve("HolyTaming");
        assertTrue(Files.isRegularFile(pack.resolve("vfx.json")),"Required host fixture missing");
        var effect=ClaudeEffect.parse(Files.readAllBytes(pack.resolve("vfx.json")));
        assertEquals("HolyTaming",effect.name());assertEquals("1.13.0",effect.schemaVersion());
        assertEquals(8,effect.systems().size());
        assertEquals(146,effect.systems().stream().mapToInt(s->s.layers().size()).sum());
        assertTrue(ClaudeEffect.SUPPORTED_FEATURES.containsAll(effect.requiredFeatures()));
        var loaded=ClaudeConfigLoader.load(root);
        assertTrue(loaded.errors().isEmpty(),()->String.join("\n",loaded.errors()));
        assertEquals(Set.of("open","connect","channel","tether","bless","release","fizzle","snap"),
                loaded.definitions().stream().map(d->d.id().substring(d.id().lastIndexOf('/')+1)).collect(Collectors.toSet()));
        assertEquals(14,loaded.assets().keySet().stream().filter(s->s.endsWith(".png")).count());
        assertEquals(Set.of("channel","tether"),effect.systems().stream().filter(ClaudeEffect.SystemDef::loop).map(ClaudeEffect.SystemDef::id).collect(Collectors.toSet()));
    }
}
