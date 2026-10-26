package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class CatalogGameplayFlowTest {
 @Test void deniedPreparationNeverShowsVisualOrExecutes(){assertFalse(CatalogGameplayFlow.cast(false,true,()->{throw new AssertionError();},()->{throw new AssertionError();},e->{throw new AssertionError();}));}
 @Test void visualBeforeGameplayAndFailedGameplayKeepsCost(){var calls=new ArrayList<String>();assertTrue(CatalogGameplayFlow.cast(true,true,()->{calls.add("vfx");return true;},()->{calls.add("execute");return false;},e->calls.add("log")));assertEquals(List.of("vfx","execute","log"),calls);}
 @Test void exceptionAfterVisualKeepsCostAndLogs(){var failures=new ArrayList<Throwable>();assertTrue(CatalogGameplayFlow.cast(true,true,()->true,()->{throw new IllegalStateException();},failures::add));assertEquals(1,failures.size());}
 @Test void visualOnlyUnknownAndVisualFailureRetainOriginalSemantics(){assertTrue(CatalogGameplayFlow.cast(true,false,()->true,()->{throw new AssertionError();},e->{}));assertNull(CatalogGameplayFlow.cast(true,false,()->null,()->false,e->{}));assertFalse(CatalogGameplayFlow.cast(true,true,()->false,()->{throw new AssertionError();},e->{}));assertFalse(CatalogGameplayFlow.cast(true,true,()->null,()->false,e->{}));}
}
