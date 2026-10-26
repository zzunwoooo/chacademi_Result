package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SettingsTest {
    @Test void nextStep() {
        var steps = new TreeMap<Integer, Long>(); steps.put(4, 100L); steps.put(6, 300L);
        var s = new Settings("school", 3, steps, true, 10_000, 100, 40, false, false);
        assertEquals(4, (int) s.next(3).getKey());
        assertEquals(6, (int) s.next(4).getKey());
        assertEquals(6, (int) s.next(5).getKey());
        assertNull(s.next(6));
    }

    @Test void likeAndVisitScopes() throws Exception {
        var log = java.util.logging.Logger.getAnonymousLogger();
        var none = new org.bukkit.configuration.file.YamlConfiguration();
        var d = Settings.from(none, log);
        assertFalse(d.likeGlobal()); assertFalse(d.visitOnceEver());
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        y.loadFromString("like:\n  limit: global\nvisits:\n  count-once-per: ever\n");
        var g = Settings.from(y, log);
        assertTrue(g.likeGlobal()); assertTrue(g.visitOnceEver());
        y.loadFromString("like:\n  limit: weird\n");
        assertFalse(Settings.from(y, log).likeGlobal());
    }
}
