package school.magiccodex.discovery;
import org.junit.jupiter.api.Test;import org.bukkit.configuration.file.YamlConfiguration;import java.nio.file.*;import java.util.*;import java.util.regex.*;import static org.junit.jupiter.api.Assertions.*;
/**
 * 2026-10-26 발견 조건 v2 (docs/plans/discovery-conditions-v2.md, 오너 결정): 도감 300종 = 서버 조건 299종(아공간 수조는 처음부터 앎),
 * 도감 문구 = 서버 문구, 수동 조건은 수업·책·스토리가 실제로 주는 마법만, 300종 밖 옛 마법·조건 없는 마법 없음.
 */
class CurrentReviewedMappingTest {
 static final Path SPELLS=Path.of("../magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells");
 static Map<String,YamlConfiguration> codex()throws Exception{var out=new TreeMap<String,YamlConfiguration>();try(var files=Files.list(SPELLS)){for(var path:files.filter(p->p.getFileName().toString().matches("[0-9]{3}_.*\\.yml")).toList()){var y=YamlConfiguration.loadConfiguration(path.toFile());out.put(y.getString("id"),y);}}return out;}
 static Definitions definitions()throws Exception{var d=new Definitions(Path.of("src/main/resources/discoveries.yml").toFile());d.manual(Path.of("src/main/resources/manual-conditions.yml").toFile());return d;}
 @Test void everyCodexSpellHasExactlyOneJudgedConditionWithTheSameText()throws Exception{
  var codex=codex();var d=definitions();assertEquals(300,codex.size());
  var expected=new TreeSet<>(codex.keySet());expected.remove("fish_trap");assertEquals(expected,new TreeSet<>(d.spells.keySet()));
  for(var e:codex.entrySet()){if(e.getKey().equals("fish_trap"))continue;var s=d.spells.get(e.getKey());
   assertEquals(e.getValue().getString("discovery.description"),s.description(),e.getKey());assertEquals(e.getValue().getString("category"),s.category(),e.getKey());
   assertFalse(s.requirements().isEmpty()&&s.prerequisites().isEmpty(),e.getKey());}
  assertTrue(Definitions.PENDING.isEmpty());assertFalse(d.spells.containsKey("feather_step"));assertFalse(d.spells.containsKey("wind_vault"));
 }
 @Test void manualConditionsAreExactlyTheOnesLessonsBooksAndStoryGrant()throws Exception{
  var granted=new TreeSet<String>();var pattern=Pattern.compile("마법조건 주기 ([a-z0-9_]+)");
  for(var file:List.of("../magic-codex-paper/src/main/resources/books.yml","../chacademy-story/plugin/src/main/resources/steps.yml")){
   var m=pattern.matcher(Files.readString(Path.of(file)).replaceAll("(?m)^\\s*#.*$",""));while(m.find())granted.add(m.group(1));}
  assertEquals(granted,new TreeSet<>(definitions().manual.keySet()));
  assertTrue(granted.containsAll(List.of("guiding_star","rewind","statue_wake","void_maw","season_sense")));assertFalse(granted.contains("arcane_imprint"));
 }
 @Test void ultimateAndMeteorUseLearnedElementCounts()throws Exception{
  var d=definitions();
  assertEquals(Map.of("learned.fire",20d),d.spells.get("falling_star").requirements());assertEquals(Map.of("learned.fire",30d),d.spells.get("flame_emperor").requirements());
  assertEquals(Map.of("learned.wind",30d),d.spells.get("heaven_cyclone").requirements());assertEquals(Map.of("learned.earth",35d),d.spells.get("titan_stride").requirements());
  assertEquals(Map.of("learned.water",30d,"fishing.legendary",1d),d.spells.get("leviathan_rise").requirements());
  assertEquals(Map.of("kill.fire_low_health",1d),d.spells.get("crimson_heart").requirements());
  assertEquals(Map.of("arrow.distant_hit",10d),d.spells.get("storm_crown").requirements());assertEquals(Map.of("state.friends",10d),d.spells.get("guardian_oath").requirements());
  assertEquals(Map.of("shield.block",10d),d.spells.get("earth_anchor").requirements());assertEquals(Map.of("shield.block",30d),d.spells.get("flawless_moment").requirements());
  assertEquals(Map.of("pickup.items",300d),d.spells.get("wind_basket").requirements());assertEquals(Map.of("drink.milk_poisoned",3d),d.spells.get("purifying_wave").requirements());
 }
}
