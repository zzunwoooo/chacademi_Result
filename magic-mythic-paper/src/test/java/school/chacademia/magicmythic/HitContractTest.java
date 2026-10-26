package school.chacademia.magicmythic;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
/** HIT cancel contract: a consumed (gimmick input) hit still succeeds with no target; hitting something always succeeds. */
class HitContractTest {
 @Test void resultTable(){
  assertTrue(HitMech.result(true,true));assertFalse(HitMech.result(true,false));
  assertTrue(HitMech.result(false,true));assertTrue(HitMech.result(false,false));
 }
}
