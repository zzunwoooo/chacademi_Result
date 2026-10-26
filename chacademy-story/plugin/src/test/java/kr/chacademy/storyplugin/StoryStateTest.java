package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
/** progress.yml 상태 (완료 기록, 대기열, 실행 대기 효과) 와 쓰기 스레드. Bukkit 없이. */
class StoryStateTest {
    static final UUID U=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Test void legacyProgressFileBecomesAnActiveDialogueWithItsFiredEvents(){
        Map<String,Object> old=new LinkedHashMap<>();
        old.put(U.toString(),new LinkedHashMap<>(Map.of("id","ch1_wakeup","scene","dream","line",1,"affinity-changed",0,"fired",List.of("told_dream"))));
        old.put("not-a-uuid",Map.of("id","x"));
        List<String> notes=new ArrayList<>();
        StoryState st=StoryState.fromMap(old,notes);
        var ps=st.players.get(U);assertNotNull(ps);assertEquals(1,st.players.size());
        assertEquals(StoryState.Kind.DIALOGUE,ps.active.kind);assertEquals("ch1_wakeup",ps.active.id);
        assertEquals("dream",ps.active.scene);assertEquals(1,ps.active.line);
        assertEquals(Set.of("told_dream"),ps.fired.get("ch1_wakeup"));
        assertFalse(notes.isEmpty());
    }
    @Test void snapshotRoundTripKeepsLedgerQueueAndPendingEffects(){
        StoryState st=new StoryState();var ps=st.of(U);ps.name="Steve";
        ps.active=new StoryState.Request(StoryState.Kind.CUTSCENE,"ch1_ashen_night",ps.nextSeq());ps.active.mode=2;ps.active.sent=true;
        var q=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1_wakeup",ps.nextSeq());q.replay=true;q.scene="wake";q.line=3;ps.queue.add(q);
        var d=new StoryState.Done();d.scene="go_friend";d.at=1760000000000L;d.times=2;ps.done.put("ch0_intro",d);
        ps.fired.computeIfAbsent("ch0_intro",k->new TreeSet<>()).add("c_a");ps.gained.put("ch0_intro",7);
        ps.effects.add(new StoryState.Effect(ps.nextSeq(),StoryState.Effect.DIALOGUE_END,"ch0_intro","go_friend",false,false));
        st.of(UUID.randomUUID());                                   // 기록 없는 플레이어는 저장하지 않는다
        Map<String,Object> snap=st.snapshot();
        ps.done.clear();ps.queue.clear();                           // 복사본은 이후 변경과 무관
        StoryState back=StoryState.fromMap(snap,new ArrayList<>());
        assertEquals(1,back.players.size());var b=back.players.get(U);
        assertEquals("Steve",b.name);assertEquals("ch1_ashen_night",b.active.id);assertEquals(2,b.active.mode);
        assertFalse(b.active.sent);                                 // 보냄 여부는 저장하지 않는다 (접속하면 다시 보냄)
        assertEquals(1,b.queue.size());assertTrue(b.queue.peek().replay);assertEquals("wake",b.queue.peek().scene);assertEquals(3,b.queue.peek().line);
        assertEquals("go_friend",b.done.get("ch0_intro").scene);assertEquals(2,b.done.get("ch0_intro").times);assertEquals(1760000000000L,b.done.get("ch0_intro").at);
        assertEquals(Set.of("c_a"),b.fired.get("ch0_intro"));assertEquals(7,b.gained.get("ch0_intro").intValue());
        assertEquals(1,b.effects.size());assertTrue(b.effects.get(0).durable);assertEquals("go_friend",b.effects.get(0).name);
        assertTrue(b.nextSeq()>3);                                  // 번호는 이어서
    }
    @Test void newerFormatIsRefusedAndBadEntriesAreSkipped(){
        assertThrows(IllegalArgumentException.class,()->StoryState.fromMap(Map.of("format",99,"players",Map.of()),new ArrayList<>()));
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("active",Map.of("kind","dialogue","id","../evil","seq",1));
        p.put("done",Map.of("ok_id",Map.of("scene","end","at",5,"times",1),"Bad Id",Map.of()));
        p.put("effects",List.of(Map.of("seq",2,"type","rm -rf","id","x"),Map.of("seq",3,"type","event","id","ok_id","name","c_1")));
        StoryState st=StoryState.fromMap(Map.of("format",2,"players",Map.of(U.toString(),p)),new ArrayList<>());
        var ps=st.players.get(U);assertNull(ps.active);assertEquals(Set.of("ok_id"),ps.done.keySet());assertEquals(1,ps.effects.size());
    }
    @Test void ledgerBlocksDuplicateRequestsOfTheSameId(){
        var ps=new StoryState.PlayerStory();
        ps.active=new StoryState.Request(StoryState.Kind.DIALOGUE,"a",1);ps.queue.add(new StoryState.Request(StoryState.Kind.CUTSCENE,"b",2));
        assertTrue(ps.has(StoryState.Kind.DIALOGUE,"a"));assertTrue(ps.has(StoryState.Kind.CUTSCENE,"b"));
        assertFalse(ps.has(StoryState.Kind.CUTSCENE,"a"));assertFalse(ps.has(StoryState.Kind.DIALOGUE,"c"));
    }
    @Test void completedDialogueOpensOnlyAsReplayAndReplayGrantsNothing(){
        var ps=new StoryState.PlayerStory();
        assertEquals(StoryState.Open.FRESH,ps.decideOpen("ch1",false,null,false));
        var run=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1",ps.nextSeq());ps.active=run;
        assertEquals(StoryState.Open.DUPLICATE,ps.decideOpen("ch1",false,null,false));   // 진행 중인 대화를 또 열라고 해도 덮어쓰지 않는다
        assertTrue(ps.claimEvent(run,"c_1"));assertFalse(ps.claimEvent(run,"c_1"));       // 이벤트는 한 번
        assertTrue(ps.recordDone(run,"ending_a",1000));ps.active=null;
        assertEquals("ending_a",ps.done.get("ch1").scene);
        // 끝낸 뒤: 기본은 열지 않음, replay 인자나 allow-replay 가 있을 때만 다시 보기
        assertEquals(StoryState.Open.ALREADY_DONE,ps.decideOpen("ch1",false,null,false));
        assertEquals(StoryState.Open.REPLAY,ps.decideOpen("ch1",true,null,false));
        assertEquals(StoryState.Open.REPLAY,ps.decideOpen("ch1",false,null,true));        // config allow-replay
        assertEquals(StoryState.Open.REPLAY,ps.decideOpen("ch1",false,Boolean.TRUE,false)); // 대화별 allow-replay
        assertEquals(StoryState.Open.ALREADY_DONE,ps.decideOpen("ch1",false,Boolean.FALSE,true)); // 대화별 값이 config 보다 먼저
        var replay=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1",ps.nextSeq());replay.replay=true;
        assertFalse(ps.claimEvent(replay,"c_2"));                                         // 다시 보기: 새 이벤트도 주지 않고
        assertFalse(ps.fired.get("ch1").contains("c_2"));
        assertFalse(ps.recordDone(replay,"ending_b",2000));                               // 끝 효과도 기록 변경도 없다
        assertEquals("ending_a",ps.done.get("ch1").scene);assertEquals(1,ps.done.get("ch1").times);
        // 관리자가 기록을 지우면 처음부터 (보상 포함)
        assertTrue(ps.resetLedger("ch1"));assertFalse(ps.resetLedger("ch1"));
        assertEquals(StoryState.Open.FRESH,ps.decideOpen("ch1",false,null,false));
        var again=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1",ps.nextSeq());
        assertTrue(ps.claimEvent(again,"c_1"));
    }
    @Test void interruptedRunKeepsFiredEventsSoReopeningDoesNotGrantTwice(){
        var ps=new StoryState.PlayerStory();
        var first=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1",ps.nextSeq());
        assertTrue(ps.claimEvent(first,"c_1"));
        // 관리자가 중간에 멈춤 (/대화관리 중지): 완료 기록은 없지만 실행한 이벤트 기록은 남는다
        assertEquals(StoryState.Open.FRESH,ps.decideOpen("ch1",false,null,false));
        var second=new StoryState.Request(StoryState.Kind.DIALOGUE,"ch1",ps.nextSeq());
        assertFalse(ps.claimEvent(second,"c_1"));assertTrue(ps.claimEvent(second,"c_2"));
        for(int i=0;i<StoryState.MAX_QUEUE;i++)ps.queue.add(new StoryState.Request(StoryState.Kind.CUTSCENE,"c"+i,ps.nextSeq()));
        assertEquals(StoryState.Open.QUEUE_FULL,ps.decideOpen("ch2",false,null,false));
    }

