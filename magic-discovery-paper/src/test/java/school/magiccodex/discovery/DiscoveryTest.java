package school.magiccodex.discovery;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.protocol.DiscoveryProtocol;
import static org.junit.jupiter.api.Assertions.*;
class DiscoveryTest {
    @TempDir Path dir;
    @Test void approvedDefinitionsLoadWithDottedKeysAndExactCropConjunction()throws Exception{
        var d=new Definitions(Path.of("src/main/resources/discoveries.yml").toFile());assertEquals(299,d.spells.size()); /* 2026-10-26 v2: 300종 - 아공간 수조 */
        var crop=d.spells.get("harvest_wind");assertEquals(3,crop.requirements().size());assertEquals(12,crop.requirements().get("break.young_wheat"));
        assertFalse(Definitions.meets(crop,Map.of("break.young_wheat",36d)));
        assertTrue(Definitions.meets(crop,Map.of("break.young_wheat",12d,"break.young_carrots",12d,"break.young_potatoes",12d)));
        assertEquals(1,d.spells.get("cross_breeze").chance());assertEquals(1,d.spells.get("wind_push").chance());
        // 2026-10-26 오너 결정 3: 메테오 스트라이크 = 화염 마법 20종 (선행 마법 목록 없음)
        assertTrue(d.spells.get("falling_star").prerequisites().isEmpty());assertEquals(Map.of("learned.fire",20d),d.spells.get("falling_star").requirements());
        assertEquals(Map.of("hit.arrow_monster",30d),d.spells.get("trajectory_acceleration").requirements());
    }
    @Test void spatialLeapRequiresOnePublicWaypointAndNoOtherProgress()throws Exception{
        // Owner-approved travel-map rule (2026-10-24): one shared, public waypoint; not a personal point or wild spawn.
        var d=new Definitions(Path.of("src/main/resources/discoveries.yml").toFile());
        var leap=d.spells.get("spatial_leap");assertNotNull(leap);
        assertEquals(Map.of("state.travel_waypoints",1d),leap.requirements());
        assertEquals("magic.learned.spatial_leap",leap.permission());assertEquals(1d,leap.chance());assertTrue(leap.prerequisites().isEmpty());
        assertFalse(Definitions.meets(leap,Map.of()));assertFalse(Definitions.meets(leap,Map.of("state.travel_waypoints",0d)));
        assertFalse(Definitions.meets(leap,Map.of("state.personal_waypoints",1d,"visit.wild_spawn",1d)));
        assertTrue(Definitions.meets(leap,Map.of("state.travel_waypoints",1d)));assertTrue(Definitions.meets(leap,Map.of("state.travel_waypoints",2d)));
    }
    @Test void invalidDefinitionCannotGrantUnconditionally()throws Exception{
        var p=dir.resolve("bad.yml");Files.writeString(p,"spells:\n  test:\n    name: Test\n    permission: magic.test\n    icon: magic:test.png\n    requirements: {}\n");assertThrows(Exception.class,()->new Definitions(p.toFile()));
    }
    @Test void simultaneousDiscoverersProduceOneGlobalFirstAndDuplicateIsIdempotent()throws Exception{
        try(var db=new DiscoveryStore(dir.resolve("test.db"))){var a=UUID.randomUUID();var b=UUID.randomUUID();
            var first=db.acquire(a,"wind",Map.of("pickup.items",1000d),false);var second=db.acquire(b,"wind",Map.of(),false);
            assertTrue(first.get().acquisition().first());assertFalse(second.get().acquisition().first());assertEquals(0,second.get().acquisition().reward());
            var repeat=db.acquire(a,"wind",Map.of(),false).get();assertFalse(repeat.created());assertEquals(first.get().acquisition().token(),repeat.acquisition().token());assertEquals(1,db.load(a).get().acquired().size());
        }
    }
    @Test void progressAndPermissionClaimsSurviveRestartWithOwnerBoundAcknowledgement()throws Exception{
        var id=UUID.randomUUID();long token;Path path=dir.resolve("restart.db");
        try(var db=new DiscoveryStore(path)){db.save(id,Map.of("break.young_wheat",11d)).get();token=db.acquire(id,"spell",Map.of("break.young_wheat",12d),false).get().acquisition().token();db.acknowledge(UUID.randomUUID(),token).get();assertFalse(db.load(id).get().acquired().getFirst().notified());}
        try(var db=new DiscoveryStore(path)){var data=db.load(id).get();assertEquals(12,data.progress().get("break.young_wheat"));assertEquals(token,data.acquired().getFirst().token());db.acknowledge(id,token).get();assertTrue(db.load(id).get().acquired().getFirst().notified());}
    }
    @Test void rewardReservationCannotDuplicateAndWrongOwnerCannotClaim()throws Exception{
        var id=UUID.randomUUID();try(var db=new DiscoveryStore(dir.resolve("reward.db"))){long t=db.acquire(id,"spell",Map.of(),false).get().acquisition().token();
            assertFalse(db.reserve(UUID.randomUUID(),t).get());assertTrue(db.reserve(id,t).get());assertFalse(db.reserve(id,t).get());db.unreserve(id,t).get();assertTrue(db.reserve(id,t).get());db.delivered(id,t).get();assertFalse(db.reserve(id,t).get());assertEquals(3,db.load(id).get().acquired().getFirst().reward());
        }
    }
    @Test void perPlayerRewardSettingKeepsGlobalFirstDistinction()throws Exception{try(var db=new DiscoveryStore(dir.resolve("scope.db"))){db.acquire(UUID.randomUUID(),"spell",Map.of(),true).get();var a=db.acquire(UUID.randomUUID(),"spell",Map.of(),true).get().acquisition();assertFalse(a.first());assertEquals(1,a.reward());}}
    @Test void countersMergeToTheHigherValueWhileStateAndTraceKeysTakeTheLatestWrite()throws Exception{
        var id=UUID.randomUUID();try(var db=new DiscoveryStore(dir.resolve("merge.db"))){
            // 서버 A의 마지막 저장(100) 뒤에 서버 B가 오래된 값(90)을 저장해도 카운터는 내려가지 않는다.
            db.save(id,Map.of("pickup.items",100d,"state.circle",5d,"trace.fell",1d)).get();
            db.save(id,Map.of("pickup.items",90d,"state.circle",3d,"trace.fell",0d)).get();
            var loaded=db.load(id).get().progress();assertEquals(100d,loaded.get("pickup.items"));assertEquals(3d,loaded.get("state.circle"));assertEquals(0d,loaded.get("trace.fell"));
            db.save(id,Map.of("pickup.items",101d)).get();assertEquals(101d,db.load(id).get().progress().get("pickup.items"));
            // 획득·관리자 경로의 진행도 기록도 같은 병합 규칙을 쓴다.
            db.acquire(id,"wind",Map.of("pickup.items",7d),false).get();db.setLearned(id,"wind",false,Set.of("pickup.items"),Map.of("pickup.items",8d)).get();
            assertEquals(101d,db.load(id).get().progress().get("pickup.items"));
            assertTrue(DiscoveryStore.monotonic("cast.wind"));assertFalse(DiscoveryStore.monotonic("state.circle"));assertFalse(DiscoveryStore.monotonic("trace.fell"));
        }
    }
    @Test void savingDirtyKeysTouchesOnlyThoseKeysAndAnEmptySaveIsANoOp()throws Exception{
        var id=UUID.randomUUID();var other=UUID.randomUUID();try(var db=new DiscoveryStore(dir.resolve("dirty.db"))){
            db.save(id,Map.of("cast.wind",4d,"pickup.items",9d)).get();db.save(other,Map.of("cast.wind",1d)).get();
            db.save(id,Map.of("cast.wind",6d)).get();db.save(id,Map.of()).get();
            assertEquals(Map.of("cast.wind",6d,"pickup.items",9d),db.load(id).get().progress());assertEquals(Map.of("cast.wind",1d),db.load(other).get().progress());
        }
    }
    @Test void learnedSignatureChangesOnlyWhenTheLearnedSetChanges()throws Exception{
        var a=UUID.randomUUID();var b=UUID.randomUUID();try(var db=new DiscoveryStore(dir.resolve("signature.db"))){
            assertEquals("",db.load(a).get().learnedSignature());assertTrue(db.learnedSignatures(List.of(a,b)).get().isEmpty());
            db.setLearned(a,"wind",true,Set.of(),Map.of()).get();String granted=db.load(a).get().learnedSignature();assertFalse(granted.isEmpty());
            assertEquals(Map.of(a,granted),db.learnedSignatures(List.of(a,b)).get());
            db.save(a,Map.of("cast.wind",3d)).get();assertEquals(granted,db.learnedSignatures(List.of(a)).get().get(a));
            db.setLearned(a,"wind",false,Set.of(),Map.of()).get();assertNotEquals(granted,db.learnedSignatures(List.of(a)).get().get(a));
            db.setLearned(a,"wind",true,Set.of(),Map.of()).get();assertEquals(granted,db.learnedSignatures(List.of(a)).get().get(a));
        }
    }
    @Test void reservedRewardsCanBeListedAndResolvedByAnAdminOnlyWhileReserved()throws Exception{
        var id=UUID.randomUUID();try(var db=new DiscoveryStore(dir.resolve("reserved.db"))){
            long first=db.acquire(id,"wind",Map.of(),true).get().acquisition().token(),second=db.acquire(id,"fire",Map.of(),true).get().acquisition().token();
            assertTrue(db.reserved(id).get().isEmpty());assertFalse(db.resolveReserved(id,first,true).get());
            assertTrue(db.reserve(id,first).get());assertTrue(db.reserve(id,second).get());
            assertEquals(List.of(first,second),db.reserved(id).get().stream().map(DiscoveryStore.Acquisition::token).toList());
            assertFalse(db.resolveReserved(UUID.randomUUID(),first,true).get());
            assertTrue(db.resolveReserved(id,first,true).get());assertTrue(db.reserve(id,first).get());
            assertTrue(db.resolveReserved(id,second,false).get());assertFalse(db.reserve(id,second).get());
            assertEquals(List.of(first),db.reserved(id).get().stream().map(DiscoveryStore.Acquisition::token).toList());
            assertEquals(3,db.load(id).get().acquired().stream().filter(v->v.token()==second).findFirst().orElseThrow().reward());
        }
    }
    @Test void protocolRejectsTruncationTrailingDataAndTraversal(){
        var n=new DiscoveryProtocol.Notice(17,"harvest_wind","수확의 바람","magiccodex:textures/spells/test.png",true);byte[] b=DiscoveryProtocol.encode(n);assertEquals(n,DiscoveryProtocol.decode(b));
        for(int i=0;i<b.length;i++){byte[] cut=Arrays.copyOf(b,i);assertThrows(IllegalArgumentException.class,()->DiscoveryProtocol.decode(cut));}
        assertThrows(IllegalArgumentException.class,()->DiscoveryProtocol.decode(Arrays.copyOf(b,b.length+1)));
        assertThrows(IllegalArgumentException.class,()->DiscoveryProtocol.encode(new DiscoveryProtocol.Notice(1,"test","x","magic:../test.png",false)));
        assertEquals(0,DiscoveryProtocol.request(DiscoveryProtocol.request(0)));assertEquals(17,DiscoveryProtocol.request(DiscoveryProtocol.request(17)));
    }
    @Test void rotationsHandleYawWrapAndRejectBackAndForthShaking(){assertEquals(20,NativeConditions.wrap(-340));assertEquals(-20,NativeConditions.wrap(340));assertEquals(-15,NativeConditions.accumulate(350,-15));assertEquals(365,NativeConditions.accumulate(350,15));}
}
