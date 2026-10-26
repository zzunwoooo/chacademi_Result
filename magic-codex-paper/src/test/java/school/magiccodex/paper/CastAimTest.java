package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.CastAimProtocol;
/** C04: the client's aim point travels intact and the server only accepts it inside range / in front / in sight. */
class CastAimTest {
 @Test void protocolRoundTripAndRejectsGarbage(){
  var a=new CastAimProtocol.Aim("falling_star",10.5,64,-3.25,UUID.randomUUID(),false);
  assertEquals(a,CastAimProtocol.decode(CastAimProtocol.encode(a)));
  var b=new CastAimProtocol.Aim("ember_bolt",1,2,3,null,true);
  assertEquals(b,CastAimProtocol.decode(CastAimProtocol.encode(b)));
  byte[] wire=CastAimProtocol.encode(b);
  assertThrows(IllegalArgumentException.class,()->CastAimProtocol.decode(java.util.Arrays.copyOf(wire,wire.length-1)));
  assertThrows(IllegalArgumentException.class,()->CastAimProtocol.decode(java.util.Arrays.copyOf(wire,wire.length+1)));
  assertThrows(IllegalArgumentException.class,()->new CastAimProtocol.Aim("Bad Id",0,0,0,null,false));
  assertThrows(IllegalArgumentException.class,()->new CastAimProtocol.Aim("x",Double.NaN,0,0,null,false));
 }
 @Test void rangeIsHorizontalOnlyForGroundSpells(){
  var eye=new Vector(0,90,0);var ground=new Vector(10,64,0); // 26 below, 10 away
  assertFalse(AimCheck.inRange(eye,ground,15,AimCheck.SLACK_GROUND,false));
  assertTrue(AimCheck.inRange(eye,ground,15,AimCheck.SLACK_GROUND,true));
  assertFalse(AimCheck.inRange(eye,new Vector(30,64,0),15,AimCheck.SLACK_GROUND,true));
  assertFalse(AimCheck.inRange(eye,new Vector(5,20,0),15,AimCheck.SLACK_GROUND,true)); // far too deep
  assertTrue(AimCheck.inRange(eye,new Vector(16,90,0),15,AimCheck.SLACK_GROUND,false));
  assertFalse(AimCheck.inRange(eye,new Vector(18,90,0),15,AimCheck.SLACK_GROUND,false));
 }
 @Test void directionAllowsSmallAnglesAndFlightSlackOnly(){
  var eye=new Vector(0,64,0);var look=new Vector(1,0,0);
  assertTrue(AimCheck.inFront(eye,look,new Vector(20,64,2),AimCheck.SLACK_GROUND));   // ~6 degrees
  assertFalse(AimCheck.inFront(eye,look,new Vector(20,64,10),AimCheck.SLACK_GROUND)); // ~27 degrees, 10 beside
  assertTrue(AimCheck.inFront(eye,look,new Vector(20,64,5),AimCheck.SLACK_AIR));      // flying: 5 beside is fine
  assertFalse(AimCheck.inFront(eye,look,new Vector(-20,64,0),AimCheck.SLACK_AIR));    // behind
 }
 @Test void sightToleratesOnlyBlocksAtThePoint(){
  assertTrue(AimCheck.clear(20,null,1));assertTrue(AimCheck.clear(20,19.5,1));assertFalse(AimCheck.clear(20,12.0,1));
 }
 @Test void entityPointMustBeOnTheEntity(){
  var box=new BoundingBox(10,64,10,11,66,11);
  assertTrue(AimCheck.onEntity(box,new Vector(10.5,65,10.5),AimCheck.SLACK_GROUND));
  assertFalse(AimCheck.onEntity(box,new Vector(20,65,10.5),AimCheck.SLACK_GROUND));
 }
}
