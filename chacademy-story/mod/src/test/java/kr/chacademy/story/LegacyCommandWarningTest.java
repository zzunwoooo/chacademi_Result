package kr.chacademy.story;
import java.util.*;
import kr.chacademy.story.dialogue.DialogueCommands;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LegacyCommandWarningTest {
 @Test void oldSceneKeysAreNotSilentlyIgnored(){var raw=Map.of("s20",List.of("say hello"));assertTrue(DialogueCommands.warnings(raw).getFirst().contains("commands.s20"));}
 @Test void legacyEndingCommandsWarnWhileFirstRewardMarkersRemainParsed()throws Exception{var raw=Map.of("end",List.of("chaca_reward ch1-3.end","say hello"));assertFalse(DialogueCommands.warnings(raw).isEmpty());assertEquals(List.of("chaca_reward ch1-3.end","say hello"),DialogueCommands.parse(raw).end());}
}
