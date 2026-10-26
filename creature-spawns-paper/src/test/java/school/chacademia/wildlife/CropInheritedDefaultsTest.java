package school.chacademia.wildlife;
import java.io.*;import java.util.*;import org.bukkit.configuration.file.YamlConfiguration;import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class CropInheritedDefaultsTest {
 @Test void inheritedJavaPluginDefaultsSupplyEveryCropWithoutSavingNewConfig()throws Exception{
  var live=YamlConfiguration.loadConfiguration(new StringReader("enabled: true\nchunk-cap: 6\narea-cap: 24\n"));
  var defaults=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"),java.nio.charset.StandardCharsets.UTF_8));
  live.setDefaults(defaults); // JavaPlugin.reloadConfig's configuration/defaults relationship.
  var plugin=mock(WildlifePlugin.class);when(plugin.getConfig()).thenReturn(live);var crops=new CropAwakening(plugin);crops.reload();
  System.out.println("INHERITED_CROP_DEFAULTS enabled="+live.getBoolean("crop-awakening.enabled",true)+" chance="+live.getDouble("crop-awakening.chance",0.05)+" ids="+crops.ids()+" copyDefaults="+live.options().copyDefaults());
  assertEquals(.05,live.getDouble("crop-awakening.chance",0.05));assertTrue(live.getBoolean("crop-awakening.enabled",true));
  assertEquals(Set.of("wheat","carrot","potato","beetroot","pumpkin","melon","nether_wart"),new HashSet<>(crops.ids()));
 }

 @Test void explicitEmptyAndCustomMappingsRemainAuthoritative()throws Exception{
  var defaults=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"),java.nio.charset.StandardCharsets.UTF_8));
  for(String yaml:List.of("crop-awakening:\n  crops: {}\n","crop-awakening:\n  chance: 0.2\n  crops:\n    WHEAT: custom_wheat\n")){
   var config=YamlConfiguration.loadConfiguration(new StringReader(yaml));config.setDefaults(defaults);var plugin=mock(WildlifePlugin.class);when(plugin.getConfig()).thenReturn(config);var crops=new CropAwakening(plugin);crops.reload();
   assertEquals(yaml.contains("custom_wheat")?Set.of("custom_wheat"):Set.of(),new HashSet<>(crops.ids()));
   assertEquals(yaml.contains("custom_wheat")?.2:.05,config.getDouble("crop-awakening.chance",.05));
  }
 }
}