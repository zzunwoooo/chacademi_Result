package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class NameplateStoreTest {
 @TempDir Path temp;
 @Test void restartReloadAndResetPreserveStableIdentity()throws Exception{Path p=temp.resolve("nameplates.yml");String key="character-656c6c6e61";var data=Map.of(key,new NameplateStore.Label("엘레나","","","교수"));NameplateStore.save(p,data);assertEquals(data,NameplateStore.load(p));NameplateStore.save(p,Map.of());assertEquals(Map.of(),NameplateStore.load(p));}
 @Test void failedReplacementKeepsOriginal()throws Exception{Path p=temp.resolve("nameplates.yml");var data=Map.of("entity-abc",new NameplateStore.Label("수위","","","NPC"));NameplateStore.save(p,data);Files.createDirectory(temp.resolve("nameplates.yml.tmp"));assertThrows(Exception.class,()->NameplateStore.save(p,Map.of()));assertEquals(data,NameplateStore.load(p));}
 @Test void malformedLabelsCannotSilentlyOverwriteStorage()throws Exception{Path p=temp.resolve("nameplates.yml");Files.writeString(p,"labels:\n  entity-abc:\n    name: ''\n    title: NPC\n");assertThrows(IllegalArgumentException.class,()->NameplateStore.load(p));assertTrue(Files.readString(p).contains("entity-abc"));}
 @Test void identifiersCannotEscapeYamlPath(){assertThrows(Exception.class,()->NameplateStore.save(temp.resolve("a.yml"),Map.of("bad.key",new NameplateStore.Label("가","","",""))));}
 @Test void unreadableYamlFailsClosed()throws Exception{Path p=temp.resolve("bad.yml");Files.writeString(p,"labels: [invalid");assertThrows(IllegalArgumentException.class,()->NameplateStore.load(p));assertEquals("labels: [invalid",Files.readString(p));}
}
