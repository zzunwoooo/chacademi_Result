package school.chacademia.wildlife;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BiomeCompatibilityTest {
 private YamlConfiguration resource(String name){return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/"+name)),StandardCharsets.UTF_8));}
 private BiomeCompatibility aliases(){return BiomeCompatibility.parse(resource("biome-compatibility.yml").getConfigurationSection("vanilla-biomes"));}
 private Habitat habitat(String name){return Habitat.parse(name,resource("habitats.yml").getConfigurationSection("habitats."+name),new ArrayList<>());}
 @Test void actualPlayerPlainsCanHostTheExistingNightSkunkWithoutChangingItsRestrictions(){
  var h=habitat("skunk");assertFalse(h.biomes().contains("minecraft:plains"));assertTrue(aliases().matches(h.biomes(),"minecraft:plains"));
  assertEquals(Habitat.NIGHT,h.time());assertEquals(1,h.groupMin());assertEquals(1,h.groupMax());assertEquals(6,h.cap());assertTrue(h.naturalGround());assertEquals(Set.of("terralith:brushland","terralith:forested_highlands"),h.biomes());
 }
 @Test void plainsDoNotBecomeAnAllowAllForUnrelatedSpecies(){
  for(String id:List.of("owl","bear_polar","wyvern_ender","wyvern_inferno"))assertFalse(aliases().matches(habitat(id).biomes(),"minecraft:plains"),id);
 }
 @Test void everyOriginalTerralithHabitatRemainsAnExactMatch(){
  var rows=resource("habitats.yml").getConfigurationSection("habitats");var compatibility=aliases();
  for(String id:rows.getKeys(false))for(String biome:habitat(id).biomes())assertTrue(compatibility.matches(habitat(id).biomes(),biome),id+" "+biome);
 }
 @Test void counterBiomesAreExplicitAndDoNotLeakIntoNetherEndOrUnknownBiomes(){
  var c=aliases();var land=Set.of("terralith:steppe");assertTrue(c.matches(land,"minecraft:plains"));
  for(String biome:List.of("minecraft:nether_wastes","minecraft:the_end","minecraft:made_up","other:plains","minecraft:desert"))assertFalse(c.matches(land,biome),biome);
 }
 @Test void vanillaCavesKeepTheExistingUndergroundPathInsteadOfTheSurfaceSkylightRule(){
  for(String biome:List.of("minecraft:lush_caves","minecraft:dripstone_caves","minecraft:deep_dark","terralith:cave/fungal_caves"))assertTrue(BiomeCompatibility.cave(biome));
  for(String biome:List.of("minecraft:plains","minecraft:forest","terralith:forested_highlands","other:cave/anything"))assertFalse(BiomeCompatibility.cave(biome));
  assertTrue(aliases().matches(Set.of("terralith:cave/underground_jungle"),"minecraft:lush_caves"));
 }
 @Test void forestDesertSnowAndWaterStayInTheirEcologicalCounterparts(){
  var c=aliases();assertTrue(c.matches(Set.of("terralith:forested_highlands"),"minecraft:forest"));assertTrue(c.matches(Set.of("terralith:ancient_sands"),"minecraft:desert"));
  assertTrue(c.matches(Set.of("terralith:snowy_shield"),"minecraft:snowy_plains"));assertTrue(c.matches(Set.of("terralith:deep_warm_ocean"),"minecraft:warm_ocean"));
  assertFalse(c.matches(Set.of("terralith:deep_warm_ocean"),"minecraft:plains"));assertFalse(c.matches(Set.of("terralith:ancient_sands"),"minecraft:forest"));
 }
 @Test void allBundledMappingsUseExistingTerralithHabitatsAndHaveNoWildcards(){
  var original=resource("habitats.yml").getConfigurationSection("habitats");var allowed=new HashSet<String>();for(String id:original.getKeys(false))allowed.addAll(habitat(id).biomes());
  var rows=resource("biome-compatibility.yml").getConfigurationSection("vanilla-biomes");assertEquals(54,rows.getKeys(false).size());
  for(String id:rows.getKeys(false)){assertTrue(id.matches("minecraft:[a-z0-9_]+"));for(String value:rows.getStringList(id))assertTrue(allowed.contains(value),value);}
 }
 @Test void terralithBiomesWithoutOwnHabitatReuseSimilarHabitats(){
  var y=resource("biome-compatibility.yml");var c=BiomeCompatibility.parse(y.getConfigurationSection("vanilla-biomes"),y.getConfigurationSection("terralith-biomes"));
  assertTrue(c.matches(habitat("elk").biomes(),"terralith:yellowstone"));
  assertFalse(c.matches(habitat("owl").biomes(),"terralith:yellowstone"));
  var original=resource("habitats.yml").getConfigurationSection("habitats");var allowed=new HashSet<String>();for(String id:original.getKeys(false))allowed.addAll(habitat(id).biomes());
  var rows=y.getConfigurationSection("terralith-biomes");
  for(String id:rows.getKeys(false)){assertTrue(id.matches("terralith:[a-z0-9_/]+"));for(String value:rows.getStringList(id))assertTrue(allowed.contains(value),value);}
 }
 @Test void builtVersionAndCommandsMovedToTheHub(){
  // 2026-10-20: /야생관리 moved to /월드관리 야생몹 · /테스트 몹 작물몹 (MagicCodexBridge command hub); no plugin.yml commands remain.
  var plugin=resource("plugin.yml");assertEquals("0.5.1-feedback-20261023",plugin.getString("version"));
  assertNull(plugin.getConfigurationSection("commands"));
  assertTrue(plugin.getStringList("softdepend").contains("MagicCodexBridge"));
 }
 @Test void malformedOrWildcardMappingsAreRejectedInsteadOfOpeningAllHabitats(){
  assertThrows(IllegalArgumentException.class,()->BiomeCompatibility.parse(null));
  for(String body:List.of("'minecraft:*': ['terralith:steppe']","'minecraft:plains': []","'minecraft:plains': ['*']","'minecraft:plains': ['terralith:steppe','terralith:steppe']","'minecraft:plains': true")){
   var y=YamlConfiguration.loadConfiguration(new StringReader("vanilla-biomes:\n  "+body+"\n"));assertThrows(IllegalArgumentException.class,()->BiomeCompatibility.parse(y.getConfigurationSection("vanilla-biomes")),body);
  }
 }
}
