package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TemperatureProtocol.Snapshot;
import static org.junit.jupiter.api.Assertions.*;

class TemperatureTest {
    @Test void comfortableAndUnknownTemperaturesProduceNoTint(){
        for(float c:new float[]{5,18,30,Float.NaN}){assertEquals(0,TemperatureVisuals.heat(c));assertEquals(0,TemperatureVisuals.cold(c));}
        assertEquals(1,TemperatureVisuals.heat(45));assertEquals(1,TemperatureVisuals.heat(100));
        assertEquals(1,TemperatureVisuals.cold(-15));assertEquals(1,TemperatureVisuals.cold(-100));
    }
    @Test void thresholdsRampMonotonicallyWithoutMixingColdAndHot(){
        float previous=0;
        for(float c=30;c<=45;c+=.1f){float v=TemperatureVisuals.heat(c);assertTrue(v>=previous);assertEquals(0,TemperatureVisuals.cold(c));previous=v;}
        previous=0;
        for(float c=5;c>=-15;c-=.1f){float v=TemperatureVisuals.cold(c);assertTrue(v>=previous);assertEquals(0,TemperatureVisuals.heat(c));previous=v;}
    }
    @Test void smoothingIsFrameRateIndependentAndNeverOvershoots(){
        float a=0,b=0;for(int i=0;i<60;i++)a=TemperatureVisuals.approach(a,1,1/60f);
        for(int i=0;i<20;i++)b=TemperatureVisuals.approach(b,1,1/20f);
        assertEquals(a,b,.00001f);assertTrue(a>0&&a<1);
        assertTrue(TemperatureVisuals.approach(a,0,10)>=0);
    }
    @Test void centerStaysUnderTwoPercentWithStrongerOuterEdges(){
        for(int t=0;t<100;t++){
            assertTrue(TemperatureVisuals.opacity(0,0,1,t)<=.02001f);
            assertTrue(TemperatureVisuals.opacity(1,-1,1,t)<=.40001f);
            assertTrue(TemperatureVisuals.opacity(1,-1,1,t)>=.36799f);
            assertTrue(TemperatureVisuals.opacity(.5f,0,1,t)<.044f);
            assertTrue(TemperatureVisuals.opacity(1,-1,1,t)>TemperatureVisuals.opacity(0,0,1,t));
        }
        assertEquals(0,TemperatureVisuals.opacity(1,1,0,0));
    }
    @Test void expiredDisconnectedAndUnavailableDataNeverLooksLikeFreezing(){
        var state=new TemperatureState();assertTrue(Float.isNaN(state.current(0)));
        state.accept(new Snapshot(true,0),0);assertEquals(0,state.current(65000));
        assertTrue(Float.isNaN(state.current(65001)));
        state.accept(Snapshot.unavailable(),65002);assertTrue(Float.isNaN(state.current(65003)));
        state.accept(new Snapshot(true,-20),65004);state.reset();assertTrue(Float.isNaN(state.current(65005)));
    }
    @Test void previewDoesNotOverwriteIncomingServerTemperature(){
        var state=new TemperatureState();state.accept(new Snapshot(true,18),0);state.preview(45);
        state.accept(new Snapshot(true,-10),1000);assertEquals(45,state.current(1000));
        state.live();assertEquals(-10,state.current(1001));
        state.preview(-15);state.clearServer();assertEquals(-15,state.current(1002));
        state.reset();assertFalse(state.previewing());
    }
    @Test void labelsHandleZeroNegativeAndOneDecimalWithoutFalsePrecision(){
        assertEquals("—°C",TemperatureState.format(Float.NaN));assertEquals("0°C",TemperatureState.format(-.01f));
        assertEquals("-12.3°C",TemperatureState.format(-12.34f));assertEquals("18°C",TemperatureState.format(18));
        assertEquals("100°C",TemperatureState.format(100));
    }
}
