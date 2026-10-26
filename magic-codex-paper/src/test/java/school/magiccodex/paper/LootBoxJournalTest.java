package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 개봉 영수증: 재시작해도 같은 결과·같은 우편분만 남고, 지우면 다시 처리하지 않는다 (재추첨·중복 지급 없음). */
class LootBoxJournalTest {
    @TempDir Path temp;

    @Test void survivesRestartWithExactResultsAndMail() throws Exception {
        var journal = new LootBoxJournal(temp);
        String op = UUID.randomUUID().toString(); UUID player = UUID.randomUUID();
        var results = List.of(new byte[]{1, 2}, new byte[]{3});
        journal.save(op, player, "box", "API 상자", 10, 3, results, new int[]{0, 3}, List.of(new byte[]{9}));
        var again = new LootBoxJournal(temp).pending(new ArrayList<>());
        assertEquals(1, again.size());
        var o = again.getFirst();
        assertEquals(op, o.operation()); assertEquals(player, o.player()); assertEquals(10, o.count()); assertEquals(3, o.grade());
        assertArrayEquals(new byte[]{3}, o.results().get(1)); assertArrayEquals(new int[]{0, 3}, o.grades());
        assertArrayEquals(new byte[]{9}, o.mail().getFirst());
        assertEquals("lootbox_" + op.replace("-", ""), o.receipt());
        journal.remove(op);
        assertTrue(new LootBoxJournal(temp).pending(new ArrayList<>()).isEmpty());
    }

    @Test void furnitureGrantsSurviveRestartAndOldRecordsHaveNone() throws Exception {
        var journal = new LootBoxJournal(temp);
        String op = UUID.randomUUID().toString(), old = UUID.randomUUID().toString();
        journal.save(op, UUID.randomUUID(), "furniture_ticket", "가구뽑기권", 10, 0, List.of(new byte[]{1}, new byte[]{2}), new int[]{0, 0}, List.of(), List.of("sofa", "piano"));
        journal.save(old, UUID.randomUUID(), "box", "API 상자", 1, 0, List.of(new byte[]{1}), new int[]{0}, List.of());
        var all = new LootBoxJournal(temp).pending(new ArrayList<>());
        var byOp = new HashMap<String, LootBoxJournal.Opening>();
        for (var o : all) byOp.put(o.operation(), o);
        assertEquals(List.of("sofa", "piano"), byOp.get(op).furniture());
        assertTrue(byOp.get(old).furniture().isEmpty());
        assertThrows(java.io.IOException.class, () -> journal.save(UUID.randomUUID().toString(), UUID.randomUUID(), "b", "t", 1, 0,
                List.of(new byte[]{1}), new int[]{0}, List.of(), List.of("../x")));
    }

    @Test void brokenFilesAreReportedNotProcessed() throws Exception {
        var journal = new LootBoxJournal(temp);
        Files.writeString(temp.resolve(UUID.randomUUID() + ".properties"), "operation=nope");
        var broken = new ArrayList<Path>();
        assertTrue(journal.pending(broken).isEmpty());
        assertEquals(1, broken.size());
    }

    @Test void rejectsUnsafeOperationIds() {
        assertThrows(IllegalArgumentException.class, () -> new LootBoxJournal(temp).remove("../x"));
    }
}
