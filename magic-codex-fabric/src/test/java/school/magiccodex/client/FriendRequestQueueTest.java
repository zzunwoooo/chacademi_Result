package school.magiccodex.client;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.SocialProtocol.Entry;
import static org.junit.jupiter.api.Assertions.*;
class FriendRequestQueueTest {
    private static Entry entry(UUID id){return new Entry(id,"친구","루미나",true);}
    @Test void eachSenderIsAnnouncedOncePerSessionAndQueueIsBounded(){
        var q=new FriendRequestQueue();UUID a=UUID.randomUUID();
        assertFalse(q.offer(null));assertTrue(q.offer(entry(a)));assertFalse(q.offer(entry(a)),"Queued request is not stacked");
        assertEquals(a,q.poll().id());assertFalse(q.offer(entry(a)),"Server replays must not show the same card again");assertNull(q.poll());
        for(int i=0;i<FriendRequestQueue.TOASTS;i++)assertTrue(q.offer(entry(UUID.randomUUID())));
        UUID late=UUID.randomUUID();assertFalse(q.offer(entry(late)));assertEquals(FriendRequestQueue.TOASTS,q.size());
        q.poll();assertTrue(q.offer(entry(late)),"A refused card was not marked as shown");
        q.clear();assertEquals(0,q.size());assertTrue(q.offer(entry(a)),"Reconnect allows the server to announce again");
    }
    @Test void resolvedRequestsLeaveTheQueueAndCanBeAnnouncedAgainLater(){
        var q=new FriendRequestQueue();UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        q.offer(entry(a));q.offer(entry(b));q.offer(entry(c));
        q.retain(Set.of(b,c));assertEquals(2,q.size());assertEquals(b,q.poll().id());
        q.remove(c);assertNull(q.poll());
        assertTrue(q.offer(entry(a)),"A new request after the old one was resolved is announced");assertFalse(q.offer(entry(b)));
    }
    @Test void toastButtonsDoNotOverlapAndStayInsideTheCard(){
        assertTrue(FriendRequestToast.ACCEPT_X>0);assertTrue(FriendRequestToast.ACCEPT_X+FriendRequestToast.BUTTON_W<=FriendRequestToast.DECLINE_X);
        assertTrue(FriendRequestToast.DECLINE_X+FriendRequestToast.BUTTON_W<=FriendRequestToast.W);assertTrue(FriendRequestToast.BUTTON_Y+FriendRequestToast.BUTTON_H<=FriendRequestToast.H);
        assertEquals(1,FriendRequestQueue.hit(FriendRequestToast.ACCEPT_X+5,FriendRequestToast.BUTTON_Y+5));assertEquals(2,FriendRequestQueue.hit(FriendRequestToast.DECLINE_X+5,FriendRequestToast.BUTTON_Y+5));
        assertEquals(0,FriendRequestQueue.hit(FriendRequestToast.ACCEPT_X-5,FriendRequestToast.BUTTON_Y+5));assertEquals(0,FriendRequestQueue.hit(FriendRequestToast.ACCEPT_X+5,FriendRequestToast.BUTTON_Y-1));
    }
}
