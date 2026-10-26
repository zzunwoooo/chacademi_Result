package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IdleFadeTest {
    @Test void holdsThenFadesSmoothlyToZero(){
        var fade=new IdleFade(1000,9);
        assertEquals(1,fade.update(9000,9,false));
        assertEquals(0.5f,fade.update(9600,9,false),0.0001);
        assertEquals(0,fade.update(10200,9,false));
        assertEquals(0,fade.update(999999,9,false));
    }
    @Test void newMessageRestoresAHiddenWindowImmediately(){
        var fade=new IdleFade(0,10);
        assertEquals(0,fade.update(10000,10,false));
        assertEquals(1,fade.update(10001,11,false));
        assertEquals(1,fade.update(18001,11,false));
    }
    @Test void openChatOrAltPinsVisibilityAndRestartsTheHoldAfterClosing(){
        var fade=new IdleFade(0,0);
        assertEquals(1,fade.update(20000,0,true));
        assertEquals(1,fade.update(50000,0,true));
        assertEquals(1,fade.update(57999,0,false));
        assertEquals(0,fade.update(59200,0,false));
    }
    @Test void repeatedPollingWithoutMessagesDoesNotResetIdle(){
        var fade=new IdleFade(0,55);
        for(int time=0;time<=8000;time+=50)assertEquals(1,fade.update(time,55,false));
        float previous=1;
        for(int time=8000;time<=9200;time+=10){
            float current=fade.update(time,55,false);
            assertTrue(current<=previous);
            previous=current;
        }
        assertEquals(0,previous);
    }
    @Test void windowsHaveIndependentMessageTimers(){
        var first=new IdleFade(0,1);var second=new IdleFade(0,1);
        assertEquals(1,first.update(10000,2,false));
        assertEquals(0,second.update(10000,1,false));
    }
    @Test void clockResetAndOlderStampFromTabTransferAreSafe(){
        var fade=new IdleFade(10000,100);
        assertEquals(1,fade.update(9999,100,false));
        assertEquals(1,fade.update(20000,1,false));
        assertEquals(0,fade.update(29200,1,false));
    }
    @Test void alphaMultiplicationPreservesColorAndHonorsBounds(){
        assertEquals(0x00123456,IdleFade.color(0x80123456,0));
        assertEquals(0x40123456,IdleFade.color(0x80123456,0.5f));
        assertEquals(0x80123456,IdleFade.color(0x80123456,2));
        assertEquals(0x00123456,IdleFade.color(0x80123456,-1));
    }
}