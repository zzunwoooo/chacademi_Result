package school.chacademia.gimmick;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BarrierLedgerTest {
 static class Blocks implements BarrierLedger.Blocks {boolean loaded=true,air=true,barrier;Map<String,Set<String>> saved=Map.of();public boolean available(String k){return loaded;}public boolean air(String k){return air;}public void place(String k){assertTrue(saved.get("g1").contains(k));barrier=true;air=false;}public void clearOwnedBarrier(String k){barrier=false;air=true;}}
 @Test void writeBeforePlacementAndWriteFailureRefusesMutation(){var b=new Blocks();var l=new BarrierLedger(saved->{throw new IOException("disk full");},b);assertFalse(l.reconcile("g1",Set.of("world:0:1:0")));assertFalse(b.barrier);assertTrue(l.snapshot().isEmpty());var good=new BarrierLedger(saved->b.saved=saved,b);assertTrue(good.reconcile("g1",Set.of("world:0:1:0")));assertTrue(b.barrier);}
 @Test void unloadedWorldRetainsRecoveryUntilChunkReturns(){var b=new Blocks();b.loaded=false;b.barrier=true;b.air=false;var l=new BarrierLedger(saved->b.saved=saved,b);l.load(Map.of("g1",Set.of("world:0:1:0")));l.reconcile("g1",Set.of());assertFalse(l.snapshot().isEmpty());b.loaded=true;l.reconcile("g1",Set.of());assertFalse(b.barrier);assertTrue(l.snapshot().isEmpty());}
 @Test void crashAfterJournalBeforeBlockPlacementCanReconcile(){var b=new Blocks();var l=new BarrierLedger(saved->b.saved=saved,b);l.load(Map.of("g1",Set.of("world:0:1:0")));assertTrue(l.reconcile("g1",Set.of("world:0:1:0")));assertTrue(b.barrier);}
 @Test void nonAirOrProtectedPositionIsNotClaimed(){var b=new Blocks();b.air=false;var l=new BarrierLedger(saved->b.saved=saved,b);l.reconcile("g1",Set.of("world:0:1:0"));assertFalse(b.barrier);assertTrue(l.snapshot().isEmpty());}
}
