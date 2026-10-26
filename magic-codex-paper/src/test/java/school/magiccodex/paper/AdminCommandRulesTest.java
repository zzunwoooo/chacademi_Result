package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AdminCommandRulesTest {
    @Test void statsUseBaseValuesNotEquipmentTotals(){
        var account=new ManaAccount(80,100,5);account.modifier("test:equipment",50,2);account.baseHaste=10;account.hasteModifier("test:equipment",20);
        account.baseMaximum=AdminCommandRules.changed(account.baseMaximum,"추가",20,1_000_000);
        account.baseRegen=AdminCommandRules.changed(account.baseRegen,"설정",7,1_000_000);
        account.baseHaste=AdminCommandRules.changed(account.baseHaste,"감소",3,1_000_000);
        assertEquals(120,account.baseMaximum);assertEquals(170,account.snapshot().maximum());assertEquals(9,account.snapshot().regeneration());assertEquals(27,account.snapshot().haste());
    }
    @Test void statsCannotOverflowAndDecreaseStopsAtZero(){
        assertEquals(0,AdminCommandRules.changed(10,"감소",50,1_000_000));
        assertEquals(1_000_000,AdminCommandRules.changed(0,"설정",1_000_000,1_000_000));
        for(String invalid:List.of("NaN","Infinity","-1","1000001","bad"))assertThrows(IllegalArgumentException.class,()->AdminCommandRules.nonnegative(invalid,1_000_000));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.changed(1_000_000,"추가",1,1_000_000));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.changed(1,"set",1,1_000_000));
    }
    @Test void scoresHaveExplicitDecreaseAndExistingSignedSetRange(){
        assertEquals(-10,AdminCommandRules.scoreValue("감소",10));assertEquals(10,AdminCommandRules.scoreValue("추가",10));assertEquals(-10,AdminCommandRules.scoreValue("설정",-10));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.scoreValue("감소",-1));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.scoreValue("설정",Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.scoreValue("설정",1_000_000_001));
    }
    @Test void corePercentAndGainBoundsMatchEnhancementSchema(){
        assertDoesNotThrow(()->AdminCommandRules.validateCore(10,100,0,10000,2));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.validateCore(0,50,10,1,0));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.validateCore(1,101,10,1,0));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.validateCore(1,50,Double.NaN,1,0));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.validateCore(1,50,10,0,0));
        assertThrows(IllegalArgumentException.class,()->AdminCommandRules.validateCore(1,50,10,1,3));
        assertEquals(2,AdminCommandRules.affinity("마법가속"));assertEquals(0,AdminCommandRules.affinity("지능"));assertEquals(0,AdminCommandRules.affinity("마력"));assertThrows(IllegalArgumentException.class,()->AdminCommandRules.affinity("임의성향"));
    }
    @Test void onlyRegisteredEnabledTitlesCanBeGranted(){
        var enabled=new TitleDefinition("good",0,"별빛",1,true,false);var disabled=new TitleDefinition("old",0,"옛 칭호",1,false,false);
        assertTrue(AdminCommandRules.titleAllowed(enabled,false));assertFalse(AdminCommandRules.titleAllowed(disabled,false));assertFalse(AdminCommandRules.titleAllowed(null,false));assertFalse(AdminCommandRules.titleAllowed(null,true));
        assertTrue(AdminCommandRules.titleAllowed(disabled,true));
    }
    @Test void catalogNamesResolveStrictlyAndAmbiguityIsNeverGuessed(){
        var catalog=Map.of("first","별빛","second","별빛","wind","바람 전령");
        assertNull(AdminCommandRules.resolve(catalog,"별빛"));assertNull(AdminCommandRules.resolve(catalog,"임의 칭호"));assertEquals("first",AdminCommandRules.resolve(catalog,"first"));assertEquals("wind",AdminCommandRules.resolve(catalog,"바람 전령"));
        assertFalse(AdminCommandRules.catalogChoices(catalog).contains("별빛"));assertTrue(AdminCommandRules.catalogChoices(catalog).contains("바람전령"));
    }
    @Test void spacedNamesResolveWithoutSpacesAndSuggestCompactNames(){
        var catalog=Map.of("wind","바람 전령","fire","불꽃","a","같은 이름","b","같은 이름");
        assertEquals("wind",AdminCommandRules.resolve(catalog,"바람전령"));assertEquals("wind",AdminCommandRules.resolve(catalog,"바람 전령"));assertNull(AdminCommandRules.resolve(catalog,"같은이름"));
        var choices=AdminCommandRules.catalogChoices(catalog);assertTrue(choices.contains("바람전령"));assertTrue(choices.contains("a"));assertFalse(choices.contains("같은이름"));
        assertTrue(choices.indexOf("바람전령")<choices.indexOf("a"));
    }
    @Test void newVerbsAreUsedForStatsAndScores(){
        assertEquals(List.of("설정","추가","빼기"),AdminCommandRules.CHANGES);assertEquals(5,AdminCommandRules.changed(10,"빼기",5,100));assertEquals(-3,AdminCommandRules.scoreValue("빼기",3));assertEquals(0,AdminCommandRules.scoreValue("보기",0));
    }
    @Test void adminCatalogIncludesEveryRegisteredSpellWithoutChangingCastingEnablement()throws Exception{
        var catalog=ManaSpells.load(java.nio.file.Path.of("src/main/resources/mana-spells.yml").toFile());
        assertEquals(300,catalog.registeredCatalog.size()); // 2026-10-25: +63 new +12 life spells; 2026-10-26: 꺼진 12개는 기획에서 삭제
        assertFalse(catalog.registeredCatalog.containsKey("aerial_haul"));assertFalse(catalog.byId.containsKey("aerial_haul"));
        catalog.byId.forEach((id,spell)->assertEquals(spell.permission(),catalog.registeredCatalog.get(id).get("permission")));
        assertThrows(UnsupportedOperationException.class,()->catalog.registeredCatalog.put("forged",Map.of()));
    }
}
