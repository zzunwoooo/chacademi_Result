package school.magiccodex.paper;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GimmickCatalogIntegrationTest {
 static final List<String> IDS=List.of("thawing_heat","steam_ignition","water_level","stone_weight","vine_awaken","light_beam","mirror_tune","prism_tint","statue_wake","rewind","conduit_turn","dimension_mark");
 static YamlConfiguration load(String p)throws Exception{var y=new YamlConfiguration();y.load(Path.of(p).toFile());return y;}
 @Test void newGimmickSpellsKeepApprovedProvisionalCostsAreDonatableAndNoInventedDiscovery()throws Exception{
  var mana=load("src/main/resources/mana-spells.yml");var routes=load("src/main/resources/spell-runtime.yml");var donor=load("src/main/resources/donation-spells.yml");var discovery=load("../magic-discovery-paper/src/main/resources/discoveries.yml");
  double[] costs={40,30,20,30,40,50,15,15,20,30,10,40},cooldowns={8,6,1.5,5,10,6,1,1,2,5,1,5};
  String[] elements={"fire","fire","water","earth","earth","light","light","light","dark","dark","earth","dark"};
  for(int i=0;i<IDS.size();i++){
   String id=IDS.get(i);assertTrue(mana.getBoolean("spells."+id+".enabled"),id);assertEquals(costs[i],mana.getDouble("spells."+id+".mana-cost"),id);assertEquals(cooldowns[i],mana.getDouble("spells."+id+".cooldown-seconds"),id);
   assertEquals("magic.learned."+id,mana.getString("spells."+id+".permission"),id);assertEquals("CHA_"+id,routes.getString("spells."+id+".skill"),id);assertFalse(routes.getBoolean("spells."+id+".utility"),id);
   assertEquals("magic.learned."+id,donor.getString("spells."+id+".permission"),id);assertTrue(discovery.contains("spells."+id),id); /* 2026-10-26 v2: 발견 조건 정함 (docs/plans/discovery-conditions-v2.md) */
   int[] numbers={63,64,114,170,171,225,209,210,255,264,155,265}; // 2026-10-25 renumber (magic-mythic-paper/tools/spell_order.py)
   var client=load("../magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells/"+String.format("%03d",numbers[i])+"_"+id+".yml");assertEquals(numbers[i],client.getInt("order"));assertEquals(elements[i],client.getString("category"),id);assertEquals(costs[i],client.getDouble("cast.mana-cost"),id);
  }
 }
 @Test void retiredUnimplementedIdsAreAbsentButFishingAndReviewedArcanaRemain()throws Exception{
  var mana=load("src/main/resources/mana-spells.yml");var ids=new HashSet<String>();
  for(String f:Files.readAllLines(Path.of("../magic-codex-fabric/src/main/resources/assets/magiccodex/default-spells/removed-defaults.txt"))){if(f.isBlank()||f.startsWith("#"))continue;String id=f.substring(f.indexOf('_')+1,f.length()-4);assertFalse(mana.contains("spells."+id),id);ids.add(id);}
  assertEquals(105,ids.size());assertTrue(mana.getBoolean("spells.fish_trap.enabled"));assertTrue(mana.contains("spells.shadow_double"));assertTrue(mana.contains("spells.misfortune_whisper"));
 }
}
