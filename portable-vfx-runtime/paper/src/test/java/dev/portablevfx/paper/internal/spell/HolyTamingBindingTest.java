package dev.portablevfx.paper.internal.spell;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Keep the shipped HolyTaming choreography aligned with the paid catalog spell. */
class HolyTamingBindingTest {
    private static final String SPELL="rainbow_collar",PREFIX="claude:holytaming/";
    private static final List<String> CAST=List.of("open","connect","channel","tether");
    private static final List<String> SUCCESS=List.of("bless","release");
    private static final List<String> FAILURE=List.of("fizzle","snap");
    private static final Set<String> EFFECTS=Set.of("open","connect","channel","tether","bless","release","fizzle","snap");
    private static final Set<String> LINKS=Set.of("connect","tether","release","snap");

    @Test void bundledCatalog149AndBindingHaveTheSameEightHolyTamingPhases() throws Exception {
        var catalogYaml=yaml("/spell-catalog.yml");
        var bindingsYaml=yaml("/spell-bindings.yml");
        var catalog=SpellCatalogYaml.read(catalogYaml);
        var spell=catalog.resolve(SPELL).orElseThrow();
        var binding=section(bindingsYaml,"bindings."+SPELL);
        assertEquals(233,spell.number());assertEquals(233,binding.getInt("number")); // 2026-10-25 renumber
        assertEquals(150d,spell.manaCost().doubleValue());assertEquals(60d,spell.cooldownSeconds().doubleValue());
        assertTrue(spell.enabled());assertTrue(spell.reviewed());
        assertEquals("magic.learned.rainbow_collar",spell.permission());
        assertSame(spell,catalog.resolve("교화").orElseThrow());
        assertSame(spell,catalog.resolve("taming").orElseThrow());
        assertEquals("HolyTaming",binding.getString("folder"));
        var expected=EFFECTS.stream().map(PREFIX::concat).collect(Collectors.toSet());
        assertEquals(8,spell.phases().size());
        assertEquals(expected,spell.phases().stream().map(SpellCatalog.Phase::effect).collect(Collectors.toSet()));
        // Compare full raw phases too: the catalog domain intentionally retains only basic timing/anchor fields.
        assertEquals(section(catalogYaml,"spells."+SPELL).getMapList("phases"),binding.getMapList("phases"));
        var plan=plan(bindingsYaml);
        assertEquals(8,plan.phases().size());
        assertEquals(expected,plan.phases().stream().map(CatalogVisualEngine.Phase::effect).collect(Collectors.toSet()));
    }

    @Test void successAndFailureKeepSeparateServerResultTriggersAndAuthoredStopTails() throws Exception {
        var yaml=yaml("/spell-bindings.yml");
        var plan=plan(yaml);
        assertEquals(ids(CAST),effectsFor(plan,"cast"));
        assertEquals(ids(SUCCESS),effectsFor(plan,"external:trigger"));
        assertEquals(ids(FAILURE),effectsFor(plan,"expire"));
        assertEquals(List.of("external:trigger","expire"),section(yaml,"bindings."+SPELL).getStringList("external-events"));
        for(var phase:plan.phases()){
            assertEquals(-1,phase.finishAfter(),phase.effect());
            assertFalse(phase.clearStoppedLocal(),phase.effect());
            if(phase.trigger().equals("cast"))assertTrue(phase.stopEffects().isEmpty(),phase.effect());
            else assertEquals(ids(CAST),phase.stopEffects(),phase.effect());
        }
        for(Map<?,?> phase:section(yaml,"bindings."+SPELL).getMapList("phases")){
            if(!"cast".equals(phase.get("trigger")))
                assertEquals(Boolean.FALSE,phase.get("clear-stopped-local"),"Terminal phases must explicitly preserve authored stopTail: "+phase.get("effect"));
        }
    }

    @Test void everyPhaseUsesTargetHeightAndAllFourLinksRunFromHandToChest() throws Exception {
        var plan=plan(yaml("/spell-bindings.yml"));
        for(var phase:plan.phases()){
            assertEquals(-1d,phase.scaleInput(),phase.effect());
            String name=phase.effect().substring(PREFIX.length());
            if(LINKS.contains(name)){
                assertEquals("link",phase.role(),phase.effect());
                assertEquals("caster",phase.anchor(),phase.effect());
                assertEquals("caster-hand",phase.linkSource(),phase.effect());
                assertEquals("target-chest",phase.linkTarget(),phase.effect());
                assertEquals(-1d,phase.linkLength(),phase.effect());
            }else{
                assertEquals("attached",phase.role(),phase.effect());
                assertEquals("target",phase.anchor(),phase.effect());
            }
        }
    }

    @Test void channelAndTetherStartAtTheNineTickSustainBoundary() throws Exception {
        var yaml=yaml("/spell-bindings.yml");
        var binding=section(yaml,"bindings."+SPELL);
        assertEquals(.45,binding.getDouble("sequence.sustainStart"),1e-8);
        for(var phase:plan(yaml).phases()){
            boolean sustain=phase.effect().equals(PREFIX+"channel")||phase.effect().equals(PREFIX+"tether");
            assertEquals(sustain?9:0,phase.delay(),phase.effect());
            if(sustain)assertEquals(400,phase.ttl(),phase.effect());
        }
    }

    private static List<String> ids(List<String> names){return names.stream().map(PREFIX::concat).toList();}
    private static List<String> effectsFor(CatalogVisualEngine.Plan plan,String trigger){
        return plan.phases().stream().filter(p->p.trigger().equals(trigger)).map(CatalogVisualEngine.Phase::effect).toList();
    }
    private static CatalogVisualEngine.Plan plan(YamlConfiguration yaml){
        var plan=CatalogVisualEngine.read(section(yaml,"bindings")).get(SPELL);
        assertNotNull(plan);return plan;
    }
    private static ConfigurationSection section(YamlConfiguration yaml,String path){
        return Objects.requireNonNull(yaml.getConfigurationSection(path),path);
    }
    private static YamlConfiguration yaml(String resource) throws Exception {
        try(var reader=new InputStreamReader(Objects.requireNonNull(HolyTamingBindingTest.class.getResourceAsStream(resource),resource),StandardCharsets.UTF_8)){
            var yaml=new YamlConfiguration();yaml.load(reader);return yaml;
        }
    }
}
