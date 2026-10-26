package school.magiccodex.paper;
import java.util.List;
final class EnhancementRules {
 static double chance(double base,double bonus,int nodes,int hits,int misses){return misses==0&&hits==(1<<nodes)-1?Math.min(1,base+bonus):base;}
 static boolean hit(long elapsed,int target,int window,int latency){return school.magiccodex.protocol.EnhancementTiming.inWindow(elapsed-Math.clamp(latency,0,200),target,window);}
 static boolean success(double roll,double chance){return roll<chance;}
 /**
  * 전투 스펙 2026-10-26: 다음 성공(지금까지 성공 횟수 successes, 0부터 = 1단계)의 단계값.
  * 목록이 비면 fallback(코어에 저장된 core_stat_gain, 예전 방식). 목록이 짧으면 마지막 값을 계속 쓴다.
  */
 static double stageGain(List<Double> stages,int successes,double fallback){
  if(stages==null||stages.isEmpty())return fallback;
  return stages.get(Math.clamp(successes,0,stages.size()-1));
 }
 /** 섞기 (오너 2026-10-26): 코어 품질(노드 수) 배율 기본값 — 1노드 0.9, 2노드 0.95, 3노드 1.0 에서 10노드 1.15 까지 직선. */
 static double defaultQuality(int nodes){
  if(nodes<=1)return 0.9;if(nodes==2)return 0.95;
  return 1.0+0.15*(Math.min(nodes,10)-3)/7.0;
 }
 static double qualityMultiplier(double[] table,int nodes){
  if(table==null||nodes<1||nodes>10||table.length<11||table[nodes]<=0)return defaultQuality(nodes);
  return table[nodes];
 }
 /** 성공 1회 상승량 [0 지능, 1 마나, 2 가속(마법봉)/초당 마나 회복(장신구)] = 단계값 × gain × 코어 성향 배율 (CoreAffinity). */
 static double[] gains(double stage,double[] gain,int affinity){
  double[] out=new double[3];for(int i=0;i<3;i++)out[i]=stage*gain[i]*school.magiccodex.protocol.CoreAffinity.multiplier(i,affinity);return out;
 }
 /** successes 번 모두 성공했을 때의 누적 상승량 (문서·검산용). */
 static double[] total(List<Double> stages,int successes,double[] gain,int affinity){
  double[] sum=new double[3];for(int n=0;n<successes;n++){double[] g=gains(stageGain(stages,n,0),gain,affinity);for(int i=0;i<3;i++)sum[i]+=g[i];}return sum;
 }
}
