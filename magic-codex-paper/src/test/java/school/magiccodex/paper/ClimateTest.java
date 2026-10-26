package school.magiccodex.paper;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.SeasonProtocol;

class ClimateTest {
    @Test void sevenMinecraftDaysAndFullYear(){
        var clock=new SeasonClock(1000,0,true,7*24000,1000);
        clock.observe(1000+7*24000-1);assertEquals(0,clock.season());assertEquals(7,clock.day());
        clock.observe(1000+7*24000);assertEquals(1,clock.season());assertEquals(1,clock.day());
        clock.observe(1000+28*24000);assertEquals(0,clock.season());
    }
    @Test void backwardsTimeDoesNotRewindSeason(){
        var c=new SeasonClock(0,0,true,168000,0);c.observe(200000);assertEquals(1,c.season());c.observe(1000);assertEquals(1,c.season());assertEquals(2,c.day());
        c.set(2,1000,false);c.observe(9999999);assertEquals(2,c.season());
    }
    @Test void sharedSeasonUsesWallClockAndAgreesAcrossServers(){
        long day=24000*SeasonClock.MILLIS_PER_TICK;assertEquals(20*60*1000L,day); // 마인크래프트 하루 = 실제 20분
        long anchor=1_800_000_000_000L;var shared=new SeasonClock.Shared(3,anchor,true,7*24000);
        assertEquals(shared,SeasonClock.Shared.decode(shared.encode()));
        // 두 서버가 같은 상태를 같은 시각에 읽으면 월드 시간과 무관하게 같은 계절·날짜가 나온다.
        var school=shared.clock(anchor+7*day-50);var wild=SeasonClock.Shared.decode(shared.encode()).clock(anchor+7*day-50);
        assertEquals(3,school.season());assertEquals(7,school.day());assertEquals(school.season(),wild.season());assertEquals(school.day(),wild.day());
        assertEquals(0,shared.clock(anchor+7*day).season());assertEquals(1,shared.clock(anchor+7*day).day());
        assertEquals(3,shared.clock(anchor-60_000).season()); // 시계가 조금 어긋난 서버: 기준 시각 이전은 시작 계절
        assertEquals(2,new SeasonClock.Shared(2,anchor,false,7*24000).clock(anchor+100*day).season());
        // 로컬 시계(틱) → 공유 상태 → 다시 시계: 계절과 진행도가 유지된다.
        var local=shared.clock(anchor+3*day);var round=SeasonClock.Shared.of(local);assertEquals(shared,round);
        for(String bad:new String[]{"","v1;4;0;1;168000","v1;0;-1;1;168000","v1;0;0;2;168000","v1;0;0;1;100","v2;0;0;1;168000","v1;x;0;1;168000"})assertThrows(IllegalArgumentException.class,()->SeasonClock.Shared.decode(bad));
        assertThrows(IllegalArgumentException.class,()->SeasonClock.Shared.decode(null));
    }
    @Test void extremesAndNamespacedBiomeOffsetFormula(){
        assertEquals(36,ClimateMath.temperature(22,36,6000,0,false,2));
        assertEquals(22,ClimateMath.temperature(22,36,18000,0,false,2));
        assertEquals(44,ClimateMath.temperature(22,36,6000,10,true,2));
        assertEquals(-100,ClimateMath.temperature(-90,-80,18000,-25,false,0));
    }
    @Test void computedTemperatureNeverOverwritesAdminOverride(){
        var f=new TemperatureFeed(18);UUID id=UUID.randomUUID();f.computed(id,30);assertEquals(30,f.get(id));
        f.set(id,-12);f.computed(id,35);assertEquals(-12,f.get(id));f.reset(id);assertEquals(35,f.get(id));f.remove(id);assertEquals(18,f.get(id));
    }
    @Test void seasonMultiplierDoesNotStackOrAlterEquipment(){
        var a=new ManaAccount(0,100,5);a.modifier("gear:wand",20,3);a.regenerationMultiplier("climate:regeneration",1.25);assertEquals(10,a.snapshot().regeneration());
        a.regenerationMultiplier("climate:regeneration",1.25);assertEquals(10,a.snapshot().regeneration());
        a.regenerationMultiplier("climate:regeneration",1);assertEquals(8,a.snapshot().regeneration());assertEquals(120,a.snapshot().maximum());
    }
    @Test void seasonWireRejectsInvalidPackets(){for(int i=0;i<4;i++)assertEquals(i,SeasonProtocol.decode(SeasonProtocol.encode(i)));assertThrows(IllegalArgumentException.class,()->SeasonProtocol.decode(new byte[100]));assertThrows(IllegalArgumentException.class,()->SeasonProtocol.encode(4));}
}
