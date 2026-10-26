package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.DiscoveryProtocol.Notice;
import static org.junit.jupiter.api.Assertions.*;
class DiscoveryQueueTest {
    private Notice notice(long id){return new Notice(id,"spell","마법","magic:spell.png",false);}
    @Test void replayCannotStackOrLosePendingNotifications(){var q=new DiscoveryQueue();assertEquals(DiscoveryQueue.Result.ADDED,q.offer(notice(1)));assertEquals(DiscoveryQueue.Result.DUPLICATE,q.offer(notice(1)));assertEquals(1,q.poll().token());assertEquals(DiscoveryQueue.Result.DUPLICATE,q.offer(notice(1)));q.finish(1);assertEquals(DiscoveryQueue.Result.FINISHED,q.offer(notice(1)));assertNull(q.poll());}
    @Test void fullQueueRefusesWithoutAcknowledgingAndResetAllowsServerReplay(){var q=new DiscoveryQueue();for(int i=1;i<=16;i++)assertEquals(DiscoveryQueue.Result.ADDED,q.offer(notice(i)));assertEquals(DiscoveryQueue.Result.FULL,q.offer(notice(17)));q.finish(q.poll().token());assertEquals(DiscoveryQueue.Result.ADDED,q.offer(notice(17)));q.clear();assertEquals(DiscoveryQueue.Result.ADDED,q.offer(notice(1)));}
}
