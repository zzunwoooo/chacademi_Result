package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.*;
import java.util.*;
class ReconfigurationTest {
    @Test void restorationOnlyRemovesFailures(){assertEquals(4,ReconfigurationRules.restore(5,2,1));assertEquals(2,ReconfigurationRules.restore(3,2,10));assertEquals(2,ReconfigurationRules.restore(2,2,1));assertThrows(IllegalArgumentException.class,()->ReconfigurationRules.restore(1,2,1));}
    @Test void everyAffinityPreservesRelativeBudgetAndActuallyChanges(){double[] original={1,.5,.5};for(int affinity=0;affinity<3;affinity++){double total=0;for(int stat=0;stat<3;stat++)total+=original[stat]*CoreAffinity.multiplier(stat,affinity);assertEquals(2,total);for(int choice=0;choice<2;choice++)assertNotEquals(affinity,CoreAffinity.changed(affinity,choice));}assertEquals(1,CoreAffinity.multiplier(0,0));assertEquals(2,CoreAffinity.multiplier(2,2));}
    @Test void packetBoundsAndRoundtrip(){var r=new ReconfigurationProtocol.Response(1,1,0,0,"마법봉","",1,0,0,-1,5,2,10,4,List.of(24d,5d,1d),List.of(24d,5d,1d),true);byte[] bytes=ReconfigurationProtocol.response(r);assertEquals(r,ReconfigurationProtocol.response(bytes));assertThrows(IllegalArgumentException.class,()->ReconfigurationProtocol.response(Arrays.copyOf(bytes,bytes.length+1)));assertThrows(IllegalArgumentException.class,()->ReconfigurationProtocol.response(new byte[5000]));for(int n=0;n<bytes.length;n++){final int length=n;assertThrows(IllegalArgumentException.class,()->ReconfigurationProtocol.response(Arrays.copyOf(bytes,length)));}assertThrows(IllegalArgumentException.class,()->new ReconfigurationProtocol.Request(0,0,0));assertThrows(IllegalArgumentException.class,()->new ReconfigurationProtocol.Request(1,0,3));}
    /** 장신구 재구성: 마법봉 응답은 예전 바이트 그대로(버전 1, 옛 클라 호환), 장신구만 버전 2 + kind 바이트. */
    @Test void accessoryKindRoundtripAndWandBytesUnchanged(){
        var wand=new ReconfigurationProtocol.Response(1,0,0,0,"마법봉","",1,0,0,-1,5,2,10,0,List.of(24d,5d,1d),List.of(20d,0d,0d),true);
        assertEquals(ReconfigurationProtocol.WAND,wand.kind());byte[] w=ReconfigurationProtocol.response(wand);assertEquals(1,w[0]);
        var acc=new ReconfigurationProtocol.Response(2,1,0,0,"달빛 반지","",3,0,0,-1,5,2,5,4,List.of(9d,100d,2.6d),List.of(9d,100d,2.6d),true,ReconfigurationProtocol.ACCESSORY);
        byte[] a=ReconfigurationProtocol.response(acc);assertEquals(2,a[0]);assertEquals(w.length+1+("달빛 반지".getBytes(java.nio.charset.StandardCharsets.UTF_8).length-"마법봉".getBytes(java.nio.charset.StandardCharsets.UTF_8).length),a.length);
        assertEquals(acc,ReconfigurationProtocol.response(a));assertEquals(ReconfigurationProtocol.ACCESSORY,ReconfigurationProtocol.response(a).kind());
        for(int n=0;n<a.length;n++){final int length=n;assertThrows(IllegalArgumentException.class,()->ReconfigurationProtocol.response(Arrays.copyOf(a,length)));}
        byte[] bad=a.clone();bad[0]=3;assertThrows(IllegalArgumentException.class,()->ReconfigurationProtocol.response(bad));
        assertThrows(IllegalArgumentException.class,()->new ReconfigurationProtocol.Response(3,0,0,0,"x","",0,0,0,-1,0,0,5,0,List.of(0d,0d,0d),List.of(0d,0d,0d),false,2));
    }
}
