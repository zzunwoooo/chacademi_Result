package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class FishCatchJournalTest {
 @TempDir Path root;
 @Test void restartPreservesCatchIdentityDateAndAcknowledgement()throws Exception{
  var owner=UUID.randomUUID();var fish=new FishTank.Fish(UUID.randomUUID().toString(),"fish_001",25.5,1760000000000L);var first=new FishCatchJournal(root);first.save(owner,fish);
  var reopened=new FishCatchJournal(root);var pending=reopened.pending();assertEquals(1,pending.size());assertEquals(owner,pending.getFirst().owner());assertEquals(fish,pending.getFirst().fish());reopened.acknowledge(pending.getFirst());assertTrue(reopened.pending().isEmpty());
 }
 @Test void retrySameCatchIsIdempotentAndAckCannotEscapeJournal()throws Exception{
  var owner=UUID.randomUUID();var fish=new FishTank.Fish(UUID.randomUUID().toString(),"fish_001",25.5,1760000000000L);var journal=new FishCatchJournal(root);journal.save(owner,fish);journal.save(owner,fish);assertEquals(1,journal.pending().size());
  assertThrows(java.io.IOException.class,()->journal.acknowledge(new FishCatchJournal.Pending(owner,fish,root.resolve("../outside.properties"))));assertEquals(1,journal.pending().size());
 }
}