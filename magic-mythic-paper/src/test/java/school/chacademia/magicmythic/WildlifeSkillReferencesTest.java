package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class WildlifeSkillReferencesTest {
 @Test void allRangedMappingsResolveExistingWildMobsAndSkills(){
  var root=Path.of("..").toAbsolutePath().normalize();var mapping=YamlConfiguration.loadConfiguration(root.resolve("creature-spawns-paper/src/main/resources/ranged-skills.yml").toFile()).getConfigurationSection("ranged-skills");var mobs=YamlConfiguration.loadConfiguration(root.resolve("reference/output/wildlife-ground-v3/chacademia_custom_wild.yml").toFile());var skills=YamlConfiguration.loadConfiguration(root.resolve("magic-mythic-paper/mythicmobs/Skills/chacademi_mob_skills.yml").toFile());assertNotNull(mapping);assertFalse(mapping.getKeys(false).isEmpty());
  for(String mob:mapping.getKeys(false)){assertTrue(mob.startsWith("CA_Wild_"));assertTrue(mobs.contains(mob),mob);assertFalse(mapping.getStringList(mob).isEmpty());for(String skill:mapping.getStringList(mob))assertTrue(skills.contains(skill),mob+" -> "+skill);}
 }
}
