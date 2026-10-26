package kr.chacademy.story.dialogue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ServerDialoguesTest {
 @TempDir Path temp;
 @AfterEach void clear(){ServerDialogues.clear();DialogueRunner.packPortrait=n->false;}
 static String text(String title){return "title: "+title+"\nstart: s1\nscenes:\n  s1:\n    lines:\n    - text: hello\n";}
 @Test void serverOnlyDialogueLoadsAndListsWithoutLocalDirectory()throws Exception{ServerDialogues.put("ch1-3",text("server"));assertTrue(DialogueLoader.exists("ch1-3"));assertEquals("server",DialogueLoader.load("ch1-3").title());assertEquals(List.of("ch1-3"),DialogueLoader.listIds(temp.resolve("missing")));assertFalse(Files.exists(temp.resolve("missing")));}
 @Test void listCombinesServerAndLocalIdsWithoutDuplicates()throws Exception{Path file=temp.resolve("ch1-3/dialogue.yml");Files.createDirectories(file.getParent());Files.writeString(file,text("local"));ServerDialogues.put("ch1-3",text("server"));ServerDialogues.put("ch1-2",text("second"));assertEquals(List.of("ch1-2","ch1-3"),DialogueLoader.listIds(temp));assertEquals("server",DialogueLoader.load("ch1-3").title());assertEquals("local",DialogueLoader.load(temp,"ch1-3").title());}
 @Test void invalidIdsAndOversizeInputAreRejected(){for(String id:List.of("../escape","UPPER","","x".repeat(65)))ServerDialogues.put(id,text("bad"));ServerDialogues.put("valid","x".repeat(ServerDialogues.MAX_CHARS+1));ServerDialogues.put(null,"x");ServerDialogues.put("null",null);assertTrue(ServerDialogues.ids().isEmpty());}
 @Test void reconnectClearRemovesPreviousServerContent(){ServerDialogues.put("ch1-3",text("old"));ServerDialogues.clear();assertNull(ServerDialogues.get("ch1-3"));assertTrue(ServerDialogues.ids().isEmpty());}
 @Test void newerServerContentReplacesStaleText()throws Exception{ServerDialogues.put("ch1-3",text("old"));ServerDialogues.put("ch1-3",text("new"));assertEquals("new",DialogueLoader.load("ch1-3").title());}
 @Test void portraitNamesRequireSafePackIdentifiers(){DialogueRunner.packPortrait=n->n.equals("elena-neutral");assertEquals("elena-neutral",DialogueRunner.packName("elena-neutral.png"));assertEquals("elena-neutral",DialogueRunner.packName("ELENA-NEUTRAL.PNG"));assertNull(DialogueRunner.packName("../elena-neutral.png"));assertNull(DialogueRunner.packName("unknown.png"));assertNull(DialogueRunner.packName(null));}
}
