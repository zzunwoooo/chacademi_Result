package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BroomKeyReservationTest {
 @Test void remappedSneakPressRepeatAndReleaseNeverFallThroughToMagic(){
  var r=new BroomKeyReservation();assertTrue(r.input(true,true,1));
  assertTrue(r.input(true,false,2));assertTrue(r.input(true,false,0));
  assertFalse(r.held());assertFalse(r.input(true,false,1));
 }
 @Test void unrelatedKeysAndNonRidersKeepNormalCasting(){
  var r=new BroomKeyReservation();assertFalse(r.input(false,true,1));assertFalse(r.input(true,false,1));
 }
 @Test void focusLossClearsReservation(){var r=new BroomKeyReservation();r.input(true,true,1);r.clear();assertFalse(r.input(true,false,1));}
}
