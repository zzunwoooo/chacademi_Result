package school.magiccodex.paper;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MobSpellDeploymentConfigTest {
 @Test void enabledMobSpellsHaveIdenticalFreshInstallAndMythicDeploymentRoutes()throws Exception{
  var ids=List.of("fox_orbit","curving_plumes","crystal_orbit","venom_spit","smoke_veil","domain_stomp","seal_circle","seal_talismans","shield_slam","chain_arrow","mana_drain","soul_scythe","oblivion_orbs","bone_grasp","shadow_leap","binding_pull","fairy_dust","moon_wind","gale_wings","feather_storm","hunting_pounce","roar_leap","gravity_slam","tail_spikes");
  var routes=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/spell-runtime.yml").toFile());
  var mythic=YamlConfiguration.loadConfiguration(Path.of("../magic-mythic-paper/server-config/spell-runtime.yml").toFile());
  var mana=YamlConfiguration.loadConfiguration(Path.of("src/main/resources/mana-spells.yml").toFile());
  var loaded=SpellRules.load(Path.of("src/main/resources/spell-runtime.yml").toFile());
  for(var id:ids){
   String key="spells."+id;
   assertTrue(mana.getBoolean(key+".enabled"),id);assertTrue(loaded.containsKey(id),id);
   assertNotNull(routes.getConfigurationSection(key),id);assertNotNull(mythic.getConfigurationSection(key),id);
   assertEquals(mythic.getConfigurationSection(key).getValues(true),routes.getConfigurationSection(key).getValues(true),id);
   assertEquals("CHA_"+id,routes.getString(key+".skill"));
  }
 }
}
