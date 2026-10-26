package school.magiccodex.paper;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TemperatureProtocol;
import school.magiccodex.protocol.TemperatureProtocol.Snapshot;
import static org.junit.jupiter.api.Assertions.*;

class TemperatureTest {
    @Test void fixedPacketsRejectMalformedAndNonFiniteInputs(){
        for(float value:new float[]{-100,-15,0,18.5f,45,100}){
            var s=new Snapshot(true,value);assertEquals(s,TemperatureProtocol.decode(TemperatureProtocol.encode(s)));
        }
        assertEquals(Snapshot.unavailable(),TemperatureProtocol.decode(TemperatureProtocol.encode(Snapshot.unavailable())));
        assertTrue(TemperatureProtocol.validRequest(TemperatureProtocol.request()));
        assertFalse(TemperatureProtocol.validRequest(new byte[4]));
        assertFalse(TemperatureProtocol.validRequest(TemperatureProtocol.encode(new Snapshot(true,45))));
        for(int size:new int[]{0,8,10,1024})assertThrows(IllegalArgumentException.class,()->TemperatureProtocol.decode(new byte[size]));
        byte[] p=TemperatureProtocol.encode(new Snapshot(true,18));p[4]=2;
        assertThrows(IllegalArgumentException.class,()->TemperatureProtocol.decode(p));
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-101,101}){
            assertThrows(IllegalArgumentException.class,()->new Snapshot(true,bad));
            byte[] packet=TemperatureProtocol.encode(new Snapshot(true,18));ByteBuffer.wrap(packet).putFloat(5,bad);
            assertThrows(IllegalArgumentException.class,()->TemperatureProtocol.decode(packet));
        }
    }
    @Test void unchangedValuesAndSubscribeSpamDoNotSendExtraMessages(){
        var f=new TemperatureFeed(18);var id=UUID.randomUUID();
        assertTrue(f.subscribe(id,0));assertEquals(new Snapshot(true,18),f.poll(id,0));
        for(int i=1;i<10000;i++){assertFalse(f.subscribe(id,i));assertNull(f.poll(id,i));}
        assertEquals(new Snapshot(true,18),f.poll(id,15000));assertNull(f.poll(id,15001));
    }
    @Test void latestValueIsCoalescedAndTinyPrecisionChangesAreIgnored(){
        var f=new TemperatureFeed(18);var id=UUID.randomUUID();f.subscribe(id,0);f.poll(id,0);
        for(int i=0;i<100;i++)f.set(id,30+i/10f);
        assertEquals(new Snapshot(true,39.9f),f.poll(id,500));
        f.set(id,39.901f);assertNull(f.poll(id,1000));
        assertThrows(IllegalArgumentException.class,()->f.set(id,Float.NaN));assertEquals(39.9f,f.get(id));
    }
    @Test void defaultResetExpiryAndQuitAreIndependent(){
        var f=new TemperatureFeed(18);var a=UUID.randomUUID();var b=UUID.randomUUID();
        f.set(a,-10);f.subscribe(a,0);f.subscribe(b,0);f.poll(a,0);f.poll(b,0);
        f.fallback(24);assertNull(f.poll(a,500));assertEquals(24,f.poll(b,500).celsius());
        f.reset(a);assertEquals(24,f.poll(a,1000).celsius());
        assertNull(f.poll(a,60000));assertFalse(f.subscribers().contains(a));
        f.set(a,45);f.remove(a);assertEquals(24,f.get(a));
        f.clear();assertTrue(f.subscribers().isEmpty());
    }
}
