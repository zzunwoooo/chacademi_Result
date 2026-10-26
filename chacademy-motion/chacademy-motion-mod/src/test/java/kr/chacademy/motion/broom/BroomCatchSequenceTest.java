package kr.chacademy.motion.broom;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class BroomCatchSequenceTest {
 @Test void fourCorrectLogicalPressesRequiredAndHeldKeysDoNotRepeat(){var s=new BroomCatchSequence(44,0);var keys=s.keys();for(int i=0;i<4;i++){s.input(0);assertEquals(i==3,s.input(1<<keys[i]));int p=s.progress();s.input(1<<keys[i]);assertEquals(p,s.progress());}assertTrue(s.complete());}
 @Test void wrongOrSimultaneousKeysResetProgress(){var s=new BroomCatchSequence(22,0);s.input(1<<s.keys()[0]);s.input(0);s.input(15);assertEquals(0,s.progress());s.input(0);s.input(1<<((s.keys()[0]+1)%4));assertEquals(0,s.progress());}
 @Test void alreadyHeldMovementDoesNotCountAndSequencesVary(){var a=new BroomCatchSequence(1,15);a.input(15);assertEquals(0,a.progress());assertFalse(java.util.Arrays.equals(a.keys(),new BroomCatchSequence(2,0).keys()));for(int i=1;i<4;i++)assertNotEquals(a.keys()[i-1],a.keys()[i]);}
}
