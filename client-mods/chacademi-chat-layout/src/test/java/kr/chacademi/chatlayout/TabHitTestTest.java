package kr.chacademi.chatlayout;
import java.util.List;
import kr.chacademi.chatlayout.LayoutMath.Rect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TabHitTestTest {
    @Test void openChatHitsTheDisplayedTabsBelowTheInputInsteadOfTheOldRow(){
        Rect frame=new Rect(10,10,200,140);
        var tabs=List.of(new Rect(13,131,30,13),new Rect(44,131,30,13));
        assertEquals(1,TabHitTest.find(tabs,0,frame,50,136));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,50,116));
    }
    @Test void selectedAndUnselectedWindowsUseTheirOwnDisplayedRow(){
        Rect frame=new Rect(10,10,200,140);
        var resting=List.of(new Rect(13,111,30,13));
        var typing=List.of(new Rect(13,131,30,13));
        assertEquals(0,TabHitTest.find(resting,0,frame,20,116));
        assertEquals(-1,TabHitTest.find(typing,0,frame,20,116));
        assertEquals(0,TabHitTest.find(typing,0,frame,20,136));
    }
    @Test void clippedLabelsCannotStealClicksOutsideTheirWindow(){
        Rect frame=new Rect(10,10,100,140);
        var tabs=List.of(new Rect(95,131,40,13));
        assertEquals(0,TabHitTest.find(tabs,0,frame,109,136));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,110,136));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,125,136));
    }
    @Test void offscreenTabsWithStaleCoordinatesCannotBeSelected(){
        Rect frame=new Rect(10,10,200,140);
        var tabs=List.of(new Rect(13,131,30,13),new Rect(13,131,30,13));
        assertEquals(1,TabHitTest.find(tabs,1,frame,20,136));
        assertEquals(-1,TabHitTest.find(tabs,2,frame,20,136));
    }
    @Test void gapsAndBottomEdgesDoNotSelectAdjacentTabs(){
        Rect frame=new Rect(10,10,200,140);
        var tabs=List.of(new Rect(13,131,30,13),new Rect(44,131,30,13));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,43,136));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,20,144));
        assertEquals(-1,TabHitTest.find(tabs,0,frame,20,130));
        assertEquals(1,TabHitTest.find(tabs,0,frame,44,131));
    }
}