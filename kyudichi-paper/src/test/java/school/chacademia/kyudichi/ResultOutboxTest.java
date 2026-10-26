package school.chacademia.kyudichi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultOutboxTest {
    private static ResultOutbox.Entry entry() {
        var a = new RatingStore.Delta(UUID.randomUUID(), "준우", 1012, true, false, false, false, 2, 1, 0, 3, 41.5, 6.25, 1, 2, true, 0, 27);
        var b = new RatingStore.Delta(UUID.randomUUID(), "상대", 998, false, false, true, true, 0, 0, 4, 0, 0, 0, 0, 0, false, 1, -36);
        return new ResultOutbox.Entry(RatingStore.MODE, UUID.randomUUID(), "arena1", 120, 30, 0, "SNITCH", new RatingStore.Teams("준우 파티", "상대 파티", 2, -1), List.of(a, b));
    }

    @Test void roundTripKeepsEveryField() throws Exception {
        var e = entry();
        var back = ResultOutbox.decode(ResultOutbox.encode(e));
        assertEquals(e.match(), back.match()); assertEquals(e.mode(), back.mode()); assertEquals(e.arena(), back.arena());
        assertEquals(e.scoreA(), back.scoreA()); assertEquals(e.scoreB(), back.scoreB()); assertEquals(e.winner(), back.winner());
        assertEquals(e.reason(), back.reason()); assertEquals(e.teams(), back.teams());
        assertEquals(e.deltas().size(), back.deltas().size());
        assertTrue(back.deltas().containsAll(e.deltas()));
    }

    @Test void saveListDelete(@TempDir Path dir) {
        var box = new ResultOutbox(dir.resolve("outbox"), Logger.getLogger("test"));
        var e = entry();
        assertTrue(box.save(e));
        assertTrue(Files.exists(box.file(e.match())));
        assertEquals(1, box.all().size());
        assertEquals(e.match(), box.all().getFirst().match());
        box.delete(e.match());
        assertTrue(box.all().isEmpty());
    }
}
