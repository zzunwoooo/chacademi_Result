package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.Path;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class JournalRecordTest {
 @TempDir Path dir;
 @Test void adminCompleteResetProgressNeverReopensConsumedPayout()throws Exception{try(var store=new QuestStore(QuestStoreTest.SQLITE,dir.resolve("q.db"))){UUID id=UUID.randomUUID();long now=System.currentTimeMillis();var q=QuestStoreTest.quest("quest",false);
  store.adminRecord(id,q,"grant",now);assertEquals("active",store.entries(id).getFirst().status());store.adminRecord(id,q,"complete",now);assertEquals(1,store.entries(id).getFirst().completions());assertNull(store.reserve(id,q.id(),q.cycle(now),now));
  store.adminRecord(id,q,"reset",now);assertEquals(1,store.entries(id).getFirst().completions());store.adminRecord(id,q,"progress",now);store.advance(id,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",10,now);assertNull(store.reserve(id,q.id(),q.cycle(now),now));
 }}
 @Test void pendingPayoutCannotBeEditedAndDailyHistoryRemainsVisible()throws Exception{try(var store=new QuestStore(QuestStoreTest.SQLITE,dir.resolve("q.db"))){UUID id=UUID.randomUUID();long now=System.currentTimeMillis();var q=QuestStoreTest.quest("quest",true);
  assertTrue(store.accept(id,q,now));store.advance(id,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",10,now);var reserved=store.reserve(id,q.id(),q.cycle(now),now);assertNotNull(reserved);
  assertThrows(IllegalStateException.class,()->store.adminRecord(id,q,"reset",now));assertEquals(reserved.token(),store.paying(id,q.id(),q.cycle(now)).token());store.finish(id,reserved,true);
  assertEquals("claimed",store.journalEntries(id).getFirst().status());assertTrue(store.accept(id,q,now+86400000));assertEquals(1,store.journalEntries(id).size());
 }}
 @Test void legacyJournalEditingDoesNotAlterStageFlagsOrEffectReceipts()throws Exception{try(var store=new DialogueStore(QuestStoreTest.SQLITE,dir.resolve("d.db"))){UUID id=UUID.randomUUID();var before=store.state(id);store.transition(id,before,"chapter:start:choice",List.of("story chapter stage2","flag learned true","command example"));var receipts=store.effects(id);
  store.journalEdit(id,"chapter","complete");store.journalEdit(id,"chapter","reset");assertEquals("stage2",store.state(id).values().get("story.chapter"));assertEquals("true",store.state(id).values().get("flag.learned"));assertEquals(receipts,store.effects(id));assertEquals("hidden",store.state(id).values().get("journal.chapter"));
 }}
}
