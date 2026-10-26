package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SocialLayoutTest {
    @Test void layoutsRemainOnScreenAndHitCoordinatesSurviveResize(){for(int[] size:new int[][]{{320,180},{640,360},{960,540},{1280,720},{2560,1080}}){var f=SocialLayout.friends(size[0],size[1]);assertTrue(f.x()>=0&&f.y()>=0);assertTrue(f.x()+1000*f.scale()<=size[0]);assertTrue(f.y()+870*f.scale()<=size[1]);assertEquals(750,f.x(f.x()+750*f.scale()),.01);var w=SocialLayout.whisper(size[0],size[1]);assertTrue(w.y()>size[1]*.5);assertTrue(w.y()+450*w.scale()<size[1]);}}
    @Test void dormMappingAndScrollNeverRevealBeyondFifty(){assertEquals(1,SocialLayout.dorm("아르케온"));assertEquals(2,SocialLayout.dorm("루미나"));assertEquals(3,SocialLayout.dorm("베스티아즈"));assertEquals(4,SocialLayout.dorm("노크세르"));assertEquals(0,SocialLayout.dorm(""));assertEquals(0,SocialLayout.maxScroll(6));assertEquals(3960,SocialLayout.maxScroll(50));}
}
