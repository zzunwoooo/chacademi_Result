package school.magiccodex.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CreatureConfigFilesTest {
 @TempDir Path root;
 @Test void additivePacksPreserveBaseAndNestedProfiles()throws Exception{
  Files.writeString(root.resolve("taming.yml"),"range: 12\ntargets:\n  wolf:\n    chance: 0.35\n");
  Files.createDirectory(root.resolve("taming.d"));Files.writeString(root.resolve("taming.d/custom.yml"),"targets:\n  ca_owl:\n    mythic-id: CA_Wild_owl\n    chance: 0.2\n    roll-shiny: false\n");
  var result=CreatureConfigFiles.load(root.toFile(),"taming","targets");
  assertEquals(12,result.getInt("range"));assertEquals(.35,result.getDouble("targets.wolf.chance"));
  assertEquals("CA_Wild_owl",result.getString("targets.ca_owl.mythic-id"));assertFalse(result.getBoolean("targets.ca_owl.roll-shiny",true));
 }
 @Test void rejectsDuplicateWithoutWritingBase()throws Exception{
  String base="stars:\n  ca_owl: 2\n";Files.writeString(root.resolve("pets.yml"),base);Files.createDirectory(root.resolve("pets.d"));Files.writeString(root.resolve("pets.d/custom.yml"),"stars:\n  ca_owl: 3\n");
  assertThrows(IllegalArgumentException.class,()->CreatureConfigFiles.load(root.toFile(),"pets","stars"));assertEquals(base,Files.readString(root.resolve("pets.yml")));
 }
 @Test void scalarStarsAreLoaded()throws Exception{
  Files.writeString(root.resolve("pets.yml"),"default-stars: 1\nstars: {}\n");Files.createDirectory(root.resolve("pets.d"));Files.writeString(root.resolve("pets.d/custom.yml"),"stars:\n  ca_owl_shiny: 2\n");
  assertEquals(2,CreatureConfigFiles.load(root.toFile(),"pets","stars").getInt("stars.ca_owl_shiny"));
 }
}
