package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.AppraisalProtocol;
class AppraisalRollTest {
 @Test void newFormationDistribution()throws Exception{try(var input=getClass().getResourceAsStream("/core-appraisal.yml")){var cfg=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));double reach=1,mean=0;for(int i=1;i<=10;i++){reach*=cfg.getDouble("formation."+i);mean+=reach;}assertEquals(3.805574523,mean,1e-8);assertEquals(.0004147605,reach,1e-10);assertTrue(cfg.getDouble("formation.5")>cfg.getDouble("formation.4"));assertTrue(cfg.getDouble("formation.8")>cfg.getDouble("formation.7"));}}
 @Test void affinityFollowsWeightsWithoutFirstItemBias(){
  double[] even={1,1,1};
  assertEquals(0,AppraisalRoll.affinity(even,()->0));assertEquals(1,AppraisalRoll.affinity(even,()->.5));assertEquals(2,AppraisalRoll.affinity(even,()->.9999999));
  assertEquals(2,AppraisalRoll.affinity(new double[]{0,0,1},()->0));assertEquals(1,AppraisalRoll.affinity(new double[]{0,3,0},()->.99));
  assertEquals(0,AppraisalRoll.affinity(new double[]{0,0,0},()->.7));
  int[] count=new int[3];int n=30000;for(int i=0;i<n;i++){double r=(i+.5)/n;count[AppraisalRoll.affinity(new double[]{2,1,1},()->r)]++;}
  assertEquals(n/2,count[0],2);assertEquals(n/4,count[1],2);assertEquals(n/4,count[2],2);
  assertThrows(IllegalArgumentException.class,()->AppraisalRoll.affinity(new double[]{1,-1,1},()->0));
  assertThrows(IllegalArgumentException.class,()->AppraisalRoll.affinity(new double[]{1,Double.NaN,1},()->0));
 }
 @Test void bundledConfigRollsEveryAffinity()throws Exception{try(var input=getClass().getResourceAsStream("/core-appraisal.yml")){var cfg=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));for(String k:new String[]{"power","mana","haste"})assertTrue(cfg.getDouble("affinity-weights."+k,1)>0,k);}}
 @Test void tenIsHardLimit(){double[] p=new double[10];java.util.Arrays.fill(p,1);var r=AppraisalRoll.roll(p,1,()->.5);assertEquals(10,r.nodes());assertFalse(r.broken());}
 @Test void ordinaryFailurePreservesPriorSuccess(){double[] p={1,1,1,0,1,1,1,1,1,1};var r=AppraisalRoll.roll(p,0,()->.5);assertEquals(3,r.nodes());assertFalse(r.broken());}
 @Test void destructionOnlyAfterFailedFormation(){double[] p={1,1,1,0,1,1,1,1,1,1};assertTrue(AppraisalRoll.roll(p,1,()->.5).broken());}
 @Test void invalidProbabilityRejected(){double[] p=new double[10];p[4]=Double.NaN;assertThrows(IllegalArgumentException.class,()->AppraisalRoll.roll(p,0,()->0));}
 @Test void packetRoundTripAndTrailingBytesRejected(){var r=new AppraisalProtocol.Response(14,2,7,true,false,2500,10000,1,"");byte[] b=AppraisalProtocol.encodeResponse(r);assertEquals(r,AppraisalProtocol.decodeResponse(b));assertThrows(IllegalArgumentException.class,()->AppraisalProtocol.decodeResponse(java.util.Arrays.copyOf(b,b.length+1)));assertThrows(IllegalArgumentException.class,()->AppraisalProtocol.decodeRequest(new byte[0]));}
 @Test void outcomeBoundsRejected(){assertThrows(IllegalArgumentException.class,()->AppraisalProtocol.encodeResponse(new AppraisalProtocol.Response(1,1,11,true,false,0,0,0,"")));}
 @Test void probabilitiesRoundTrip(){var ps=java.util.List.of(1d,.95,.88,.35,.85,.75,.22,.70,.55,.08);var r=new AppraisalProtocol.Response(14,1,3,true,false,1000,500,0,"",ps);assertEquals(r,AppraisalProtocol.decodeResponse(AppraisalProtocol.encodeResponse(r)));assertThrows(IllegalArgumentException.class,()->AppraisalProtocol.encodeResponse(new AppraisalProtocol.Response(1,0,0,false,false,0,0,0,"",java.util.List.of(.5))));}
}

