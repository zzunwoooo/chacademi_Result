package school.chacademia.magicmythic;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
/** A04: one tick of chabolt moves exactly the configured speed, in equal sub-steps of at most 0.4. */
class BoltStepTest {
 @Test void tickTravelEqualsConfiguredSpeed(){
  for(double d:new double[]{0.1,0.4,0.5,0.8,1.3,1.37,2.5,4.0,8.0}){
   int n=Geometry.steps(d);double s=d/n,sum=0;for(int i=0;i<n;i++)sum+=s;
   assertEquals(d,sum,1e-9,"distance "+d);assertTrue(s<=Geometry.MAX_STEP+1e-12,"step "+s+" for "+d);
  }
  assertEquals(1,Geometry.steps(0.4));assertEquals(2,Geometry.steps(0.5));assertEquals(10,Geometry.steps(4.0));
  assertEquals(1,Geometry.steps(0));assertEquals(1,Geometry.steps(Double.NaN));
 }
}
