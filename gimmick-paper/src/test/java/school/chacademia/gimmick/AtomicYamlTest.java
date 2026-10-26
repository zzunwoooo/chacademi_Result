package school.chacademia.gimmick;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class AtomicYamlTest {
 @TempDir Path dir;
 @Test void validSnapshotRoundtripsAndCorruptFileIsPreserved()throws Exception{var file=dir.resolve("gimmicks.yml");var yaml=new YamlConfiguration();yaml.set("next-id",3);AtomicYaml.save(yaml,file.toFile());assertEquals(3,AtomicYaml.load(file.toFile()).getInt("next-id"));Files.writeString(file,"invalid: [");assertThrows(IllegalStateException.class,()->AtomicYaml.load(file.toFile()));assertEquals("invalid: [",Files.readString(file));}
}