    // ---------------------------------------------------------------- 쓰기 스레드
    static final class Fixture {
        final List<String> written=Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<Runnable> mainQueue=new ConcurrentLinkedQueue<>();
        final CountDownLatch gate=new CountDownLatch(1);
        volatile boolean block,fail;
        final ScheduledExecutorService worker=StoryStore.newWorker();
        final StoryStore store=new StoryStore(text->{
            if(block){try{gate.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}
            if(fail)throw new java.io.IOException("disk full");
            written.add(text);
        },worker,mainQueue::add,Logger.getAnonymousLogger());
        void settle()throws Exception{worker.submit(()->{}).get(5,TimeUnit.SECONDS);}
        int runMain(){int n=0;for(Runnable r;(r=mainQueue.poll())!=null;n++)r.run();return n;}
    }
    @Test void manyRequestsWhileWritingCollapseIntoOneWriteAndCallbacksRunAfterIt() throws Exception {
        Fixture f=new Fixture();f.block=true;AtomicInteger effects=new AtomicInteger();
        f.store.submit(()->"v0",List.of(effects::incrementAndGet));
        for(int i=1;i<=100;i++){String v="v"+i;f.store.submit(()->v,List.of(effects::incrementAndGet));}
        assertEquals(0,effects.get());                              // 디스크에 쓰이기 전에는 효과 없음
        f.block=false;f.gate.countDown();f.settle();f.settle();
        assertTrue(f.written.size()<=2,f.written.toString());       // 101번 요청 = 많아야 2번 쓰기 (쓰던 것 + 마지막 것)
        assertEquals("v100",f.written.get(f.written.size()-1));
        f.runMain();assertEquals(101,effects.get());
        f.worker.shutdownNow();
    }
    @Test void failedWriteRunsNoCallbacksAndFlushNowWritesLatestState() throws Exception {
        Fixture f=new Fixture();f.fail=true;AtomicInteger effects=new AtomicInteger();
        f.store.submit(()->"lost",List.of(effects::incrementAndGet));
        f.settle();
        assertTrue(f.written.isEmpty());assertEquals(0,f.runMain());assertEquals(0,effects.get());
        f.fail=false;
        assertTrue(f.store.flushNow(()->"final"));                  // 서버가 꺼질 때: 그 자리에서 마지막 상태를 쓴다
        assertEquals(List.of("final"),f.written);
        assertEquals(0,effects.get());                              // 밀린 효과는 effects 에 남아 다음 접속 때 실행
    }
}
