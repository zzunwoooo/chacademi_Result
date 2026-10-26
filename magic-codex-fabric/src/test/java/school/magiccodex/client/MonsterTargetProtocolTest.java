package school.magiccodex.client;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import school.magiccodex.protocol.*;
class MonsterTargetProtocolTest {
 @Test void authoritativeHealthRarityAndEligibilityRoundTrip(){var s=new TamingProtocol.State(0,1,2,"골렘",false,.35,0,0,0,"");var a=new TamingProtocol.Anchor(UUID.randomUUID(),1,2,3,4);var t=new MonsterTargetProtocol.Target(new TamingProtocol.AnchoredState(s,a),50,100,true,false,true);assertEquals(t,MonsterTargetProtocol.decode(MonsterTargetProtocol.encode(t)));}
 @Test void nonfiniteHealthAndTruncatedPacketsRejected(){var s=new TamingProtocol.State(0,1,2,"골렘",false,.35,0,0,0,"");var a=new TamingProtocol.Anchor(UUID.randomUUID(),1,2,3,4);assertThrows(IllegalArgumentException.class,()->new MonsterTargetProtocol.Target(new TamingProtocol.AnchoredState(s,a),Double.NaN,100,false,true,true));assertThrows(IllegalArgumentException.class,()->MonsterTargetProtocol.decode(new byte[]{1,0,9}));}
}
