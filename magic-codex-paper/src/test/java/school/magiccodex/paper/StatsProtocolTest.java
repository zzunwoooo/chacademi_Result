package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.StatsProtocol;
import school.magiccodex.protocol.StatsProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class StatsProtocolTest {
    private static final UUID ID=UUID.fromString("fedcba98-7654-4321-1234-56789abcdef0");
    private Profile profile(){return new Profile(ID,"Mage",5,"바람",24.0,null,40,12,34.0,180.0,7.5,62,200,"skin","signature",Collections.nCopies(6,new byte[]{1,2,3}));}
    @Test void remoteStatsRoundTripPreservesIdentityMissingFieldsAndEquipment(){
        var r=StatsProtocol.decodeResponse(StatsProtocol.encodeResponse(new Response(StatsProtocol.OPEN,7,ID,"",profile())));
        assertEquals(ID,r.target());assertEquals(7,r.session());assertEquals("Mage",r.profile().name());assertEquals(62,r.profile().learned());assertNull(r.profile().popularity());
        assertEquals(180,r.profile().maximum());assertEquals("바람",r.profile().dormitory());assertArrayEquals(new byte[]{1,2,3},r.profile().equipment().get(5));
    }
    @Test void requestsAreExactBoundedAndRejectTruncationTrailingDataAndUnknownActions(){
        for(int action=1;action<=4;action++){var r=new Request(action,19,ID);byte[] b=StatsProtocol.encodeRequest(r);assertEquals(r,StatsProtocol.decodeRequest(b));
            for(int n=0;n<b.length;n++){byte[] cut=Arrays.copyOf(b,n);assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeRequest(cut));}
            assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeRequest(Arrays.copyOf(b,b.length+1)));
        }
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeRequest(new Request(5,1,ID)));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeRequest(new Request(1,0,ID)));
    }
    @Test void responseRejectsTruncationWrongTargetAndOversizedPayload(){
        byte[] b=StatsProtocol.encodeResponse(new Response(StatsProtocol.SNAPSHOT,3,ID,"",profile()));
        for(int i=0;i<b.length;i++){byte[] cut=Arrays.copyOf(b,i);assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse(cut));}
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse(Arrays.copyOf(b,b.length+1)));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse(new byte[30001]));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeResponse(new Response(StatsProtocol.OPEN,3,UUID.randomUUID(),"",profile())));
    }
    @Test void missingExtrasAndNoticeRemainUnambiguous(){
        var r=new Response(StatsProtocol.NOTICE,3,ID,"해당 기능은 아직 준비 중입니다.",null);
        assertEquals(r,StatsProtocol.decodeResponse(StatsProtocol.encodeResponse(r)));
        assertThrows(IllegalArgumentException.class,()->new StatsService.Additional(10,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new StatsService.Additional(1,null,Double.NaN,null));
    }
    private Extra extra(){return new Extra("별빛을 품은 탐구자",342,3,"낚시 도구 받기",1,2,List.of(new byte[]{4},new byte[0],new byte[]{5,6}),"orrery","oak_broom",true,2);}
    @Test void v3RoundTripCarriesExtrasAndStaysOffTheOldChannel(){
        var r=StatsProtocol.decodeResponse3(StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.OPEN,9,ID,"",profile()),extra(),null)));
        assertEquals("Mage",r.base().profile().name());assertEquals("별빛을 품은 탐구자",r.extra().title());assertEquals(342,r.extra().likes());
        assertEquals(3,r.extra().activeQuests());assertEquals("낚시 도구 받기",r.extra().tracked());assertEquals(1,r.extra().trackedCurrent());assertEquals(2,r.extra().trackedRequired());
        assertArrayEquals(new byte[]{5,6},r.extra().accessories().get(2));assertEquals("orrery",r.extra().artifact());assertEquals("oak_broom",r.extra().broom());
        assertTrue(r.extra().voted());assertEquals(2,r.extra().tickets());
        byte[] v3=StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.SNAPSHOT,9,ID,"",profile()),extra(),null));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse(v3));
        byte[] v2=StatsProtocol.encodeResponse(new Response(StatsProtocol.SNAPSHOT,9,ID,"",profile()));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse3(v2));
        for(int i=0;i<v3.length;i+=7){byte[] cut=Arrays.copyOf(v3,i);assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeResponse3(cut));}
    }
    @Test void v3UnknownValuesStayUnknown(){
        var none=new Extra("",null,null,"",0,0,List.of(new byte[0],new byte[0],new byte[0]),"","",false,0);
        var r=StatsProtocol.decodeResponse3(StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.OPEN,1,ID,"",profile()),none,null)));
        assertNull(r.extra().likes());assertNull(r.extra().activeQuests());assertEquals("",r.extra().tracked());
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.OPEN,1,ID,"",profile()),
            new Extra("",null,null,"",3,2,List.of(new byte[0],new byte[0],new byte[0]),"","",false,0),null)));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.OPEN,1,ID,"",profile()),null,null)));
    }
    @Test void v3VoteResultAndRequests(){
        var vote=new Response3(new Response(StatsProtocol.VOTED,4,ID,"인기도를 올렸어요",null),null,new Vote(129.0,true,0));
        var back=StatsProtocol.decodeResponse3(StatsProtocol.encodeResponse3(vote));
        assertEquals(StatsProtocol.VOTED,back.base().kind());assertEquals(129.0,back.vote().popularity());assertTrue(back.vote().voted());assertEquals("인기도를 올렸어요",back.base().message());
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeResponse(new Response(StatsProtocol.VOTED,4,ID,"",null)));
        for(int action=1;action<=6;action++){var q=new Request(action,3,ID);assertEquals(q,StatsProtocol.decodeRequest3(StatsProtocol.encodeRequest3(q)));}
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.encodeRequest3(new Request(7,3,ID)));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeRequest(StatsProtocol.encodeRequest3(new Request(1,3,ID))));
        assertThrows(IllegalArgumentException.class,()->StatsProtocol.decodeRequest3(StatsProtocol.encodeRequest(new Request(1,3,ID))));
    }
    @Test void v3FitsNineLargeItems(){
        byte[] big=new byte[3072];var p=new Profile(ID,"Mage",5,"루미나",24.0,1.0,40,12,34.0,180.0,7.5,62,200,"s".repeat(4096),"g".repeat(2048),Collections.nCopies(6,big));
        var e=new Extra("t".repeat(96),1,1,"q".repeat(64),0,0,List.of(big,big,big),"a".repeat(64),"b".repeat(64),false,0);
        assertNotNull(StatsProtocol.decodeResponse3(StatsProtocol.encodeResponse3(new Response3(new Response(StatsProtocol.OPEN,1,ID,"",p),e,null))));
    }
    @Test void passiveSpellsStillCountAsDiscoveries()throws Exception{
        var file=java.nio.file.Files.createTempFile("stats-catalog",".yml");
        try{java.nio.file.Files.writeString(file,"spells:\n  passive:\n    enabled: false\n    permission: magic.learned.passive\n");
            var spells=ManaSpells.load(file.toFile());assertTrue(spells.byId.isEmpty());assertEquals(List.of("magic.learned.passive"),spells.discoveryPermissions);
        }finally{java.nio.file.Files.deleteIfExists(file);}
    }
}
