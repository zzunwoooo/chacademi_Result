package school.chacademia.gimmick;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelDefinitionTest {
 @Test void collisionOpenStatesRemainStringsAndRetainedModelsHaveBlueprints()throws Exception{
  var y=new YamlConfiguration();y.load(Path.of("src/main/resources/models.yml").toFile());var models=y.getConfigurationSection("models");assertNotNull(models);assertEquals(37,models.getKeys(false).size());
  int opening=0;
  for(String id:models.getKeys(false)){
   assertTrue(Files.isRegularFile(Path.of("models",id+".bbmodel")),id);
   Object open=models.get(id+".open");if(open!=null){assertInstanceOf(String.class,open,id);assertTrue(Set.of("on","off").contains(open),id);opening++;}
   assertEquals(2,models.getIntegerList(id+".footprint").size(),id);
  }
  assertTrue(opening>=5);
 }
}
