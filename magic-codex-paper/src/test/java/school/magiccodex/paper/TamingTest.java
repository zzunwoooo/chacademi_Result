package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TamingProtocol;
import static org.junit.jupiter.api.Assertions.*;

class TamingTest {
    @Test void ordinaryHealthBonusIsBoundedAndBossDoesNotBecomeEasyAtOneHp(){
        assertEquals(.35,TamingRules.chance(.35,.35,20,20,false),1e-9);
        assertEquals(.525,TamingRules.chance(.35,.35,10,20,false),1e-9);
        assertEquals(.7,TamingRules.chance(.35,.35,0,20,false),1e-9);
        assertEquals(.008,TamingRules.chance(.008,.9,1,200,true),1e-9);
        assertEquals(1,TamingRules.chance(.8,.8,-100,20,false));
        assertEquals(0,TamingRules.chance(Double.NaN,.2,10,20,false));
    }
    @Test void exactProbabilityBoundaries(){assertFalse(TamingRules.succeeds(0,0));assertTrue(TamingRules.succeeds(1,.999999));assertTrue(TamingRules.succeeds(.008,.00799));assertFalse(TamingRules.succeeds(.008,.008));}
    @Test void requestIsBoundedAndCannotSupplyRewardsOrOdds(){
        var r=new TamingProtocol.Request(TamingProtocol.CAST,UUID.randomUUID());assertEquals(r,TamingProtocol.request(TamingProtocol.encode(r)));
        byte[] valid=TamingProtocol.encode(r);assertThrows(IllegalArgumentException.class,()->TamingProtocol.request(Arrays.copyOf(valid,valid.length+1)));
        valid[1]=42;assertThrows(IllegalArgumentException.class,()->TamingProtocol.request(valid));
        assertThrows(IllegalArgumentException.class,()->TamingProtocol.request(new byte[1601]));
    }
    @Test void responseValidatesNumbersLengthsAndRoundtrips(){
        var s=new TamingProtocol.State(3,2,14,"고대 유적의 수호자",true,.008,23000,2000,4000,"교화 중…");assertEquals(s,TamingProtocol.state(TamingProtocol.encode(s)));
        var bad=new TamingProtocol.State(3,2,14,"보스",true,Double.NaN,0,0,4000,"");assertThrows(IllegalArgumentException.class,()->TamingProtocol.state(TamingProtocol.encode(bad)));
        assertThrows(IllegalArgumentException.class,()->TamingProtocol.state(new byte[1]));
    }
}
