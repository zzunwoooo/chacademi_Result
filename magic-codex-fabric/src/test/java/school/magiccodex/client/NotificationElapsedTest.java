package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class NotificationElapsedTest {
 @Test void slowVisibleFramesExpireInRealElapsedTime(){var q=new NotificationQueue();q.offer(new NotificationQueue.Notice("a","q","A","B",3500));assertTrue(q.frame(0,true,3).getFirst().announce());assertEquals(1200,q.frame(1200,true,3).getFirst().age());assertFalse(q.frame(1300,true,3).getFirst().announce());assertTrue(q.frame(3600,true,3).isEmpty());}
 @Test void screenTransitionPausesAndDoesNotReplaySound(){var q=new NotificationQueue();q.offer(new NotificationQueue.Notice("a","q","A","B",3500));q.frame(0,true,3);q.frame(500,true,3);q.frame(600,false,3);var next=q.frame(90000,true,3).getFirst();assertEquals(500,next.age());assertFalse(next.announce());assertEquals(600,q.frame(90100,true,3).getFirst().age());}
}
