package school.magiccodex.paper;
import java.util.function.DoubleSupplier;
final class AppraisalRoll {
 record Result(int nodes,boolean broken){}
 static Result roll(double[] chances,double breakChance,DoubleSupplier random){
  if(chances.length!=10||!Double.isFinite(breakChance)||breakChance<0||breakChance>1)throw new IllegalArgumentException();
  for(double p:chances)if(!Double.isFinite(p)||p<0||p>1)throw new IllegalArgumentException();
  for(int i=0;i<10;i++)if(random.getAsDouble()>=chances[i])return new Result(i,random.getAsDouble()<breakChance);
  return new Result(10,false);
 }
 /** 감정 성공한 코어의 성향(0 지능 / 1 마나 / 2 마법 가속)을 가중치 비율로 뽑는다. 합이 0이면 0(예전 동작). */
 static int affinity(double[] weights,DoubleSupplier random){
  if(weights.length!=3)throw new IllegalArgumentException();
  double total=0;for(double w:weights){if(!Double.isFinite(w)||w<0)throw new IllegalArgumentException();total+=w;}
  if(total<=0)return 0;
  double r=random.getAsDouble()*total;
  for(int i=0;i<3;i++){if(weights[i]>0&&r<weights[i])return i;r-=weights[i];}
  for(int i=2;i>=0;i--)if(weights[i]>0)return i; // 부동소수 끝값 보정
  return 0;
 }
}
