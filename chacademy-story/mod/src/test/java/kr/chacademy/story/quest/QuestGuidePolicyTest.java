package kr.chacademy.story.quest;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class QuestGuidePolicyTest {
 @Test void onlyCurrentActiveSessionStarts(){assertTrue(QuestGuidePolicy.accepts(true,7,7));assertFalse(QuestGuidePolicy.accepts(false,7,7));assertFalse(QuestGuidePolicy.accepts(true,7,6));assertFalse(QuestGuidePolicy.accepts(true,0,0));}
 @Test void npcAndPortalTrackWithoutReadyRequirement(){assertTrue(QuestGuidePolicy.tracks(true,7,7,QuestPackets.TARGET_NPC,true));assertTrue(QuestGuidePolicy.tracks(true,7,7,QuestPackets.TARGET_LOCATION,true));assertFalse(QuestGuidePolicy.tracks(true,7,7,QuestPackets.TARGET_NONE,true));assertFalse(QuestGuidePolicy.tracks(true,7,7,2,false));assertFalse(QuestGuidePolicy.tracks(true,8,7,2,true));}
 @Test void arrivalIsHorizontalAndInclusiveAtTen(){assertTrue(QuestGuidePolicy.reached(10,0));assertTrue(QuestGuidePolicy.reached(6,8));assertTrue(QuestGuidePolicy.reached(0,0));assertFalse(QuestGuidePolicy.reached(10.001,0));assertFalse(QuestGuidePolicy.reached(Double.NaN,0));assertFalse(QuestGuidePolicy.finite(0,Double.POSITIVE_INFINITY,0));}
}
