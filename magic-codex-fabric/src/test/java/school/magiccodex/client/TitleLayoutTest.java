package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TitleLayoutTest {
    @Test void scaleAndHitTargetsAgreeAtDifferentGuiRatios(){for(int[] size:new int[][]{{1920,1080},{960,540},{640,360},{480,270},{1280,720}}){var f=TitleLayout.fit(size[0],size[1]);for(int side=0;side<2;side++)for(int row=0;row<5;row++){double x=f.x()+(side==0?100:620)*f.scale(),y=f.y()+(442+row*60)*f.scale();assertEquals(row,TitleLayout.row(f.localX(x),f.localY(y),side));}assertTrue(f.x()>=0&&f.y()>=0);assertTrue(880*f.scale()<=size[1]);}}
    @Test void approvedCloseShiftTracksLayoutScaleWithoutOverlappingNicknameAction(){
        assertEquals(985,TitleLayout.CLOSE_X);assertEquals(51,TitleLayout.CLOSE_Y);
        assertTrue(TitleLayout.CLOSE_X>820+156);
        for(int[] size:new int[][]{{1920,1080},{960,540},{640,360},{480,270},{1280,720}}){
            var f=TitleLayout.fit(size[0],size[1]);
            double x=f.x()+(TitleLayout.CLOSE_X+21)*f.scale(),y=f.y()+(TitleLayout.CLOSE_Y+21)*f.scale();
            assertTrue(TitleLayout.close(f.localX(x),f.localY(y)));
            assertFalse(TitleLayout.close(1020,45));
            assertFalse(TitleLayout.close(TitleLayout.CLOSE_X-1,TitleLayout.CLOSE_Y+21));
        }
    }
    @Test void gapsAndOutsideAreNotClickable(){assertEquals(-1,TitleLayout.row(100,470,0));assertEquals(-1,TitleLayout.row(100,710,0));assertEquals(-1,TitleLayout.row(520,442,0));assertEquals(-1,TitleLayout.row(600,415,1));}
    @Test void longNamesRemainLegibleWithoutBrokenSurrogatePairs(){assertEquals("짧은 칭호",TitleLayout.elide("짧은 칭호",20,String::length));assertEquals("별빛을 발…",TitleLayout.elide("별빛을 발견한 위대한 탐험가",6,String::length));String s=TitleLayout.elide("🌟🌟🌟🌟",5,String::length);assertEquals("🌟🌟…",s);assertFalse(Character.isHighSurrogate(s.charAt(s.length()-2)));}
}
