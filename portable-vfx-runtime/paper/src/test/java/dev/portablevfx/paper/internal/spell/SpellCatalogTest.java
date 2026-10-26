package dev.portablevfx.paper.internal.spell;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpellCatalogTest {
    private static SpellCatalog.Spell spell(String id,int n,List<String> aliases,boolean enabled) {
        return new SpellCatalog.Spell(n,id,"이름", "magic.learned."+id,"",5d,1.5d,true,enabled,
                aliases,enabled?List.of(new SpellCatalog.Phase("cast","claude:fireball/projectile","caster",0,20)):List.of());
    }
    @Test void resolvesOnlyExplicitStableIdsAndAliases() {
        SpellCatalog c=new SpellCatalog(List.of(spell("feather_fall",1,List.of("페더 폴"),true)));
        assertEquals("feather_fall",c.resolve(" FEATHER_FALL ").orElseThrow().id());
        assertTrue(c.resolve("페더   폴").isPresent());
        assertTrue(c.resolve("1").isEmpty());assertTrue(c.resolve("feather_step").isEmpty());
    }
    @Test void rejectsAliasAndIdentityCollisions() {
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog(List.of(spell("a",1,List.of("같음"),false),spell("b",2,List.of("같음"),false))));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog(List.of(spell("a",1,List.of(),false),spell("b",1,List.of(),false))));
    }
    @Test void unknownCostsAllowedOnlyWhenDisabled() {
        assertDoesNotThrow(()->new SpellCatalog.Spell(83,"healing_rain","치유의 비","magic.learned.healing_rain","",null,null,true,false,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Spell(83,"healing_rain","치유의 비","magic.learned.healing_rain","",null,null,true,true,List.of(),List.of()));
    }
    @Test void strictSchemaDoesNotInferMissingBindings() {
        Map<String,Object> row=new HashMap<>(Map.of("number",299,"name","그림자 통행","permission","magic.learned.shadow_passage","mana-cost",10,"cooldown-seconds",1.5));
        SpellCatalog c=SpellCatalog.fromMap(Map.of("schema-version",1,"spells",Map.of("shadow_passage",row)));
        assertFalse(c.resolve("shadow_passage").orElseThrow().enabled());
        assertEquals(1.5,c.resolve("shadow_passage").orElseThrow().cooldownSeconds());
        row.put("number",299.5);
        assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMap(Map.of("schema-version",1,"spells",Map.of("shadow_passage",row))));
    }
    @Test void lenientLoadSkipsOnlyBrokenOrCollidingSpells() {
        Map<String,Object> good=new HashMap<>(Map.of("number",1,"name","좋은 마법","permission","magic.learned.good","aliases",List.of("좋음")));
        Map<String,Object> badNumber=new HashMap<>(Map.of("number",2.5,"name","나쁜 번호","permission","magic.learned.bad"));
        Map<String,Object> sameNumber=new HashMap<>(Map.of("number",1,"name","번호 중복","permission","magic.learned.dup"));
        Map<String,Object> sameAlias=new HashMap<>(Map.of("number",3,"name","별칭 중복","permission","magic.learned.alias","aliases",List.of("좋음")));
        Map<String,Object> enabledWithoutCost=new HashMap<>(Map.of("number",4,"name","비용 없음","permission","magic.learned.cost","reviewed",true,"enabled",true));
        Map<String,Object> alsoGood=new HashMap<>(Map.of("number",5,"name","또 좋은 마법","permission","magic.learned.also"));
        Map<String,Object> spells=new LinkedHashMap<>();
        spells.put("good",good);spells.put("bad_number",badNumber);spells.put("same_number",sameNumber);spells.put("same_alias",sameAlias);
        spells.put("no_cost",enabledWithoutCost);spells.put("not_a_map","text");spells.put("also_good",alsoGood);
        Map<String,String> problems=new LinkedHashMap<>();
        SpellCatalog c=SpellCatalog.fromMapLenient(Map.of("schema-version",1,"spells",spells),problems);
        assertEquals(List.of("good","also_good"),c.entries().stream().map(SpellCatalog.Spell::id).toList());
        assertEquals(Set.of("bad_number","same_number","same_alias","no_cost","not_a_map"),problems.keySet());
        assertEquals("good",c.resolve("좋음").orElseThrow().id());
        // 건너뛴 마법의 번호/별칭은 뒤따르는 마법을 막지 않는다.
        Map<String,Object> reuse=new HashMap<>(Map.of("number",3,"name","재사용","permission","magic.learned.reuse"));
        spells.put("reuse",reuse);problems.clear();
        assertTrue(SpellCatalog.fromMapLenient(Map.of("schema-version",1,"spells",spells),problems).resolve("reuse").isPresent());
        // 파일 구조 오류는 전체 거부(이전 카탈로그 유지)로 남는다.
        assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMapLenient(Map.of("schema-version",2,"spells",spells),new HashMap<>()));
        assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMapLenient(Map.of("schema-version",1),new HashMap<>()));
        assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMap(Map.of("schema-version",1,"spells",spells)));
    }
    @Test void authorityRequiredAndCalledOnceWithoutInventingMana() {
        SpellCatalog catalog=new SpellCatalog(List.of(spell("a",1,List.of(),true),spell("b",2,List.of(),false)));
        UUID player=UUID.randomUUID();
        assertEquals(SpellCastController.Status.NO_AUTHORITY,new SpellCastController(catalog,null,null).cast(player,"a").status());
        int[] calls={0,0};
        SpellCastController c=new SpellCastController(catalog,(p,s,run)->{calls[0]++;return run.get()?SpellCastController.Status.CAST:SpellCastController.Status.PLAYBACK_FAILED;},(p,s)->{calls[1]++;return true;});
        assertEquals(SpellCastController.Status.NOT_READY,c.cast(player,"b").status());
        assertArrayEquals(new int[]{0,0},calls);
        assertEquals(SpellCastController.Status.CAST,c.cast(player,"a").status());
        assertArrayEquals(new int[]{1,1},calls);
    }
    @Test void authorityCannotInvokeTwice() {
        SpellCatalog catalog=new SpellCatalog(List.of(spell("a",1,List.of(),true)));
        SpellCastController c=new SpellCastController(catalog,(p,s,run)->{run.get();run.get();return SpellCastController.Status.CAST;},(p,s)->true);
        assertThrows(IllegalStateException.class,()->c.cast(UUID.randomUUID(),"a"));
    }
    @Test void canonicalEffectsRejectWrongCaseOrTraversal() {
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Phase("cast","claude:stormcrown/projO","caster",0,1));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Phase("cast","claude:x/../bad","caster",0,1));
    }

    @Test void mechanicOwnedMobEffectsRemainExplicitAndParticipateInReadiness(){
        var refs=List.of("claude:kitsunefox/shot_normal","claude:kitsunefox/hit_normal");
        var mob=new SpellCatalog.Spell(300,"fox_orbit","여우불 공전","magic.learned.fox_orbit","",30d,8d,true,true,List.of(),List.of(),refs);
        assertTrue(mob.mechanicVfx());assertTrue(mob.phases().isEmpty());assertEquals(Set.copyOf(refs),new SpellCatalog(List.of(mob)).mechanicEffects());
        var off=new SpellCatalog.Spell(300,"fox_orbit","여우불 공전","magic.learned.fox_orbit","",30d,8d,true,false,List.of(),List.of(),refs);
        assertTrue(new SpellCatalog(List.of(off)).mechanicEffects().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Spell(299,"fox_orbit","x","permission","",30d,8d,true,true,List.of(),List.of(),refs));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Spell(300,"other","x","permission","",30d,8d,true,true,List.of(),List.of(),refs));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Spell(300,"fox_orbit","x","permission","",null,8d,true,true,List.of(),List.of(),refs));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog.Spell(300,"fox_orbit","x","permission","",30d,8d,false,true,List.of(),List.of(),refs));
    }
    @Test void mechanicModeCannotSilentlyEnableEmptyOrUnboundSpells(){
        var row=new HashMap<String,Object>();row.put("number",300);row.put("name","여우불 공전");row.put("permission","magic.learned.fox_orbit");row.put("mana-cost",30);row.put("cooldown-seconds",8);row.put("reviewed",true);row.put("enabled",true);row.put("preparation-status","mob-skill-vfx-direct");row.put("phases",List.of());
        assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMap(Map.of("schema-version",1,"spells",Map.of("fox_orbit",row))));
        row.put("required-effects",List.of("claude:kitsunefox/shot_normal","claude:kitsunefox/hit_normal"));assertTrue(SpellCatalog.fromMap(Map.of("schema-version",1,"spells",Map.of("fox_orbit",row))).resolve("fox_orbit").orElseThrow().mechanicVfx());
        row.remove("preparation-status");assertThrows(IllegalArgumentException.class,()->SpellCatalog.fromMap(Map.of("schema-version",1,"spells",Map.of("fox_orbit",row))));
    }
    @Test void onlyApprovedArcanaDisplaySlotsCanOverlapDisabledLegacyEntries(){
        var legacy=spell("air_brake",203,List.of(),false);
        var arcana=spell("landing_airbrake",203,List.of(),true);
        var catalog=new SpellCatalog(List.of(legacy,arcana));
        assertEquals(2,catalog.entries().size());assertFalse(catalog.resolve("air_brake").orElseThrow().enabled());
        assertTrue(catalog.resolve("landing_airbrake").orElseThrow().enabled());assertTrue(catalog.resolve("203").isEmpty());
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog(List.of(spell("air_brake",203,List.of(),true),arcana)));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog(List.of(spell("other",203,List.of(),false),arcana)));
        assertThrows(IllegalArgumentException.class,()->new SpellCatalog(List.of(legacy,arcana,arcana)));
    }

}
