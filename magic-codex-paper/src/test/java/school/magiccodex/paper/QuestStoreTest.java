package school.magiccodex.paper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.QuestProtocol;

class QuestStoreTest {
    @TempDir Path dir;
    static final DatabaseSettings SQLITE=new DatabaseSettings(false,"","","");
    static QuestDefinition quest(String id,boolean daily){return new QuestDefinition(id,"좀비 순찰","의뢰 설명","",daily,true,List.of(new QuestDefinition.Objective(QuestDefinition.Type.KILL,"ZOMBIE",10,"좀비 처치","wild","")),"에메랄드 3개",List.of(),List.of("EMERALD:3"));}
    @Test void schoolAcceptWildProgressAndDuplicateClaim()throws Exception{
        Path path=dir.resolve("q.db");UUID id=UUID.randomUUID();long now=System.currentTimeMillis();var q=quest("hunt",true);
        try(var school=new QuestStore(SQLITE,path);var wild=new QuestStore(SQLITE,path)){
            school.seed(Map.of(q.id(),q));assertEquals(q,wild.catalog().get(q.id()));assertTrue(school.accept(id,q,now));assertFalse(wild.accept(id,q,now));
            wild.advance(id,QuestDefinition.Type.KILL,"ZOMBIE","school","world",10,now);assertEquals(0,school.entries(id).getFirst().progress()[0]);
            wild.advance(id,QuestDefinition.Type.KILL,"SKELETON","wild","world",10,now);assertEquals(0,school.entries(id).getFirst().progress()[0]);
            wild.advance(id,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",7,now);school.advance(id,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",8,now);
            assertEquals(10,wild.entries(id).getFirst().progress()[0]);var claim=school.reserve(id,q.id(),q.cycle(now),now);assertNotNull(claim);assertNull(wild.reserve(id,q.id(),q.cycle(now),now));assertFalse(wild.abandon(id,q.id(),now));
            school.finish(id,claim,true);assertNull(wild.reserve(id,q.id(),q.cycle(now),now));assertEquals("claimed",wild.entries(id).getFirst().status());
        }
    }
    @Test void dailyResetAndMaximumThree()throws Exception{try(var store=new QuestStore(SQLITE,dir.resolve("q.db"))){UUID p=UUID.randomUUID();long n=System.currentTimeMillis();for(int i=0;i<3;i++)assertTrue(store.accept(p,quest("q"+i,true),n));assertFalse(store.accept(p,quest("q3",true),n));assertTrue(store.accept(p,quest("q0",true),n+86400000));assertTrue(store.abandon(p,"q1",n));}}
    @Test void acceptedSnapshotSurvivesCatalogChange()throws Exception{try(var store=new QuestStore(SQLITE,dir.resolve("q.db"))){UUID p=UUID.randomUUID();var q=quest("q",false);store.seed(Map.of("q",q));store.accept(p,q,System.currentTimeMillis());store.publish(Map.of());assertTrue(store.catalog().isEmpty());assertEquals(q,store.entries(p).getFirst().quest());}}
    @Test void interruptedPayoutNeedsExplicitReview()throws Exception{UUID p=UUID.randomUUID();long n=System.currentTimeMillis();var q=quest("q",false);try(var store=new QuestStore(SQLITE,dir.resolve("q.db"))){store.accept(p,q,n);store.advance(p,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",10,n);assertNotNull(store.reserve(p,"q","once",n));}try(var store=new QuestStore(SQLITE,dir.resolve("q.db"))){assertNull(store.reserve(p,"q","once",n));assertFalse(store.accept(p,q,n));store.resolve(p,"q","once",false);assertNotNull(store.reserve(p,"q","once",n));}}
    @Test void submitProgressCannotBeForged()throws Exception{var q=new QuestDefinition("item","납품","","",false,true,List.of(new QuestDefinition.Objective(QuestDefinition.Type.SUBMIT,"WHEAT",32,"밀","","")),"",List.of(),List.of());try(var store=new QuestStore(SQLITE,dir.resolve("q.db"))){UUID p=UUID.randomUUID();long n=System.currentTimeMillis();store.accept(p,q,n);store.advance(p,QuestDefinition.Type.SUBMIT,"WHEAT","school","world",32,n);assertEquals(0,store.entries(p).getFirst().progress()[0]);}}
    @Test void incompleteCannotClaimAndEventsClamp()throws Exception{try(var s=new QuestStore(SQLITE,dir.resolve("q.db"))){var q=quest("q",false);UUID p=UUID.randomUUID();long n=System.currentTimeMillis();s.accept(p,q,n);assertNull(s.reserve(p,"q","once",n));s.advance(p,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",Integer.MAX_VALUE,n);assertEquals(10,s.entries(p).getFirst().progress()[0]);}}
    @Test void definitionAndProtocolRoundTrip(){var q=quest("q",true);assertEquals(q,QuestDefinition.decode(q.encode()));var r=new QuestProtocol.Response(true,0,1,"수락 완료",List.of(new QuestProtocol.Card("q","순찰","설명","보상","active",List.of(new QuestProtocol.Goal("처치",4,10)))));assertEquals(r,QuestProtocol.response(QuestProtocol.encode(r)));assertThrows(IllegalArgumentException.class,()->QuestProtocol.request(QuestProtocol.encode(new QuestProtocol.Request(99,0,"q"))));assertThrows(IllegalArgumentException.class,()->QuestProtocol.request(new byte[40000]));byte[] bytes=QuestProtocol.encode(new QuestProtocol.Request(0,0,""));assertThrows(IllegalArgumentException.class,()->QuestProtocol.request(Arrays.copyOf(bytes,bytes.length+1)));}
    @Test void batchedProgressIsOneWriteCappedAtGoalAndNeverForgesSubmit()throws Exception{
        var q=new QuestDefinition("mix","혼합","","",false,true,List.of(new QuestDefinition.Objective(QuestDefinition.Type.KILL,"ZOMBIE",10,"좀비","",""),new QuestDefinition.Objective(QuestDefinition.Type.EVENT,"bell",2,"종","","school_world"),new QuestDefinition.Objective(QuestDefinition.Type.SUBMIT,"WHEAT",5,"밀","","")),"",List.of(),List.of());
        try(var s=new QuestStore(SQLITE,dir.resolve("q.db"))){UUID p=UUID.randomUUID();long n=System.currentTimeMillis();assertTrue(s.accept(p,q,n));s.advanceAll(p,List.of(),"school",n);
            s.advanceAll(p,List.of(new QuestStore.Delta(QuestDefinition.Type.KILL,"ZOMBIE","wild_world",7),new QuestStore.Delta(QuestDefinition.Type.KILL,"ZOMBIE","school_world",8),new QuestStore.Delta(QuestDefinition.Type.EVENT,"bell","wild_world",5),new QuestStore.Delta(QuestDefinition.Type.EVENT,"bell","school_world",1),new QuestStore.Delta(QuestDefinition.Type.SUBMIT,"WHEAT","school_world",5),new QuestStore.Delta(QuestDefinition.Type.KILL,"SKELETON","school_world",0)),"school",n);
            assertArrayEquals(new int[]{10,1,0},s.entries(p).getFirst().progress());}
    }
    @Test void payoutStageIsRecordedForRetryAndClearedOnFinish()throws Exception{
        try(var s=new QuestStore(SQLITE,dir.resolve("q.db"))){var q=quest("q",false);UUID p=UUID.randomUUID();long n=System.currentTimeMillis();s.accept(p,q,n);s.advance(p,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",10,n);assertNull(s.paying(p,"q","once"));
            var claim=s.reserve(p,"q","once",n);assertEquals(0,claim.stage());assertEquals(0,s.paying(p,"q","once").stage());
            s.stage(p,claim,QuestStore.ITEMS|QuestStore.RECORDED);var held=s.paying(p,"q","once");assertEquals(QuestStore.ITEMS|QuestStore.RECORDED,held.stage());assertEquals(claim.token(),held.token());
            // reset(예전 retry): 수령 전으로 되돌리면 단계 기록도 지운다.
            s.resolve(p,"q","once",false);assertNull(s.paying(p,"q","once"));var again=s.reserve(p,"q","once",n);assertEquals(0,s.paying(p,"q","once").stage());assertThrows(java.sql.SQLException.class,()->s.stage(p,claim,QuestStore.ITEMS));
            s.stage(p,again,15|QuestStore.RECORDED);s.finish(p,again,true);assertNull(s.paying(p,"q","once"));assertEquals("claimed",s.entries(p).getFirst().status());assertEquals(0,s.entries(p).getFirst().stage());}
    }
    @Test void catalogRevisionMovesOnlyWithCatalogWrites()throws Exception{
        try(var s=new QuestStore(SQLITE,dir.resolve("q.db"))){var q=quest("q",false);long start=s.catalogRevision();s.seed(Map.of("q",q));long seeded=s.catalogRevision();assertTrue(seeded>start);UUID p=UUID.randomUUID();long n=System.currentTimeMillis();s.accept(p,q,n);s.advance(p,QuestDefinition.Type.KILL,"ZOMBIE","wild","world",1,n);assertEquals(seeded,s.catalogRevision());
            assertTrue(s.edit("q",QuestStore.fingerprint(q),q.release(0,false)));long edited=s.catalogRevision();assertTrue(edited>seeded);s.release(List.of("q"),n,0,true);assertTrue(s.catalogRevision()>edited);long before=s.catalogRevision();s.publish(Map.of());assertTrue(s.catalogRevision()>before);}
    }
}
