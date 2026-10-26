package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FirstNicknameStateTest {
    @Test void durableStartNormalSaveAndDuplicateCompletion() throws Exception {
        UUID id=UUID.randomUUID();var disk=new HashSet<UUID>();var commands=new AtomicInteger();
        var state=new FirstNicknameState(disk,next->{disk.clear();disk.addAll(next);});
        assertTrue(state.start(id));assertFalse(state.start(id));assertTrue(disk.contains(id));
        assertTrue(state.complete(id,()->true,commands::incrementAndGet));
        assertFalse(state.complete(id,()->true,commands::incrementAndGet));
        assertEquals(1,commands.get());assertFalse(state.pending(id));assertTrue(disk.isEmpty());
    }
    @Test void failedStartDoesNotOpenOrBecomePending() {
        UUID id=UUID.randomUUID();var state=new FirstNicknameState(Set.of(),next->{throw new IOException();});
        assertThrows(IOException.class,()->state.start(id));assertFalse(state.pending(id));
    }
    @Test void failedCompletionRetainsPendingAndDoesNotRunStory() {
        UUID id=UUID.randomUUID();var commands=new AtomicInteger();
        var state=new FirstNicknameState(Set.of(id),next->{throw new IOException();});
        assertThrows(IOException.class,()->state.complete(id,()->true,commands::incrementAndGet));
        assertTrue(state.pending(id));assertEquals(0,commands.get());
    }
    @Test void disconnectAndOldSessionKeepPendingForReconnect() throws Exception {
        UUID id=UUID.randomUUID();var disk=new HashSet<>(Set.of(id));var commands=new AtomicInteger();
        var state=new FirstNicknameState(disk,next->{disk.clear();disk.addAll(next);});
        assertFalse(state.complete(id,()->false,commands::incrementAndGet));assertTrue(disk.contains(id));
        var reloaded=new FirstNicknameState(disk,next->{disk.clear();disk.addAll(next);});
        assertTrue(reloaded.pending(id));
        assertTrue(reloaded.complete(id,()->true,commands::incrementAndGet));assertEquals(1,commands.get());
    }
    @Test void ordinaryNicknameSaveNeverTriggersFirstStory() throws Exception {
        var commands=new AtomicInteger();var state=new FirstNicknameState(Set.of(),next->fail("must not write"));
        assertFalse(state.complete(UUID.randomUUID(),()->true,commands::incrementAndGet));assertEquals(0,commands.get());
    }
}
