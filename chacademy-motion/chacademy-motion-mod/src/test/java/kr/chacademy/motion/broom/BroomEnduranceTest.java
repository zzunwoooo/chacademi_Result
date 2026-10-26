package kr.chacademy.motion.broom;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BroomEnduranceTest {
    private BroomPhysics physics(float mastery,float stability){return new BroomPhysics(new BroomPhysics.Stats(1,.04f,10,.03f,stability,.05f,mastery,120,60),0,0,0,1,123);}
    private BroomPhysics.Input input(int roll){return new BroomPhysics.Input(true,false,false,false,false,false,false,roll,0,0,false,false,70);}
    @Test void smoothFlightIsNotForcedOffAtOneMinute(){
        var p=physics(.5f,1); for(int i=0;i<2400;i++){p.step(input(0));assertTrue(p.balance<=p.maximumBalance());}
        assertFalse(p.fellOff());assertTrue(p.balance>0);assertEquals(p.balanceCap(),p.maximumBalance(),1e-6f); // 2026-10-20: no fatigue decay
    }
    @Test void recatchPreservesElapsedAndCooldownAndCannotRecoverBeyondCurrentCap(){
        var p=physics(.5f,1);for(int i=0;i<1500;i++)p.step(input(0));
        long ticks=p.ridingTicks();p.boostCooldown=80;p.rollCooldown=30;p.caught();
        assertEquals(ticks,p.ridingTicks());assertEquals(80,p.boostCooldown);assertEquals(30,p.rollCooldown);
        assertTrue(p.balance<=18);assertTrue(p.balance<=p.maximumBalance());
        for(int i=0;i<100;i++)p.step(input(0));assertTrue(p.balance<=p.maximumBalance());
    }
    @Test void masteryAndStabilityImproveFatigueTolerance(){
        var novice=physics(0,.85f);var veteran=physics(1,1.6f);
        for(int i=0;i<1200;i++){novice.step(input(0));veteran.step(input(0));}
        assertTrue(veteran.maximumBalance()>novice.maximumBalance()); // higher stability = higher cap
        novice.hurt();veteran.hurt();assertTrue(veteran.balance>novice.balance);
    }
    @Test void rollRemainsCollisionBounded(){
        var p=physics(.5f,1);p.step(input(1));assertTrue(p.horizontalSpeed()<=1.1+1e-6);
        p.afterMove(0,0,0,true,false);assertTrue(Math.abs(p.vx)<.3);assertTrue(Math.abs(p.vz)<.3);
    }
}
