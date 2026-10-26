package school.magiccodex.paper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import school.magiccodex.protocol.SchoolProtocol;
import school.magiccodex.protocol.SchoolProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class SchoolTest {
    @TempDir Path dir;
    private Donation donation(String id,UUID donor,int house,long time){return new Donation(id,"수확의 바람",donor,"별하",house,time);}
    @Test void firstClaimIsGlobalAndPersistsAcrossRestart()throws Exception{
        Path file=dir.resolve("school.db");UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var s=new SchoolStore(file)){assertTrue(s.donate(donation("wind",a,0,1),5));assertFalse(s.donate(donation("wind",b,1,2),99));assertEquals(List.of(5L,0L,0L,0L),s.snapshot().scores());s.house(a,2);}
        try(var s=new SchoolStore(file)){assertFalse(s.donate(donation("wind",b,1,3),99));assertEquals(a,s.snapshot().records().getFirst().donor());assertEquals(2,s.snapshot().houses().get(a));}
    }
    @Test void queuedSimultaneousClaimAwardsExactlyOnce()throws Exception{
        var executor=Executors.newSingleThreadExecutor();
        try(var s=new SchoolStore(dir.resolve("queue.db"))){var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<40;i++)futures.add(executor.submit(()->s.donate(donation("wind",UUID.randomUUID(),0,1),7)));int successes=0;for(var f:futures)if(f.get())successes++;assertEquals(1,successes);assertEquals(7L,s.snapshot().scores().getFirst());}
        finally{executor.shutdown();}
    }
    @Test void pointChangesIndependentAndResetDoesNotEraseOtherData()throws Exception{
        try(var s=new SchoolStore(dir.resolve("reset.db"))){UUID id=UUID.randomUUID();s.change(1,50,false);s.change(1,-20,false);assertTrue(s.donate(donation("a",id,1,1),2));assertTrue(s.donate(donation("b",id,2,2),3));assertEquals("b",s.snapshot().records().getFirst().spell());s.reset("a");assertEquals(1,s.snapshot().records().size());assertEquals(32L,s.snapshot().scores().get(1));assertTrue(s.donate(donation("a",id,1,3),2));s.resetPoints();assertEquals(2,s.snapshot().records().size());assertEquals(List.of(0L,0L,0L,0L),s.snapshot().scores());s.reset("all");assertTrue(s.snapshot().records().isEmpty());}
    }
    @Test void failedPointsUpdateRollsBackDonation()throws Exception{
        try(var s=new SchoolStore(dir.resolve("rollback.db"))){s.change(0,SchoolStore.MAX_SCORE,true);assertThrows(IllegalArgumentException.class,()->s.donate(donation("wind",UUID.randomUUID(),0,1),1));assertTrue(s.snapshot().records().isEmpty());assertEquals(SchoolStore.MAX_SCORE,s.snapshot().scores().getFirst());}
    }
    @Test void boundedProtocolAndKoreanDisplayNames(){
        var r=new Request(SchoolProtocol.DONATE,1,0,"wind");assertEquals(r,SchoolProtocol.request(SchoolProtocol.encode(r)));
        assertThrows(IllegalArgumentException.class,()->SchoolProtocol.encode(new Request(3,1,0,"../../evil")));
        assertThrows(IllegalArgumentException.class,()->SchoolProtocol.request(new byte[25000]));
        var response=new Response(1,1,2,0,1,"","","별하","루미나",List.of(0L,50L,0L,0L),List.of(donation("wind",UUID.randomUUID(),1,1)));
        assertEquals(response,SchoolProtocol.response(SchoolProtocol.encode(response)));
        byte[] bytes=SchoolProtocol.encode(response);assertThrows(IllegalArgumentException.class,()->SchoolProtocol.response(Arrays.copyOf(bytes,bytes.length+1)));
    }
    @Test void donationAnnouncementUsesCorrectObjectParticle(){
        assertEquals("을",SchoolBridge.objectParticle("수확의 바람"));assertEquals("를",SchoolBridge.objectParticle("요정 가루"));
        assertEquals("을",SchoolBridge.objectParticle("봉인진"));assertEquals("를",SchoolBridge.objectParticle("바람바구니"));assertEquals("을(를)",SchoolBridge.objectParticle("feather fall"));
    }
}
