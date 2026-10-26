package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HeaderLayoutTest {
    private LayoutMath.Panel panel(WindowState state,int width,int height,int natural){
        return HeaderLayout.apply(LayoutMath.window(width,height,state,18,natural),natural,width);
    }
    @Test void tabsAndControlsShareHeaderWithoutOverlapping(){
        var p=panel(new WindowState(),480,270,205);
        var tabs=HeaderLayout.tabs(p);
        assertEquals(p.header().bottom(),p.body().top());
        assertEquals(p.body().bottom(),p.frame().bottom());
        assertTrue(tabs.right()<p.control(0).x());
        for(int i=0;i<2;i++){
            var button=p.control(i);
            assertTrue(p.header().contains(button.x(),button.top()));
            assertTrue(p.header().contains(button.right()-1,button.bottom()-1));
            assertFalse(tabs.contains(button.x(),button.top()));
        }
    }
    @Test void openingInputDoesNotMoveHeaderOrTabHitArea(){
        var p=panel(new WindowState(),480,270,205);
        var typing=InputLayout.attach(p);
        assertEquals(p.header(),typing.header());
        assertEquals(HeaderLayout.tabs(p),HeaderLayout.tabs(typing));
        assertEquals(p.body(),typing.body());
        assertTrue(InputLayout.background(InputLayout.field(typing)).bottom()<typing.frame().bottom());
    }
    @Test void minimizedPaneIsExactlyOneHeaderAndClosedRemainsAnOpenButton(){
        var s=new WindowState();s.mode=UiState.Mode.MINIMIZED;
        var p=panel(s,480,270,205);
        assertEquals(HeaderLayout.HEIGHT,p.frame().height());
        assertEquals(0,p.body().height());
        assertEquals(p.frame(),p.header());
        s.mode=UiState.Mode.CLOSED;
        var nativePanel=LayoutMath.window(480,270,s,18,205);
        assertSame(nativePanel,HeaderLayout.apply(nativePanel,205,480));
    }
    @Test void savedNarrowWidthClipsTabsAndClampsAtRightEdge(){
        var s=new WindowState();s.width=0.1;s.x=1;
        var p=panel(s,480,270,205);
        assertEquals(LayoutMath.MIN_WIDTH,p.frame().width());
        assertEquals(478,p.frame().right());
        assertTrue(HeaderLayout.tabs(p).width()>0);
    }
    @Test void normalBodySizeIsRetainedAndUnusedBottomTabSpaceIsRemoved(){
        var s=new WindowState();s.height=0.2;
        var old=LayoutMath.window(480,270,s,18,205);
        var p=HeaderLayout.apply(old,205,480);
        assertEquals(old.body().height(),p.body().height());
        assertEquals(HeaderLayout.HEIGHT+p.body().height(),p.frame().height());
        assertEquals(p.frame().height(),old.frame().height());
    }
    @Test void maximumWindowStillFillsTheViewportWhenTyping(){
        for(int[] size:new int[][]{{320,180},{480,270},{960,540}}){
            var s=new WindowState();s.mode=UiState.Mode.MAXIMIZED;
            var p=InputLayout.attach(panel(s,size[0],size[1],205));
            assertEquals(size[1]-2,p.frame().bottom());
            assertEquals(size[0]-2,p.frame().right());
            assertEquals(2,p.header().top());
        }
    }
}