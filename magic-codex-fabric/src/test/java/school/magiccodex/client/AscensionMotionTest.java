package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AscensionMotionTest {
    @Test void arrivalIsContinuousAndFinishesBeforeConfirmation(){
        assertEquals(0,AscensionMotion.reveal(0));assertEquals(1,AscensionMotion.reveal(AscensionMotion.ARRIVAL),.0001);
        float last=0;for(double t=0;t<8;t+=.01){float p=AscensionMotion.reveal(t);assertTrue(p>=last&&p<=1);last=p;}
        assertTrue(AscensionMotion.COMPLETE>AscensionMotion.ARRIVAL);
    }
    @Test void allNineOrbitsStayInThePortraitAreaAndCrossBothDepthPlanes(){
        for(int i=0;i<9;i++){boolean front=false,back=false;for(double t=0;t<80;t+=.1){
            assertTrue(AscensionMotion.x(i,t)>275&&AscensionMotion.x(i,t)<665);
            assertTrue(AscensionMotion.y(i,t)>250&&AscensionMotion.y(i,t)<460);
            front|=AscensionMotion.depth(i,t)>0;back|=AscensionMotion.depth(i,t)<0;
        }assertTrue(front&&back);}
    }
}
