package kr.chacademy.storyplugin;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class LocalStoryRewardsTest {
 @Test void currentContentHasOnlyFixedSelfEffects(){var a=LocalStoryRewards.resolve("ch1-2.e_s11");assertEquals("teacher_ellna",a.affinityNpc());assertEquals(-1,a.affinityDelta());assertNull(a.discoveryEvent());var d=LocalStoryRewards.resolve("ch1-3.end");assertEquals("jump.sprinting",d.discoveryEvent());assertEquals(30,d.discoveryAmount());assertNull(d.affinityNpc());}
 @Test void otherTargetsAmountsAndCommandsAreNeverAccepted(){for(String key:new String[]{"op Zzunwoo","ch1-3.end Zzunwoo","ch1-2.e_s11 20","ch1-2.other","","../end"})assertNull(LocalStoryRewards.resolve(key));}
}
