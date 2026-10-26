package school.magiccodex.paper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class SocialTest {
    @TempDir Path dir;
    private static SocialProtocol.Entry friend(UUID id,String name){return new SocialProtocol.Entry(id,name,"루미나",true);}
    @Test void persistenceLimitDuplicateAndSelf()throws Exception{
        UUID owner=UUID.randomUUID();Path db=dir.resolve("friends.db");var peers=new ArrayList<UUID>();
        try(var store=new FriendStore(db)){
            assertFalse(store.add(owner,friend(owner,"self")));
            for(int i=0;i<50;i++){UUID id=UUID.randomUUID();peers.add(id);assertTrue(store.add(owner,friend(id,"friend"+i)));}
            assertFalse(store.add(owner,friend(peers.getFirst(),"renamed")));assertFalse(store.add(owner,friend(UUID.randomUUID(),"extra")));
            assertEquals(50,store.load(owner).size());assertTrue(store.load(peers.getFirst()).isEmpty(),"Adding is one-way");
            assertTrue(store.pendingSignal(owner));
        }
        try(var store=new FriendStore(db)){
            assertEquals(50,store.load(owner).size());assertFalse(store.remove(UUID.randomUUID(),peers.getFirst()));
            store.profile(peers.getFirst(),"newName","아르케온");var e=store.load(owner).getFirst();assertEquals("newName",e.name());assertEquals("아르케온",e.dormitory());
            assertTrue(store.remove(owner,peers.getFirst()));assertFalse(store.remove(owner,peers.getFirst()));assertTrue(store.add(owner,friend(UUID.randomUUID(),"replacement")));assertEquals(50,store.load(owner).size());
            store.signalDelivered(owner);assertFalse(store.pendingSignal(owner));store.remove(owner,peers.get(1));store.add(owner,friend(peers.get(1),"friend1"));assertFalse(store.pendingSignal(owner),"Re-adding must not manufacture another first-friend reward");
        }
    }
    @Test void queuedDoubleAddsAreIdempotent()throws Exception{
        var executor=Executors.newSingleThreadExecutor();UUID owner=UUID.randomUUID(),target=UUID.randomUUID();
        try(var store=new FriendStore(dir.resolve("queue.db"))){var a=executor.submit(()->store.add(owner,friend(target,"target")));var b=executor.submit(()->store.add(owner,friend(target,"target")));assertTrue(a.get());assertFalse(b.get());assertEquals(1,store.load(owner).size());}finally{executor.shutdown();}
    }
    @Test void ticketsBoundToOwnerTargetAndSingleUse(){
        var tickets=new WhisperTickets();UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),target=UUID.randomUUID();var t=tickets.issue(owner,target,100);
        assertFalse(tickets.consume(other,target,t.token(),200));assertFalse(tickets.consume(owner,other,t.token(),200));assertFalse(tickets.consume(owner,target,t.token()+1,200));
        assertTrue(tickets.consume(owner,target,t.token(),200));assertFalse(tickets.consume(owner,target,t.token(),200));
    }
    @Test void unboundCastCanSelectOneFriendAndExpires(){
        var tickets=new WhisperTickets();UUID owner=UUID.randomUUID(),target=UUID.randomUUID();var t=tickets.issue(owner,SocialProtocol.NONE,0);
        assertNotNull(tickets.bind(owner,target,10));assertNull(tickets.bind(owner,UUID.randomUUID(),20));
        assertFalse(tickets.consume(owner,target,t.token(),120000));assertNull(tickets.get(owner,120000));
        t=tickets.issue(owner,target,0);tickets.cancel(owner,t.token());assertNull(tickets.get(owner,1));
        tickets.issue(owner,target,0);tickets.remove(owner);assertNull(tickets.get(owner,1));
    }
    @Test void successfulSocialCastChargesEachMessageWithoutCooldownAndRejectsLockedEmpty(){
        var spell=new ManaSpells.Spell("wind_message","마법 바람의전언","magic.learned.wind_message",1,1000);var a=new ManaAccount(3,100,0);int[] opens={0};
        assertEquals(ManaProtocol.LOCKED,ManaCasting.attempt(a,spell,false,0,()->{opens[0]++;return true;}).status());
        assertEquals(ManaProtocol.OK,ManaCasting.attempt(a,spell,true,0,()->{opens[0]++;return true;}).status());assertEquals(2,a.current);
        assertEquals(ManaProtocol.OK,ManaCasting.attempt(a,spell,true,10,()->{opens[0]++;return true;}).status());assertEquals(2,opens[0]);assertEquals(1,a.current);
        assertEquals(ManaProtocol.FAILED,ManaCasting.attempt(a,spell,true,1001,()->false).status());assertEquals(1,a.current);
        a.current=0;assertEquals(ManaProtocol.EMPTY,ManaCasting.attempt(a,spell,true,1002,()->true).status());
    }
    @Test void koreanMessagesAndFullListRoundTripAndStaySmall(){
        UUID id=UUID.randomUUID();String message="도서관 앞에서 만날까? /op는 그냥 글자예요";
        var request=new SocialProtocol.Request(SocialProtocol.SEND,42,id,89,message);assertEquals(request,SocialProtocol.request(SocialProtocol.encode(request)));
        var entries=new ArrayList<SocialProtocol.Entry>();for(int i=0;i<50;i++)entries.add(friend(UUID.randomUUID(),"player"+i));
        var r=new SocialProtocol.Response(SocialProtocol.SNAPSHOT,42,id,89,"하루","루미나",message,1000,entries);
        byte[] b=SocialProtocol.encode(r);assertTrue(b.length<SocialProtocol.MAX_BYTES);assertEquals(r,SocialProtocol.response(b));
    }
    @Test void badPayloadsAndControlTextCannotReachHandlers(){
        var r=new SocialProtocol.Request(SocialProtocol.SEND,1,UUID.randomUUID(),1,"hello");byte[] b=SocialProtocol.encode(r);
        for(int i=0;i<b.length;i++){byte[] shortBytes=Arrays.copyOf(b,i);assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(shortBytes));}
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(Arrays.copyOf(b,b.length+1)));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(new byte[SocialProtocol.MAX_BYTES+1]));
        for(String s:List.of(""," ","line\nbreak","\u00a7cRed","a\u202Eb","a".repeat(241)))assertThrows(IllegalArgumentException.class,()->SocialProtocol.cleanMessage(s));
        assertEquals("한글 테스트",SocialProtocol.cleanMessage(" 한글 테스트 "));
    }
    // ---- 친구 신청(동의) 흐름 ----
    private static final long T=1_000_000_000_000L;
    private static boolean has(FriendStore store,UUID owner,UUID target)throws Exception{return store.load(owner).stream().anyMatch(e->e.id().equals(target));}
    @Test void requestIsPendingUntilAcceptedAndThenBothSidesAreFriends()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();Path db=dir.resolve("requests.db");
        try(var store=new FriendStore(db)){
            assertEquals(FriendStore.Result.SELF,store.request(a,"에이","루미나",friend(a,"에이"),T));
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","루미나",friend(b,"비"),T));
            assertEquals(FriendStore.Result.DUPLICATE,store.request(a,"에이","루미나",friend(b,"비"),T+1),"No duplicate request");
            assertTrue(store.load(a).isEmpty());assertTrue(store.load(b).isEmpty(),"A request is not a friendship");
            assertEquals(List.of(a),store.incoming(b,T+1).stream().map(SocialProtocol.Entry::id).toList());assertEquals("에이",store.incoming(b,T+1).getFirst().name());
            assertEquals(List.of(b),store.outgoing(a,T+1).stream().map(SocialProtocol.Entry::id).toList());assertEquals("비",store.outgoing(a,T+1).getFirst().name());
            assertTrue(store.incoming(a,T+1).isEmpty());assertFalse(store.pendingSignal(a));
            var polled=store.poll(List.of(a,b),T+1);assertEquals(1,polled.incoming().get(b).size());assertNull(polled.incoming().get(a));assertTrue(polled.friends().isEmpty());
        }
        try(var store=new FriendStore(db)){ // 재접속·재시작 뒤에도 남아 있다
            assertEquals(1,store.incoming(b,T+2).size());
            assertEquals(FriendStore.Result.MISSING,store.accept(a,"에이","루미나",b,T+2),"Only the target can accept");
            assertEquals(FriendStore.Result.ACCEPTED,store.accept(b,"비","아르케온",a,T+2));
            assertTrue(has(store,a,b));assertTrue(has(store,b,a));assertEquals("에이",store.load(b).getFirst().name());assertEquals("아르케온",store.load(a).getFirst().dormitory());
            assertTrue(store.incoming(b,T+3).isEmpty());assertTrue(store.outgoing(a,T+3).isEmpty());
            assertTrue(store.pendingSignal(a));assertTrue(store.pendingSignal(b));
            assertEquals(FriendStore.Result.MISSING,store.accept(b,"비","아르케온",a,T+3));
            assertEquals(FriendStore.Result.ALREADY_FRIENDS,store.request(a,"에이","루미나",friend(b,"비"),T+3));
            assertEquals(Map.of(a,1,b,1),store.poll(List.of(a,b),T+3).friends());
            assertTrue(store.unfriend(a,b));assertFalse(has(store,a,b));assertFalse(has(store,b,a));assertFalse(store.unfriend(a,b));
        }
    }
    @Test void crossedRequestsAndLegacyOneWayRowsBecomeMutual()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
        try(var store=new FriendStore(dir.resolve("mutual.db"))){
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(b,"비"),T));
            assertEquals(FriendStore.Result.ACCEPTED,store.request(b,"비","",friend(a,"에이"),T+1),"A crossed request is consent from both");
            assertTrue(has(store,a,b));assertTrue(has(store,b,a));assertTrue(store.incoming(b,T+2).isEmpty());assertTrue(store.outgoing(b,T+2).isEmpty());
            // 예전 방식의 한쪽 등록: c만 d를 등록해 둔 상태
            assertTrue(store.add(c,friend(d,"디")));
            assertEquals(FriendStore.Result.SENT,store.request(c,"씨","",friend(d,"디"),T),"One-way owner still needs the other side's consent");
            assertFalse(has(store,d,c));
            assertEquals(FriendStore.Result.ACCEPTED,store.accept(d,"디","",c,T+1));
            assertEquals(1,store.load(c).size(),"Existing direction is kept, not duplicated");assertTrue(has(store,d,c));
            UUID e=UUID.randomUUID(),f=UUID.randomUUID();
            assertTrue(store.add(e,friend(f,"에프")));
            assertEquals(FriendStore.Result.ACCEPTED,store.request(f,"에프","",friend(e,"이"),T),"The person who was added one-way reciprocates directly");
            assertTrue(has(store,f,e));assertEquals(1,store.load(e).size());assertTrue(store.incoming(e,T+1).isEmpty());
        }
    }
    @Test void declineStartsCooldownAndRequestsExpire()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var store=new FriendStore(dir.resolve("decline.db"))){
            assertFalse(store.decline(b,a,T));
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(b,"비"),T));
            assertTrue(store.decline(b,a,T+10));assertFalse(store.decline(b,a,T+11));
            assertTrue(store.incoming(b,T+11).isEmpty());assertTrue(store.load(a).isEmpty());assertTrue(store.load(b).isEmpty());
            assertEquals(FriendStore.Result.COOLDOWN,store.request(a,"에이","",friend(b,"비"),T+20));
            assertEquals(FriendStore.Result.SENT,store.request(b,"비","",friend(a,"에이"),T+20),"The cooldown is per direction");
            assertTrue(store.withdraw(b,a,T+30));assertFalse(store.withdraw(b,a,T+31));
            assertEquals(FriendStore.Result.COOLDOWN,store.request(b,"비","",friend(a,"에이"),T+40),"Withdrawing starts the same cooldown, so request/withdraw cannot be looped");
            assertTrue(store.incoming(a,T+40).isEmpty());
            assertEquals(FriendStore.Result.SENT,store.request(b,"비","",friend(a,"에이"),T+30+FriendStore.DECLINE_COOLDOWN+1),"The withdraw cooldown ends after DECLINE_COOLDOWN");
            assertTrue(store.withdraw(b,a,T+30+FriendStore.DECLINE_COOLDOWN+2));
            long later=T+10+FriendStore.DECLINE_COOLDOWN+1;
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(b,"비"),later));
            long expired=later+FriendStore.REQUEST_TTL+1;
            assertEquals(1,store.incoming(b,later+FriendStore.REQUEST_TTL).size());
            assertTrue(store.incoming(b,expired).isEmpty());assertTrue(store.outgoing(a,expired).isEmpty());assertNull(store.poll(List.of(b),expired).incoming().get(b));
            assertEquals(FriendStore.Result.MISSING,store.accept(b,"비","",a,expired),"An expired request cannot be accepted");
            assertEquals(FriendStore.Result.SENT,store.request(b,"비","",friend(a,"에이"),expired),"An expired reverse request is not a mutual accept");
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(UUID.randomUUID(),"other"),expired));
            store.purge(expired+FriendStore.REQUEST_TTL+1);assertTrue(store.outgoing(a,expired).isEmpty());assertTrue(store.outgoing(b,expired).isEmpty());
        }
    }
    @Test void outstandingRequestsAreCappedPerSender()throws Exception{
        UUID a=UUID.randomUUID();var targets=new ArrayList<UUID>();
        try(var store=new FriendStore(dir.resolve("cap.db"))){
            for(int i=0;i<FriendStore.REQUEST_LIMIT;i++){UUID id=UUID.randomUUID();targets.add(id);assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(id,"t"+i),T+i));}
            UUID extra=UUID.randomUUID();
            assertEquals(FriendStore.Result.LIMIT,store.request(a,"에이","",friend(extra,"extra"),T+100));assertTrue(store.incoming(extra,T+100).isEmpty());
            assertEquals(FriendStore.REQUEST_LIMIT,store.outgoing(a,T+100).size());
            assertTrue(store.withdraw(a,targets.getFirst(),T+100));
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(extra,"extra"),T+101));
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(UUID.randomUUID(),"late"),T+FriendStore.REQUEST_TTL+50),"Expired requests no longer count");
        }
    }
    @Test void acceptWritesBothDirectionsOrNothing()throws Exception{
        UUID full=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var store=new FriendStore(dir.resolve("atomic.db"))){
            for(int i=0;i<SocialProtocol.LIMIT;i++)assertTrue(store.add(full,friend(UUID.randomUUID(),"f"+i)));
            assertEquals(FriendStore.Result.FRIEND_LIMIT,store.request(full,"풀","",friend(a,"에이"),T),"A full list cannot send requests");
            assertEquals(FriendStore.Result.SENT,store.request(a,"에이","",friend(full,"풀"),T));
            assertEquals(FriendStore.Result.FRIEND_LIMIT,store.accept(full,"풀","",a,T+1));
            assertFalse(has(store,a,full));assertFalse(has(store,full,a));assertEquals(1,store.incoming(full,T+1).size(),"A refused accept keeps the request");assertFalse(store.pendingSignal(a));
            // 신청을 보낸 뒤 보낸 사람의 목록이 가득 찬 경우
            assertEquals(FriendStore.Result.SENT,store.request(b,"비","",friend(a,"에이"),T));
            for(int i=0;i<SocialProtocol.LIMIT;i++)assertTrue(store.add(b,friend(UUID.randomUUID(),"g"+i)));
            assertEquals(FriendStore.Result.TARGET_FRIEND_LIMIT,store.accept(a,"에이","",b,T+1));
            assertFalse(has(store,a,b));assertFalse(has(store,b,a));assertEquals(SocialProtocol.LIMIT,store.load(b).size());assertEquals(1,store.incoming(a,T+1).size());
        }
    }
    @Test void renamesReachPendingRequests()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var store=new FriendStore(dir.resolve("rename.db"))){
            store.request(a,"old","루미나",friend(b,"target"),T);store.profile(a,"새이름","노크세르");store.profile(b,"새상대","아르케온");
            assertEquals("새이름",store.incoming(b,T).getFirst().name());assertEquals("노크세르",store.incoming(b,T).getFirst().dormitory());assertEquals("새상대",store.outgoing(a,T).getFirst().name());
        }
    }
    @Test void requestKindsRoundTripAndRejectMalformed(){
        UUID id=UUID.randomUUID();
        for(int action:new int[]{SocialProtocol.ACCEPT,SocialProtocol.DECLINE,SocialProtocol.WITHDRAW}){
            var r=new SocialProtocol.Request(action,7,id,0,"");byte[] b=SocialProtocol.encode(r);assertEquals(r,SocialProtocol.request(b));
            for(int i=0;i<b.length;i++){byte[] cut=Arrays.copyOf(b,i);assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(cut));}
            assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(Arrays.copyOf(b,b.length+1)));
            byte[] none=b.clone();Arrays.fill(none,13,29,(byte)0);assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(none),"Target is required");
            byte[] ticket=b.clone();ticket[36]=1;assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(ticket));
            byte[] unknown=b.clone();unknown[4]=11;assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(unknown));
            assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Request(action,7,SocialProtocol.NONE,0,"")));
            assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Request(action,7,id,0,"text")));
            assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Request(action,0,id,0,"")));
        }
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Request(11,1,id,0,"")));
        // 기존 종류는 그대로 해석된다
        for(int action=SocialProtocol.LIST;action<=SocialProtocol.CANCEL;action++){var r=new SocialProtocol.Request(action,3,id,5,"abc");assertEquals(r,SocialProtocol.request(SocialProtocol.encode(r)));}
    }
    @Test void requestNotificationsAndPendingListsRoundTrip(){
        UUID id=UUID.randomUUID();
        var notice=new SocialProtocol.Response(SocialProtocol.FRIEND_REQUEST,0,id,0,"하루","루미나","",0,List.of());
        assertEquals(notice,SocialProtocol.response(SocialProtocol.encode(notice)));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.FRIEND_REQUEST,0,SocialProtocol.NONE,0,"하루","","",0,List.of())));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.FRIEND_REQUEST,0,id,0," ","","",0,List.of())));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.FRIEND_REQUEST,0,id,0,"하루","","",0,List.of(friend(UUID.randomUUID(),"x")))));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.FRIEND_REQUEST,0,id,0,"a".repeat(17),"","",0,List.of())));
        byte[] b=SocialProtocol.encode(notice);
        byte[] none=b.clone();Arrays.fill(none,13,29,(byte)0);assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(none));
        byte[] unknown=b.clone();unknown[4]=10;assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(unknown));
        for(int i=0;i<b.length;i++){byte[] cut=Arrays.copyOf(b,i);assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(cut));}
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(Arrays.copyOf(b,b.length+1)));
        var entries=new ArrayList<SocialProtocol.Entry>();for(int i=0;i<SocialProtocol.LIMIT;i++)entries.add(new SocialProtocol.Entry(UUID.randomUUID(),"player"+i,"노크세르",false));
        for(int kind:new int[]{SocialProtocol.INCOMING,SocialProtocol.OUTGOING}){
            var list=new SocialProtocol.Response(kind,0,SocialProtocol.NONE,0,"","","",0,entries);byte[] bytes=SocialProtocol.encode(list);
            assertTrue(bytes.length<SocialProtocol.MAX_BYTES);assertEquals(list,SocialProtocol.response(bytes));
            assertEquals(new SocialProtocol.Response(kind,0,SocialProtocol.NONE,0,"","","",0,List.of()),SocialProtocol.response(SocialProtocol.encode(new SocialProtocol.Response(kind,0,SocialProtocol.NONE,0,"","","",0,List.of()))));
        }
        var twice=new ArrayList<>(entries.subList(0,2));twice.add(entries.getFirst());
        byte[] duplicate=SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.INCOMING,0,SocialProtocol.NONE,0,"","","",0,twice));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(duplicate));
        for(int kind=SocialProtocol.SNAPSHOT;kind<=SocialProtocol.OPEN;kind++){var r=new SocialProtocol.Response(kind,4,id,9,"하루","루미나","본문",10,List.of());assertEquals(r,SocialProtocol.response(SocialProtocol.encode(r)));}
    }
    @Test void previousProtocolVersionIsRejectedCleanlyAndRecognised(){
        byte[] request=SocialProtocol.encode(new SocialProtocol.Request(SocialProtocol.LIST,1,SocialProtocol.NONE,0,""));
        byte[] response=SocialProtocol.encode(new SocialProtocol.Response(SocialProtocol.SNAPSHOT,1,SocialProtocol.NONE,0,"","","",0,List.of()));
        assertFalse(SocialProtocol.legacy(request));assertFalse(SocialProtocol.legacy(null));assertFalse(SocialProtocol.legacy(new byte[3]));
        byte[] oldRequest=request.clone(),oldResponse=response.clone();oldRequest[3]=1;oldResponse[3]=1; // v1 = 0x534F4301
        assertTrue(SocialProtocol.legacy(oldRequest));
        assertThrows(IllegalArgumentException.class,()->SocialProtocol.request(oldRequest));assertThrows(IllegalArgumentException.class,()->SocialProtocol.response(oldResponse));
    }
}
