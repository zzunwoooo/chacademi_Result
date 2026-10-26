package school.chacademia.magicmythic;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MobAssetContractTest {
 @Test void mobHooksResolveActualSuppliedAnimationsAndGameplayEffects(){
  var contract=YamlConfiguration.loadConfiguration(Path.of("server-config/mob-asset-contract-20261019.yml").toFile());
  var effects=new HashSet<>(contract.getStringList("effects"));assertEquals(1038,effects.size());
  var mobs=contract.getConfigurationSection("mobs");assertNotNull(mobs);assertEquals(156,mobs.getKeys(false).size());
  var skills=YamlConfiguration.loadConfiguration(Path.of("mythicmobs/Skills/chacademi_mob_skills.yml").toFile());
  var state=Pattern.compile("state\\{s=([^;}]+)");int states=0;
  for(var mob:mobs.getKeys(false)){
   var animations=new HashSet<>(mobs.getStringList(mob+".animations"));
   for(var id:mobs.getStringList(mob+".skills")){
    assertTrue(skills.contains(id),mob+": "+id);
    for(var line:skills.getStringList(id+".Skills")){
     var match=state.matcher(line);while(match.find()){assertTrue(animations.contains(match.group(1)),mob+": "+id+" references absent animation "+match.group(1));states++;}
    }
   }
  }
  assertTrue(states>300);
  var ref=Pattern.compile("(?:[;{])(?:vfx|vfxin|vfxout|hitvfx)=((?:claude:)?[A-Za-z0-9_-]+/[A-Za-z0-9_-]+)");int referenced=0;
  for(var file:java.util.List.of("chacademi_mob_skills.yml","chacademi_spells.yml")){
   var yaml=YamlConfiguration.loadConfiguration(Path.of("mythicmobs/Skills/"+file).toFile());
   for(var id:yaml.getKeys(false))for(var line:yaml.getStringList(id+".Skills")){
    var match=ref.matcher(line);while(match.find()){
     String effect=match.group(1);if(!effect.startsWith("claude:"))effect="claude:"+effect;
     assertTrue(effects.contains(effect),id+": missing authored effect "+effect);referenced++;
    }
   }
  }
  assertTrue(referenced>400);
 }
}