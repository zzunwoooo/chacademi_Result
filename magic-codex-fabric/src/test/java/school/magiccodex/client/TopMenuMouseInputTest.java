package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TopMenuMouseInputTest {
    @Test void menuConsumesPressAndReleaseWithoutClickingTwice(){
        var route=new TopMenuMouseInput();var calls=new AtomicInteger();
        assertTrue(route.handle(0,1,()->{calls.incrementAndGet();return true;}));
        assertTrue(route.handle(0,0,()->{fail("release must not invoke menu");return false;}));
        assertEquals(1,calls.get());
        assertFalse(route.handle(0,0,()->true));
    }
    @Test void clicksOutsideMenuKeepChatAndGameplayWorking(){
        var route=new TopMenuMouseInput();
        assertFalse(route.handle(0,1,()->false));
        assertFalse(route.handle(0,0,()->true));
    }
    @Test void rightAndThumbButtonsNeverInvokeMenu(){
        var route=new TopMenuMouseInput();
        for(int button:new int[]{1,2,3,4})assertFalse(route.handle(button,1,()->{fail();return true;}));
    }
    @Test void unrelatedReleaseDoesNotLoseCapturedLeftGesture(){
        var route=new TopMenuMouseInput();
        assertTrue(route.handle(0,1,()->true));
        assertFalse(route.handle(1,0,()->false));
        assertTrue(route.handle(0,0,()->false));
    }
}
