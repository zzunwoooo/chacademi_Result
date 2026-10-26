package kr.chacademi.chatlayout;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class DynamicLayoutTest {
    @Test void arbitraryWindowsKeepIndependentGeometryAndModes(){
        WindowState a=new WindowState(),b=new WindowState(),c=new WindowState();
        b.anchor(.4,.3);c.anchor(.65,.55);c.systemDefault=true;
        var beforeB=LayoutMath.window(960,540,b,18,130);var beforeC=LayoutMath.window(960,540,c,18,130);
        a.anchor(.1,.1);a.resize(.5,.4);a.maximize();
        assertEquals(beforeB,LayoutMath.window(960,540,b,18,130));assertEquals(beforeC,LayoutMath.window(960,540,c,18,130));
        assertEquals(18,beforeC.body().height());assertEquals(15,beforeC.tabs());
    }
    @Test void naturalTabWidthFitsFreshFiveCategoryMainButManualResizeRemainsPossible(){
        WindowState main=new WindowState();
        assertEquals(145,LayoutMath.window(320,180,main,18,145).body().width());
        assertNull(main.width);
        main.resize(.41,.2);
        assertEquals(131,LayoutMath.window(320,180,main,18,145).body().width());
    }
    @Test void frozenDestinationSizeDoesNotChangeWhenMoreCategoriesAreMerged(){
        WindowState system=new WindowState();system.systemDefault=true;system.locked=true;system.anchor(.4,.35);
        var before=LayoutMath.window(960,540,system,18,130);
        system.width=(double)before.body().width()/960;system.height=(double)before.body().height()/540;
        var after=LayoutMath.window(960,540,system,18,380);
        assertEquals(before,after);assertTrue(system.locked);
    }
    @Test void minimumHeightFitsOneLineAndMinimizedBadgesDoNotCoverHeaderControls(){
        WindowState tiny=new WindowState();tiny.height=.001;
        var panel=LayoutMath.window(640,360,tiny,18,130);
        assertEquals(9,panel.body().height());
        tiny.minimize();panel=LayoutMath.window(640,360,tiny,18,130);
        assertEquals(0,panel.body().height());
        assertEquals(panel.header().bottom()+6,panel.body().bottom());
        assertTrue(panel.body().bottom()+1-6>=panel.header().bottom());
        assertEquals(LayoutMath.HEADER+6+LayoutMath.TAB_RESERVE,panel.frame().height());
    }

    @Test void systemMaximumAndRestoreStillBelongToOneDynamicWindow(){
        WindowState system=new WindowState();system.systemDefault=true;system.anchor(.3,.3);
        var before=LayoutMath.window(640,360,system,18,130);
        system.maximize();assertTrue(LayoutMath.window(640,360,system,18,130).body().height()>18);
        system.maximize();assertEquals(before,LayoutMath.window(640,360,system,18,130));
    }
}
