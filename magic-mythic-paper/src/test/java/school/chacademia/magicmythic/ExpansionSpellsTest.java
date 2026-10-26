package school.chacademia.magicmythic;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-14 (spells 101-167 + 7 earlier): generated skills only use mechanics/types this plugin implements. */
class ExpansionSpellsTest {
    static final Map<String, Set<String>> TYPES = Map.of(
        "chaworld", Set.of("decoy", "companion", "heat", "platform", "flight", "frostwalk", "kiln", "ore", "water", "line", "cauldron", "wetfarm", "grow", "concrete", "sign", "lure", "boat", "dew", "dye", "sapgeum"),
        "chafarm", Set.of("seedrow", "harvest", "ripen", "revive", "grass", "strip", "compost", "breed", "bees", "craft", "weather"),
        "chautil", Set.of("forge", "vein", "age", "mend", "mine", "survey", "return", "text", "potion", "exp", "deepdark", "star", "reprise", "core", "truesight", "taunt"),
        "chasummon", Set.of("golem", "emissary", "pets", "conduit", "light", "ladder", "spring"),
        "chabuff", Set.of("giant", "root"),
        "chaguard", Set.of("lethal", "reflect", "block", "deflect", "thorns", "anchor", "counter", "ward", "invuln", "homing"),
        "chamark", Set.of("ash_brand", "wedge", "prison", "swords", "root"),
        "chaally", Set.of("quench", "cleanse", "share", "potion", "heal", "buff", "feed", "comfort", "dawn", "oath"),
        "chagimmick", Set.of("ignite","extinguish","thaw","steam","level","weight","grow","beam","tune","tint","wake","rewind","conduit","mark"),
        "chaarcana", Set.of("double", "echo", "thread", "swap", "contract", "siphon", "drowse", "spread", "discord", "chain", "defer", "ink", "nail", "lullaby", "loosen", "seal", "scales", "cage", "wound", "ghosttool", "bell", "eclipse", "airbrake", "hushed", "thorns", "skipring", "droplet", "tideplane"));
    static final Set<String> UNTYPED = Set.of("chahit", "chameteor", "chabolt", "chazone", "chachannel", "chanote", "chaselfpotion", "chaselfvelocity", "chamobshot", "chatelegraph");

    @Test void everyGeneratedLineUsesAnImplementedMechanic() throws Exception {
        var yaml = YamlConfiguration.loadConfiguration(Path.of("mythicmobs/Skills/chacademi_spells.yml").toFile());
        var line = Pattern.compile("^(cha\\w+)\\{([^}]*)\\}");
        int checked = 0;
        for (String skill : yaml.getKeys(false)) {
            for (String l : yaml.getStringList(skill + ".Skills")) {
                Matcher m = line.matcher(l);
                if (!m.find()) { assertTrue(l.startsWith("delay ") || l.startsWith("leap{"), skill + ": " + l); continue; }
                String mech = m.group(1);
                var params = new HashMap<String, String>();
                for (String kv : m.group(2).split(";")) if (kv.contains("=")) params.put(kv.split("=", 2)[0], kv.split("=", 2)[1]);
                if (UNTYPED.contains(mech)) { checked++; continue; }
                assertTrue(TYPES.containsKey(mech), skill + ": unknown mechanic " + mech);
                assertTrue(TYPES.get(mech).contains(params.get("type")), skill + ": " + mech + " type " + params.get("type"));
                assertFalse(params.getOrDefault("spell", "").isBlank(), skill + ": spell= missing");
                checked++;
            }
        }
        assertTrue(checked > 150);
        // the chant follow-up skill exists
        assertTrue(yaml.contains("CHA_solar_judgment_fire"));
    }

    @Test void routesExistForEveryNewActiveSpell() throws Exception {
        var routes = YamlConfiguration.loadConfiguration(Path.of("server-config/spell-runtime.yml").toFile()).getConfigurationSection("spells");
        for (String id : List.of("earth_shaping", "stone_wall", "earth_elemental", "solar_judgment", "faint_veil", "flame_forging", "blue_bonfire", "starlight_reprise"))
            assertNotNull(routes.getConfigurationSection(id), id);
        for (String passive : List.of("trajectory_acceleration", "resonant_arrow", "glass_firing", "glaze_coating"))
            assertNull(routes.getConfigurationSection(passive), passive); // passives live in Passives
        for (String dropped : List.of("cinder_detonation", "vine_ladder", "light_mark", "experience_exchange"))
            assertNull(routes.getConfigurationSection(dropped), dropped); // 2026-10-26 기획에서 삭제
        for (String id : routes.getKeys(false)) {
            int range = routes.getInt(id + ".range");
            assertTrue(range >= 1 && range <= 48, id); // Bridge SpellRules limit
            assertTrue(routes.getString(id + ".skill").matches("CHA_[a-z0-9_]{1,80}"), id);
        }
    }

    @Test void slotReportParsing() {
        assertEquals(List.of("a", "b_c", "d-1"), SpellSlots.parse(" A ,b_c,,d-1,a,bad id!"));
        assertEquals(6, SpellSlots.parse("a,b,c,d,e,f,g,h").size());
        assertTrue(SpellSlots.parse(null).isEmpty());
    }

    @Test void sixElementsWindow() {
        var times = new HashMap<String, Long>();
        long now = 100_000;
        for (String e : List.of("wind", "fire", "water", "earth", "light")) times.put(e, now - 1000);
        assertFalse(DiscoveryHooks.sixWithin(times, now, 30_000));
        times.put("dark", now);
        assertTrue(DiscoveryHooks.sixWithin(times, now, 30_000));
        times.put("wind", now - 31_000);
        assertFalse(DiscoveryHooks.sixWithin(times, now, 30_000));
    }

    @Test void compostRateMatchesVanillaExpectation() {
        // 0.65 chance (e.g. wheat): 11 items -> 1 bone meal costs ceil(7/0.65)=11
        assertEquals(1, FarmMech.compostYield(0.65f, 11));
        assertEquals(11, FarmMech.compostCost(0.65f, 1));
        assertEquals(0, FarmMech.compostYield(0.3f, 20));
        assertEquals(7, FarmMech.compostCost(1f, 1));
    }

    @Test void weatheringTableOnlyNamesRealBlocks() {
        for (var e : UtilMech.WEATHER.entrySet()) {
            assertNotNull(Material.matchMaterial(e.getKey()), e.getKey());
            for (String v : e.getValue()) assertNotNull(Material.matchMaterial(v), v);
        }
    }

    @Test void guidingStarArrowIsRelativeToFacing() {
        var from = new Location(null, 0, 64, 0, 0, 0); // yaw 0 = facing +Z (south)
        assertEquals("↑", UtilMech.arrow(from, new Location(null, 0, 64, 10)));
        assertEquals("↓", UtilMech.arrow(from, new Location(null, 0, 64, -10)));
        assertEquals("←", UtilMech.arrow(from, new Location(null, 10, 64, 0)));  // +X is to the left when facing south
        assertEquals("→", UtilMech.arrow(from, new Location(null, -10, 64, 0)));
    }
}
