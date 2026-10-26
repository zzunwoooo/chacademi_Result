package school.magiccodex.discovery;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PotionRecoveryTest {
 UUID actor=UUID.randomUUID(),one=UUID.randomUUID(),two=UUID.randomUUID();
 PotionRecovery context(){return new PotionRecovery(actor,Map.of(actor,new PotionRecovery.Sample(10,20,4),one,new PotionRecovery.Sample(19,20,4),two,new PotionRecovery.Sample(10,20,4)));}
 @Test void actualHealingIncludesClampedHealthAndRequiresTwoPartyMembers(){var c=context();for(var id:c.samples.keySet())c.regain(id,true,false,4);var healed=c.actual(Map.of(actor,14d,one,20d,two,14d));assertTrue(PotionRecovery.qualifies(actor,healed,Set.of(actor,one,two)));assertFalse(PotionRecovery.qualifies(actor,healed,Set.of(actor,one)));assertFalse(PotionRecovery.qualifies(actor,Set.of(one,two),Set.of(actor,one,two)));}
 @Test void cancelledZeroUnrelatedOverlappingOrDamagedTargetsFailClosed(){var c=context();c.regain(actor,true,true,4);c.regain(one,false,false,4);c.regain(two,true,false,0);assertTrue(c.actual(Map.of(actor,14d,one,20d,two,14d)).isEmpty());c=context();c.regain(actor,true,false,4);c.regain(actor,true,false,4);c.regain(one,true,false,4);c.invalidate(one);c.regain(two,true,false,4);assertEquals(Set.of(two),c.actual(Map.of(actor,18d,one,20d,two,14d)));}
 @Test void fullHealthAndNoActualIncreaseDoNotCount(){var c=new PotionRecovery(actor,Map.of(actor,new PotionRecovery.Sample(20,20,4),one,new PotionRecovery.Sample(10,20,4)));c.regain(actor,true,false,4);c.regain(one,true,false,4);assertTrue(c.actual(Map.of(actor,20d,one,10d)).isEmpty());}
}
