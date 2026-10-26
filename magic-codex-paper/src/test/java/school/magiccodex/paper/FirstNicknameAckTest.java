package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class FirstNicknameAckTest {
 @Test void failureStaleConnectionAndDuplicateAckNeverPermitFirstDone(){var fail=new FirstNicknameAck();assertFalse(fail.accept(false,true));assertFalse(fail.accept(true,true));assertFalse(new FirstNicknameAck().accept(true,false));var ok=new FirstNicknameAck();assertTrue(ok.accept(true,true));assertFalse(ok.accept(true,true));}
 @Test void defaultTargetAndCompatibleClientCommandRemainCh12(){var id=UUID.randomUUID();assertEquals("ch1-2",FirstNicknameCommands.plan(null,"Player",id,"별빛").dialogue());assertEquals("ch1-2",FirstNicknameCommands.plan(List.of("/story dialogue ch1-2"),"Player",id,"별빛").dialogue());assertEquals("ch1-2",FirstNicknameCommands.plan(List.of("storydialogue {player} ch1-2 replay"),"Player",id,"별빛").dialogue());}
 @Test void malformedStoryConfigurationDoesNotFallThroughToConsole(){var id=UUID.randomUUID();for(String c:List.of("story dialogue ../bad","storydialogue other ch1-2","storydialogue Player ch1-2 local","story dialogue ch1-2 extra"))assertThrows(IllegalArgumentException.class,()->FirstNicknameCommands.plan(List.of(c),"Player",id,"별빛"));}
 @Test void koreanStoryCommandIsRecognized(){var id=UUID.randomUUID();assertEquals("ch1-2",FirstNicknameCommands.plan(List.of("대화관리 재생 {player} 대화 ch1-2"),"Player",id,"별빛").dialogue());assertThrows(IllegalArgumentException.class,()->FirstNicknameCommands.plan(List.of("대화관리 재생 Other 대화 ch1-2"),"Player",id,"별빛"));}
}
