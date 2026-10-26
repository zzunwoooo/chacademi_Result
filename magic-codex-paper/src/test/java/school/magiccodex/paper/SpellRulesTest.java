package school.magiccodex.paper;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpellRulesTest {
    @TempDir Path temp;
    @Test void legacyAndMobRoutesAreIsolatedFromTheCatalogIdentity()throws Exception{
        var routes=SpellRules.load(Path.of("src/main/resources/spell-runtime.yml").toFile());
        var paid=ManaSpells.load(Path.of("src/main/resources/mana-spells.yml").toFile());
        assertEquals(97,routes.size()); // 2026-10-26: cinder_detonation 기획에서 삭제 // Original compatibility file is preserved, not activated in catalogue mode.
        assertTrue(paid.catalogMode);
        assertFalse(paid.byId.containsKey("feather_step"));
        assertFalse(paid.byId.containsKey("wind_vault"));
        assertEquals("magic.learned.feather_fall",paid.byId.get("feather_fall").permission());
        for(var spell:paid.byId.values()){
            assertEquals("magic.learned."+spell.id(),spell.permission());
            assertTrue(spell.cost()>=0);assertTrue(spell.cooldown()>=0);
        }
    }
    @Test void powerChangesDamageButNeverTheManaCostOrCooldown(){
        var rule=new SpellRules.Rule("test","시험","CHA_test",SpellRules.Target.AIM,false,24,4,.35);
        assertEquals(4,rule.damage(0));assertEquals(12.4,rule.damage(24),.0001);assertEquals(39,rule.damage(100));
        assertEquals(4,rule.damage(-100));assertEquals(350004,rule.damage(Double.POSITIVE_INFINITY));
    }
    @Test void rangeMultiplierDefaultsToUnchangedAndScalesEveryRoute()throws Exception{
        assertEquals(24,SpellRules.scaledRange(24,1.0));assertEquals(36,SpellRules.scaledRange(24,1.5));assertEquals(64,SpellRules.scaledRange(48,3));assertEquals(1,SpellRules.scaledRange(1,0.25));
        var file=temp.resolve("mult.yml");String body="spells:\n  test:\n    name: 시험\n    skill: CHA_test\n    target: AIM\n    range: 20\n";
        Files.writeString(file,body);assertEquals(20,SpellRules.load(file.toFile()).get("test").range());
        Files.writeString(file,"range-multiplier: 1.5\n"+body);assertEquals(30,SpellRules.load(file.toFile()).get("test").range());
        for(String bad:new String[]{"range-multiplier: 0\n","range-multiplier: 10\n",".NaN\n".replace(".NaN","range-multiplier: .NaN")}){
            Files.writeString(file,bad+body);assertThrows(Exception.class,()->SpellRules.load(file.toFile()));
        }
    }
    @Test void invalidRangeAndNonfiniteCoefficientsDoNotReplaceValidRoutes()throws Exception{
        var file=temp.resolve("rules.yml");String valid="spells:\n  test:\n    name: 시험\n    skill: CHA_test\n    target: AIM\n    range: 24\n    base-damage: 4\n    power-ratio: 0.35\n";
        Files.writeString(file,valid);assertEquals(1,SpellRules.load(file.toFile()).size());
        for(String bad:new String[]{valid.replace("range: 24","range: 100000"),valid.replace("0.35",".NaN"),valid.replace("CHA_test","op player"),valid.replace("target: AIM","target: anywhere")}){
            Files.writeString(file,bad);assertThrows(Exception.class,()->SpellRules.load(file.toFile()));
        }
    }
}
