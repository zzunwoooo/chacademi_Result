package school.magiccodex.paper;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TamingProtocol;
import static org.junit.jupiter.api.Assertions.*;
class TamingAnchorProtocolTest {
 @Test void serverTargetWithMissingClientBaseRoundtripsAtomically(){var s=new TamingProtocol.State(0,1,42,"CA_Wild_alpha_boar",false,.08,0,0,0,"");var v=new TamingProtocol.AnchoredState(s,new TamingProtocol.Anchor(UUID.randomUUID(),1,64,2,2));byte[] wire=TamingProtocol.encode(v);assertEquals(v,TamingProtocol.anchoredState(wire));assertEquals(s,TamingProtocol.state(TamingProtocol.encode(s)));assertThrows(IllegalArgumentException.class,()->TamingProtocol.anchoredState(Arrays.copyOf(wire,wire.length-1)));assertThrows(IllegalArgumentException.class,()->TamingProtocol.anchoredState(Arrays.copyOf(wire,wire.length+1)));assertThrows(IllegalArgumentException.class,()->TamingProtocol.anchoredState(new byte[1601]));}
 @Test void invalidPositionsAreRejected(){for(double x:new double[]{Double.NaN,Double.POSITIVE_INFINITY,32_000_001})assertThrows(IllegalArgumentException.class,()->new TamingProtocol.Anchor(UUID.randomUUID(),x,0,0,1));assertThrows(IllegalArgumentException.class,()->new TamingProtocol.Anchor(UUID.randomUUID(),0,0,0,-1));}
}
