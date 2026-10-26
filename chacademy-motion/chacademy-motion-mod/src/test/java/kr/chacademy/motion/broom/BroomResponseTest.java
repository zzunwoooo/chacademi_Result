package kr.chacademy.motion.broom;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class BroomResponseTest {
 BroomPhysics make(float turn,float brake,float stability){return new BroomPhysics(new BroomPhysics.Stats(1,.04f,turn,brake,stability,.05f,.5f,120,60),0,0,0,.6,9);}
 BroomPhysics.Input in(float yaw,float pitch,boolean back){return new BroomPhysics.Input(!back,back,false,false,false,false,false,0,yaw,pitch,false,false,70);}
 @Test void pitchChangesClimbAndDive(){var up=make(10,.04f,1);var down=make(10,.04f,1);for(int i=0;i<15;i++){up.step(in(0,-40,false));down.step(in(0,40,false));}assertTrue(up.vy>0);assertTrue(down.vy<0);}
 @Test void turnAndBrakeStatsChangeActualMotion(){var quick=make(15,.12f,1);var slow=make(5,.02f,1);quick.step(in(60,0,false));slow.step(in(60,0,false));assertTrue(quick.heading>slow.heading);quick=make(10,.12f,1);slow=make(10,.02f,1);for(int i=0;i<5;i++){quick.step(in(0,0,true));slow.step(in(0,0,true));}assertTrue(quick.horizontalSpeed()<slow.horizontalSpeed());}
 @Test void stabilityAffectsRecoveryAndRecatchStartsLow(){var high=make(10,.04f,2);var low=make(10,.04f,.5f);high.balance=low.balance=20;high.step(in(0,0,false));low.step(in(0,0,false));assertTrue(high.balance>low.balance);high.caught();assertEquals(18,high.balance);}
 @Test void pitchFollowsLookAtTurnRate(){var quick=make(15,.04f,1);var slow=make(5,.04f,1);quick.step(in(0,0,false));slow.step(in(0,0,false));quick.step(in(0,-50,false));slow.step(in(0,-50,false));assertTrue(quick.flightPitch<slow.flightPitch);assertTrue(slow.flightPitch>=-5.001f&&slow.flightPitch<0);for(int i=0;i<40;i++)slow.step(in(0,-50,false));assertEquals(-50,slow.flightPitch,0.01);}
 @Test void spaceClimbsAndStacksWithLookingUp(){var space=make(10,.04f,1);var none=make(10,.04f,1);var both=make(10,.04f,1);var look=make(10,.04f,1);
  for(int i=0;i<20;i++){space.step(new BroomPhysics.Input(false,false,false,false,true,false,false,0,0,0,false,false,70));none.step(new BroomPhysics.Input(false,false,false,false,false,false,false,0,0,0,false,false,70));
   look.step(new BroomPhysics.Input(true,false,false,false,false,false,false,0,0,-40,false,false,70));both.step(new BroomPhysics.Input(true,false,false,false,true,false,false,0,0,-40,false,false,70));}
  assertTrue(space.vy>none.vy);assertTrue(both.vy>look.vy);assertTrue(look.vy>0);}
 @Test void balanceCapFollowsStabilityLevel(){assertEquals(93,make(10,.04f,.6f).balance,1e-4);assertEquals(120,make(10,.04f,1.8f).balance,1e-4);assertEquals(102,BroomPhysics.balanceCap(1.0f),1e-4);}
 @Test void recatchUsesCurrentLookPitch(){var broom=make(5,.04f,1);broom.step(in(0,-40,false));broom.caught();broom.step(in(0,35,false));assertEquals(35,broom.flightPitch,0.001);}

}
