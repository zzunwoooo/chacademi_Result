package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FishMoodTest {
    @Test void pullResistsThenRestsThenNormal(){
        assertEquals(FishMood.RESIST,FishMood.of(false,2.0f,true,1.65f,.9f,2.5f,5f));
        assertEquals(FishMood.TIRED,FishMood.of(false,2.6f,false,.9f,.9f,2.5f,5f));
        assertEquals(FishMood.NORMAL,FishMood.of(false,2.5f+FishMood.TIRED_WINDOW+.01f,false,.9f,.9f,2.5f,5f));
        assertEquals(FishMood.NORMAL,FishMood.of(false,1.0f,false,1f,.9f,0,2f));
    }
    @Test void lowStaminaStaysTiredButAPullStillResists(){
        assertEquals(FishMood.TIRED,FishMood.of(false,9f,false,1.2f,.2f,3f,12f));
        assertEquals(FishMood.RESIST,FishMood.of(false,9f,true,1.5f,.2f,9.5f,12f));
    }
    @Test void surgeRestsBeforeItsNextPullAndThrashPeaksResist(){
        assertEquals(FishMood.TIRED,FishMood.of(true,10f,false,.35f,.9f,3f,11f));
        assertEquals(FishMood.RESIST,FishMood.of(false,10f,false,1.3f,.9f,3f,14f));
    }
}
