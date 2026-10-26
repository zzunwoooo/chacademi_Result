package dev.portablevfx.client.network;
import dev.portablevfx.protocol.CatalogReadinessV2;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReadinessUploadTest {
    private static Set<String> ids(int n){return IntStream.range(0,n).mapToObj(i->"claude:readiness_fixture/effect_"+String.format("%04d",i)).collect(Collectors.toSet());}
    @Test void cannotSendWithoutServerEpochAndDoesNotAdvanceOnFailedSend()throws Exception{
        var u=new ReadinessUpload();assertNull(u.peek(ids(1245)));UUID epoch=UUID.randomUUID();u.epoch(epoch);
        byte[] first=u.peek(ids(1245));assertSame(first,u.peek(ids(1245)));assertTrue(first.length<32767);
        var r=new CatalogReadinessV2.Receiver(epoch);assertNull(r.accept(first,0).complete());u.sent();
        byte[] second=u.peek(ids(1245));assertNotNull(second);assertEquals(ids(1245),r.accept(second,1).complete());u.sent();
        assertEquals(1245,u.sentCount());assertNull(u.peek(ids(1245)));
    }
    @Test void reloadWithdrawsAndReplacingPartialBatchEvenWithPriorCompletedIdsIsNewGeneration()throws Exception{
        var u=new ReadinessUpload();UUID epoch=UUID.randomUUID();u.epoch(epoch);var r=new CatalogReadinessV2.Receiver(epoch);Set<String> small=ids(1);
        assertEquals(small,r.accept(u.peek(small),0).complete());u.sent();
        assertNull(r.accept(u.peek(ids(1245)),1).complete());u.sent();
        assertEquals(small,r.accept(u.peek(small),2).complete());u.sent();
        assertEquals(Set.of(),r.accept(u.peek(Set.of()),3).complete());u.sent();assertEquals(0,u.sentCount());
    }
    @Test void nonceChangeAndDisconnectDropPendingFramesWhileReannounceKeepsGenerationMonotonic()throws Exception{
        var u=new ReadinessUpload();UUID first=UUID.randomUUID(),second=UUID.randomUUID();u.epoch(first);byte[] old=u.peek(ids(1245));u.sent();u.invalidate();
        var r=new CatalogReadinessV2.Receiver(first);assertNull(r.accept(old,0).complete());assertTrue(r.accept(u.peek(ids(1245)),1).started());
        u.epoch(second);var other=new CatalogReadinessV2.Receiver(second);assertThrows(Exception.class,()->other.accept(old,2));
        assertNull(other.accept(u.peek(ids(1245)),3).complete());u.sent();assertEquals(ids(1245),other.accept(u.peek(ids(1245)),4).complete());u.sent();
        u.disconnect();assertFalse(u.hasEpoch());assertNull(u.peek(ids(1)));assertEquals(-1,u.sentCount());
    }
    @Test void sameEpochAndUnchangedInventoryNeedsNoNewGenerationAfterPeriodicReannounce(){
        var u=new ReadinessUpload();UUID epoch=UUID.randomUUID();u.epoch(epoch);var ready=ids(1245);
        while(u.peek(ready)!=null)u.sent();u.epoch(epoch);assertNull(u.peek(ready));assertEquals(1245,u.sentCount());
        u.epoch(UUID.randomUUID());assertNotNull(u.peek(ready));
    }
    @Test void rejectedOversizeSnapshotCannotCorruptThePreviousUpload(){
        var u=new ReadinessUpload();u.epoch(UUID.randomUUID());byte[] first=u.peek(ids(1245));
        assertThrows(IllegalArgumentException.class,()->u.peek(ids(4097)));assertSame(first,u.peek(ids(1245)));
    }
}
