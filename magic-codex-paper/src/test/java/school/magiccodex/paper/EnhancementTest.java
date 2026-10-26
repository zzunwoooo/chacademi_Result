package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.EnhancementProtocol;
import java.util.*;
class EnhancementTest {
 @Test void bonusRequiresEveryHit(){for(int n=1;n<=10;n++){int all=(1<<n)-1;assertEquals(.6,EnhancementRules.chance(.4,.2,n,all,0),1e-9);assertEquals(.4,EnhancementRules.chance(.4,.2,n,all,1));assertEquals(.4,EnhancementRules.chance(.4,.2,n,all-1,0));}assertEquals(1,EnhancementRules.chance(.9,.2,1,1,0));}
 @Test void timingEdgesAndLatency(){assertTrue(EnhancementRules.hit(1130,1000,80,50));assertFalse(EnhancementRules.hit(1131,1000,80,50));assertTrue(EnhancementRules.hit(970,1000,80,50));assertFalse(EnhancementRules.hit(969,1000,80,50));assertFalse(EnhancementRules.hit(0,1000,80,0));assertTrue(EnhancementRules.hit(1200,1000,80,1000));}
 @Test void ringMatchesJudgement(){assertEquals(15f,school.magiccodex.protocol.EnhancementTiming.radius(1000,0,1000));assertTrue(school.magiccodex.protocol.EnhancementTiming.radius(900,0,1000)>15);assertTrue(school.magiccodex.protocol.EnhancementTiming.radius(1100,0,1000)<15);for(int t=800;t<=1200;t++)assertEquals(EnhancementRules.hit(t+50,1000,80,50),school.magiccodex.protocol.EnhancementTiming.inWindow(t,1000,80));}
 @Test void rollBoundary(){assertFalse(EnhancementRules.success(.4,.4));assertTrue(EnhancementRules.success(.399,.4));assertFalse(EnhancementRules.success(0,0));}
 @Test void packetsRejectCorruption(){var r=new EnhancementProtocol.Response(1,0,3,0,10,0,0,100,800,"마법봉","",2000,5000,.4,.2,List.of(24d,0d,0d),List.of(1d,2d,.5),List.of(1000,2000,3000));byte[] b=EnhancementProtocol.response(r);assertEquals(r,EnhancementProtocol.response(b));assertThrows(IllegalArgumentException.class,()->EnhancementProtocol.response(Arrays.copyOf(b,b.length+1)));assertThrows(IllegalArgumentException.class,()->EnhancementProtocol.response(new byte[5000]));var q=new EnhancementProtocol.Request(12,1,9);assertEquals(q,EnhancementProtocol.request(EnhancementProtocol.request(q)));assertThrows(IllegalArgumentException.class,()->new EnhancementProtocol.Request(0,1,0));assertThrows(IllegalArgumentException.class,()->new EnhancementProtocol.Request(1,1,10));}
}
