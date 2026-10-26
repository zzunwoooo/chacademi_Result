package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class NotificationQueueTest {
 NotificationQueue.Notice n(String id){return new NotificationQueue.Notice(id,"quest","Title","Body",1000);}
 @Test void boundedNewestBottomAndOverflowSafe(){var q=new NotificationQueue();for(int i=0;i<30;i++)q.offer(n("n"+i));assertEquals(16,q.size());var rows=q.frame(0,true,3);assertEquals(3,rows.size());assertEquals("n29",rows.getFirst().notice().id());assertTrue(q.frame(20,true,0).isEmpty());q.offer(n("n29"));assertEquals(16,q.size());assertEquals(3,q.frame(40,true,100).size());}
 @Test void menusPauseTimeAndNewNoticesNeverOverlap(){var q=new NotificationQueue();q.offer(n("old"));q.frame(0,true,3);q.frame(100,true,3);q.frame(10000,false,3);assertEquals(100,q.frame(10100,true,3).getFirst().age());q.offer(n("new"));var rows=q.frame(10200,true,3);assertEquals("new",rows.getFirst().notice().id());assertEquals(0,rows.getFirst().slot());assertEquals(1,rows.get(1).slot());q.clear();assertEquals(0,q.size());}
 @Test void expiredNoticeDoesNotExecuteAnyAction(){var q=new NotificationQueue();q.offer(n("x"));for(int i=0;i<12;i++)q.frame(i*100,true,3);assertEquals(0,q.size());}
 @Test void repeatedWarningDoesNotRestartAnimationOrCreateDuplicate(){var q=new NotificationQueue();assertTrue(q.offerIfAbsent(n("warning")));q.frame(0,true,3);q.frame(100,true,3);assertFalse(q.offerIfAbsent(n("warning")));assertEquals(200,q.frame(200,true,3).getFirst().age());assertEquals(1,q.size());}
 @Test void repeatedDeliveryPreservesAgeAndDismissOnlyRemovesChosenNotice(){var q=new NotificationQueue();q.offer(n("a"));q.frame(0,true,3);q.frame(100,true,3);q.offer(n("a"));assertEquals(200,q.frame(200,true,3).getFirst().age());q.offer(n("b"));assertTrue(q.dismiss("a"));assertEquals(1,q.size());assertEquals("b",q.frame(300,true,3).getFirst().notice().id());assertFalse(q.dismiss("a"));}
}
