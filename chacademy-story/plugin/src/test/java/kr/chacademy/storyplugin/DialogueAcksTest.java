package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;import java.util.UUID;import static org.junit.jupiter.api.Assertions.*;
class DialogueAcksTest {
 @Test void duplicateClicksShareOneAckAndWrongDialogueCannotCompleteIt(){var a=new DialogueAcks();var p=UUID.randomUUID();var f=a.begin(p,"ch1-2");assertSame(f,a.begin(p,"ch1-2"));a.accept(p,"ch1-1");assertFalse(f.isDone());a.accept(p,"ch1-2");a.accept(p,"ch1-2");assertTrue(f.join());}
 @Test void failureAndLogoutPermitFreshRetry(){var a=new DialogueAcks();var p=UUID.randomUUID();var f=a.begin(p,"ch1-2");a.reject(p,"ch1-2");assertFalse(f.join());var retry=a.begin(p,"ch1-2");assertNotSame(f,retry);a.disconnect(p);assertFalse(retry.join());var success=a.begin(p,"ch1-2");a.accept(p,"ch1-2");assertTrue(success.join());}
}
