package kr.chacademy.story;
import kr.chacademy.story.dialogue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalDialogueTest {
 @TempDir Path dir;
 @Test void firstRunCommandsAndGainsAreOnceAndAllAlternateReplayGainsAreBlocked()throws Exception{
  Path file=dir.resolve("progress.properties");var s=new LocalDialogueStore(file);
  assertTrue(s.claimEvent("ch1-2","hello"));assertFalse(s.claimEvent("ch1-2","hello"));
  assertTrue(s.claimAffinity("ch1-2","scene.a",List.of(new Dialogue.AffinityChange("teacher",-1))));
  assertFalse(s.claimAffinity("ch1-2","scene.a",List.of(new Dialogue.AffinityChange("teacher",-1))));
  assertFalse(Files.readString(file).contains("affinity."),"호감도 값은 이 PC 에 저장하지 않는다 (MagicCodex 하나)");
  assertTrue(s.complete("ch1-2"));assertFalse(s.complete("ch1-2"));
  var reloaded=new LocalDialogueStore(file);
  assertTrue(reloaded.done("ch1-2"));assertFalse(reloaded.claimEvent("ch1-2","different_route"));
  assertFalse(reloaded.claimAffinity("ch1-2","scene.b",List.of(new Dialogue.AffinityChange("teacher",3))));
  assertFalse(Files.readString(file).contains("affinity."));
  assertTrue(reloaded.claimEvent("ch1-3","hello"));
 }
 @Test void failedWriteDoesNotIssueAClaimOrMarkComplete()throws Exception{
  Path parent=dir.resolve("file");Files.writeString(parent,"not a directory");var s=new LocalDialogueStore(parent.resolve("state.properties"));
  assertThrows(java.io.IOException.class,()->s.claimEvent("ch1","event"));assertThrows(java.io.IOException.class,()->s.complete("ch1"));assertFalse(s.done("ch1"));
 }
 @Test void commandFormatFailsClosedAndNeverMapsAConsoleIdentity()throws Exception{
  var c=DialogueCommands.parse(Map.of("end",List.of("/최초닉네임설정"),"events",Map.of("e",List.of("help"))));
  assertEquals(List.of("최초닉네임설정"),c.end());assertEquals("give Account stone",DialogueCommands.expand("give {player} stone","Account","닉네임"));
  assertThrows(java.io.IOException.class,()->DialogueCommands.parse(Map.of("end","op player")));
  assertThrows(java.io.IOException.class,()->DialogueCommands.parse(Map.of("end",List.of("help\nop player"))));
  assertThrows(java.io.IOException.class,()->DialogueCommands.parse(Map.of("events",Map.of("../x",List.of("help")))));
 }
 @Test void openingFailureDiscardsConstructorEventsAndRepeatedAcceptDoesNotRepeatThem(){
  List<String> seen=new ArrayList<>();var failed=new DialogueOpenSignals();failed.send(()->seen.add("bad"));failed.reject();failed.accept();assertTrue(seen.isEmpty());
  var accepted=new DialogueOpenSignals();accepted.send(()->seen.add("initial"));assertTrue(seen.isEmpty());accepted.accept();accepted.accept();accepted.send(()->seen.add("later"));assertEquals(List.of("initial","later"),seen);
 }
}
