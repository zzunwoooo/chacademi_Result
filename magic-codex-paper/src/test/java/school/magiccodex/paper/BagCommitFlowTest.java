package school.magiccodex.paper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BagCommitFlowTest {
 final BagSnapshotBinding b=new BagSnapshotBinding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,BagSnapshotBinding.hash("inv"));
 final class Port implements BagCommitFlow.Port {
  final List<String> events=new ArrayList<>();String fail;
  void step(String n){events.add(n);if(n.equals(fail))throw new IllegalStateException(n);}
  public boolean reserve(){step("reserve");return true;}
  public void writeDatabase(){step("dbWrite");}
  public BagCommitFlow.Proof readDatabase(){step("dbRead");return new BagCommitFlow.Proof(b.snapshot(),"inv",b);}
  public void writeRedis(){step("redisWrite");}
  public BagCommitFlow.Proof peekRedis(){step("redisPeek");return new BagCommitFlow.Proof(b.snapshot(),"inv",b);}
  public boolean acknowledgeCas(boolean r){step("cas"+(r?"Release":"Keep"));return true;}
  public void releaseCheckout(){step("checkout");}
  public void unlock(){step("unlock");}
  public void blocked(String stage,Exception e){events.add("blocked:"+stage);}
 }
 @Test void normalTransferProvesBothStoresAndCasBeforeCheckoutAndUnlock(){var p=new Port();assertTrue(BagCommitFlow.run(b,true,true,p));assertEquals(List.of("reserve","dbWrite","dbRead","redisWrite","redisPeek","casRelease","checkout","unlock"),p.events);}
 @Test void everyIntermediateFailureKeepsInputLockedAndStopsSubsequentStages(){for(String fail:List.of("reserve","dbWrite","dbRead","redisWrite","redisPeek","casRelease","checkout")){var p=new Port();p.fail=fail;assertFalse(BagCommitFlow.run(b,true,true,p),fail);assertFalse(p.events.contains("unlock"),fail);assertTrue(p.events.getLast().startsWith("blocked:"));}}
 @Test void autosaveDoesNotReleaseTheLeaseOrCheckout(){var p=new Port();assertTrue(BagCommitFlow.run(b,false,true,p));assertFalse(p.events.contains("checkout"));assertTrue(p.events.contains("casKeep"));}
 @Test void anExistingNativeLockIsNotReleasedByBackgroundSave(){var p=new Port();assertTrue(BagCommitFlow.run(b,false,false,p));assertFalse(p.events.contains("unlock"));}
 @Test void callbackSuccessWithWrongDbOrRedisSnapshotIsNotAProof(){assertThrows(IllegalStateException.class,()->BagCommitFlow.verify(b,new BagCommitFlow.Proof(UUID.randomUUID(),"inv",b)));assertThrows(IllegalStateException.class,()->BagCommitFlow.verify(b,new BagCommitFlow.Proof(b.snapshot(),"old",b)));}
 @Test void shutdownUsesTheSameProofPipelineWithoutNeedingASaveEvent(){var p=new Port();assertTrue(BagCommitFlow.run(b,true,true,p));assertTrue(p.events.indexOf("dbRead")<p.events.indexOf("checkout"));}
}
