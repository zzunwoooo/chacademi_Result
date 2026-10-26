package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WildDefinitionsTest {
 @Test void splitWildDefinitionsAreGroundedAndRareStatsMatchWithoutPetChanges() throws Exception {
  Path root=Path.of("..").toAbsolutePath().normalize();
  var split=YamlConfiguration.loadConfiguration(root.resolve("reference/output/wildlife-ground-v3/chacademia_custom_wild.yml").toFile());
  var combined=YamlConfiguration.loadConfiguration(root.resolve("reference/output/custom-creatures-v1/server/plugins/MythicMobs/Mobs/chacademia_custom.yml").toFile());
  assertEquals(276,split.getKeys(false).size());
  for(String id:split.getKeys(false)){
   assertTrue(id.startsWith("CA_Wild_"));assertFalse(Set.of("VEX","PARROT","PHANTOM","GHAST","BLAZE","BAT","ALLAY","BEE","ENDER_DRAGON").contains(split.getString(id+".Type")));
   for(String key:split.getConfigurationSection(id).getKeys(true))if(!split.isConfigurationSection(id+"."+key)) {
    if(key.equals("Options.FollowRange")) assertEquals(32,split.getInt(id+"."+key),id);
    else if(key.equals("Options.MovementSpeed")) assertEquals(Math.round(combined.getDouble(id+"."+key)*1.35*1000)/1000d,split.getDouble(id+"."+key),1e-9,id);
    else if(key.equals("Health")||key.equals("Damage")) continue; // 몹 스펙 #109: tools/mob_spec.py 등급 규칙 (아래)
    else assertEquals(combined.get(id+"."+key),split.get(id+"."+key),id+"."+key);
   }
  }
  for(String color:List.of("green","ender","inferno","undead"))for(String shiny:List.of("","_shiny")){
   String id="CA_Wild_wyvern_"+color+shiny;assertEquals("HUSK",split.getString(id+".Type"));assertTrue(split.getStringList(id+".AIGoalSelectors").contains("meleeattack"));assertEquals(380,split.getDouble(id+".Health"));assertEquals(8,split.getDouble(id+".Damage"));assertEquals(.389,split.getDouble(id+".Options.MovementSpeed"));
   assertEquals("PARROT",combined.getString("CA_Pet_wyvern_"+color+shiny+".Type"));assertTrue(combined.getBoolean("CA_Pet_wyvern_"+color+shiny+".Options.Invincible"));
  }
  // 몹 스펙 #109 (2026-10-26): 1성 40 · 2성 60 (근접 3) · 3성 380 (근접 8) · 보스 2000 (근접 24), 원래 근접 0 이면 0
  assertEquals(2000,split.getDouble("CA_Wild_glacialcore.Health"));assertEquals(24,split.getDouble("CA_Wild_glacialcore.Damage"));
  assertEquals(40,split.getDouble("CA_Wild_squirrel.Health"));assertEquals(0,split.getDouble("CA_Wild_squirrel.Damage"));
  assertEquals(60,split.getDouble("CA_Wild_bear.Health"));assertEquals(3,split.getDouble("CA_Wild_bear.Damage"));
  assertEquals(60,split.getDouble("CA_Wild_elk.Health"));assertEquals(0,split.getDouble("CA_Wild_elk.Damage"));
  assertEquals(380,split.getDouble("CA_Wild_nocsy_cat_fallen.Health"));
  assertEquals(0,split.getDouble("CA_Wild_nocsy_cat_fallen.Damage"));
 }
}
