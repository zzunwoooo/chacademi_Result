package school.magiccodex.paper;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PetTargetRayTest {
 private static final Vector EYE=new Vector(0,1.6,0),FORWARD=new Vector(0,0,1);
 @Test void actualHitAlongRayIsAccepted(){assertEquals(8,TamingBridge.rayHitDistance(EYE,FORWARD,new Vector(0,1.6,8),16));}
 @Test void wallClippedRayCannotAcceptHitBehindWall(){assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,FORWARD,new Vector(0,1.6,8),4));}
 @Test void nearbyOffRayMobAndHitBehindPlayerAreRejected(){
  assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,FORWARD,new Vector(.4,1.6,2),16));
  assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,FORWARD,new Vector(0,1.6,-2),16));
 }
 @Test void staleMissingAndInvalidIntersectionsFailClosed(){
  assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,FORWARD,null,16));
  assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,FORWARD,new Vector(Double.NaN,1.6,2),16));
  assertEquals(Double.POSITIVE_INFINITY,TamingBridge.rayHitDistance(EYE,new Vector(),new Vector(0,1.6,2),16));
 }
 @Test void rayDistanceIsNotChangedByDirectionMagnitude(){assertEquals(3,TamingBridge.rayHitDistance(EYE,new Vector(0,0,2),new Vector(0,1.6,3),16));}
 @Test void soundIntervalsUseHalfSecondSchedulerAndKeepBounds(){
  assertEquals(12,ShinyBridge.soundIntervalTicks(6));
  assertEquals(24,ShinyBridge.soundIntervalTicks(12));
  assertEquals(10,ShinyBridge.soundIntervalTicks(0));
  assertEquals(600,ShinyBridge.soundIntervalTicks(Integer.MAX_VALUE));
 }
}