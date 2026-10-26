package school.magiccodex.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** #47 마법 효과음: 설정 읽기와 VFX 사건 → 소리 키 연결 (재생 없음). */
class SpellSoundsTest {
    static YamlConfiguration yaml(String s) throws Exception { var y = new YamlConfiguration(); y.loadFromString(s); return y; }

    @Test void parsesLayersWithBoundsAndNamespace() throws Exception {
        var cues = SpellSounds.parse(yaml("""
            spells:
              ember_bolt:
                cast:
                  - {sound: 'minecraft:entity.blaze.shoot', volume: 9, pitch: 0.1, delay: 999}
                  - {sound: block.fire.ambient}
                hit:
                  - {sound: entity.generic.burn, volume: 0.6, pitch: 1.1}
            """));
        var cast = cues.get("ember_bolt").get("cast");
        assertEquals(2, cast.size());
        assertEquals("entity.blaze.shoot", cast.get(0).sound());
        assertEquals(3f, cast.get(0).volume());
        assertEquals(0.5f, cast.get(0).pitch());
        assertEquals(60, cast.get(0).delay());
        assertEquals(1f, cast.get(1).volume());
        assertThrows(IllegalArgumentException.class, () -> SpellSounds.parse(yaml("spells:\n  x:\n    cast:\n      - {sound: 'bad sound!'}\n")));
    }

    @Test void vfxEventsMapToHitAndEnd() throws Exception {
        var events = SpellSounds.parse(yaml("""
            spells:
              s:
                cast: [{sound: a.b}]
                hit: [{sound: a.c}]
                end: [{sound: a.d}]
                summon: [{sound: a.e}]
            """)).get("s");
        assertEquals("cast", SpellSounds.key(events, "cast"));
        assertEquals("hit", SpellSounds.key(events, "external:contact"));
        assertEquals("hit", SpellSounds.key(events, "external:impact"));
        assertEquals("end", SpellSounds.key(events, "external:block_contact"));
        assertEquals("summon", SpellSounds.key(events, "external:summon"), "exact event name wins");
        assertNull(SpellSounds.key(events, "external:launch"), "unlisted events stay silent");
        assertNull(SpellSounds.key(null, "cast"));
    }
}
