package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AppraisalTimelineTest {
 @Test void tenNeverStartsEleventhFlight(){assertTrue(AppraisalTimeline.flying(11.49,10));assertFalse(AppraisalTimeline.flying(11.50,10));assertEquals(10,AppraisalTimeline.shown(11.6,10));assertEquals(11.95,AppraisalTimeline.end(10),.00001);}
 @Test void failureHasExactlyOneFailedAttempt(){assertTrue(AppraisalTimeline.flying(4.59,3));assertFalse(AppraisalTimeline.flying(4.60,3));assertEquals(3,AppraisalTimeline.shown(4.8,3));}
 @Test void breakFadesInsteadOfSwitching(){assertEquals(0,AppraisalTimeline.brokenBlend(4.6,3),.0001);float half=AppraisalTimeline.brokenBlend(4.875,3);assertEquals(.5,half,.0001);assertEquals(1,AppraisalTimeline.brokenBlend(5.2,3),.0001);}
}
