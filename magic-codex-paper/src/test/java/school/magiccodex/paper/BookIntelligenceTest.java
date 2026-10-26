package school.magiccodex.paper;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BookIntelligenceTest {
 private static YamlConfiguration yaml(String text)throws Exception{var y=new YamlConfiguration();y.loadFromString(text);return y;}
 @Test void requiredIntelligenceDefaultsToZeroAndParses()throws Exception{
  var y=yaml("a:\n  title: 책\n  pages:\n    - left: 본문\nb:\n  title: 책\n  required-intelligence: 30\n  pages:\n    - left: 본문\n");
  assertEquals(0,BookBridge.parse("a",y.getConfigurationSection("a")).requiredIntelligence());
  assertEquals(30,BookBridge.parse("b",y.getConfigurationSection("b")).requiredIntelligence());
 }
 @Test void negativeRequirementRejected()throws Exception{
  var y=yaml("c:\n  title: 책\n  required-intelligence: -1\n  pages:\n    - left: 본문\n");
  assertThrows(IllegalArgumentException.class,()->BookBridge.parse("c",y.getConfigurationSection("c")));
 }
 @Test void gate(){
  assertTrue(BookBridge.intelligenceMet(0,0));assertTrue(BookBridge.intelligenceMet(30,30));assertTrue(BookBridge.intelligenceMet(30,30.5));
  assertFalse(BookBridge.intelligenceMet(30,29.9));
 }
 @Test void bundledBooksParse()throws Exception{try(var in=getClass().getResourceAsStream("/books.yml")){var y=new YamlConfiguration();y.load(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));var root=y.getConfigurationSection("books");for(String id:root.getKeys(false))assertEquals(0,BookBridge.parse(id,root.getConfigurationSection(id)).requiredIntelligence(),id);}}
}
