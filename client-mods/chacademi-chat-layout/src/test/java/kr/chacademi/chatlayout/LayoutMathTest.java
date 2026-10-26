package kr.chacademi.chatlayout;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LayoutMathTest {
    @Test void firstPlacementIsDockedAndSystemHasTwoRows() {
        for(int[] viewport:new int[][]{{1920,1080},{960,540},{640,360},{480,270},{320,180},{400,300}}) {
            UiState s=new UiState();
            var p=LayoutMath.calculate(viewport[0],viewport[1],s,18,true,false);
            assertEquals(p.upper().frame().bottom(),p.lower().frame().top());
            assertEquals(18,p.lower().body().height());
            assertEquals(0,p.lower().tabs());
            assertTrue(p.upper().body().width()>=130);
            assertTrue(p.lower().frame().bottom()<=viewport[1]-20);
        }
    }
    private static UiState initialized() {
        UiState s=new UiState();var p=LayoutMath.calculate(960,540,s,18,true,false);
        s.anchor(false,(double)p.lower().frame().x()/960,(double)p.lower().frame().top()/540);return s;
    }
    @Test void movingOrChangingUpperDoesNotChangeLower() {
        UiState s=initialized();var lower=LayoutMath.calculate(960,540,s,18,true,false).lower();
        s.anchor(true,.2,.1);
        assertEquals(lower,LayoutMath.calculate(960,540,s,18,true,false).lower());
        for(UiState.Mode m:UiState.Mode.values()) {
            s.upper=m;assertEquals(lower,LayoutMath.calculate(960,540,s,18,true,false).lower());
        }
        s.resize(true,.4,.3);assertEquals(lower,LayoutMath.calculate(960,540,s,18,true,false).lower());
    }
    @Test void movingOrChangingLowerDoesNotChangeUpper() {
        UiState s=initialized();var upper=LayoutMath.calculate(960,540,s,18,true,false).upper();
        s.anchor(false,.4,.55);
        for(UiState.Mode m:UiState.Mode.values()) {
            s.lower=m;assertEquals(upper,LayoutMath.calculate(960,540,s,18,true,false).upper());
        }
        s.resize(false,.5,.2);assertEquals(upper,LayoutMath.calculate(960,540,s,18,true,false).upper());
    }
    @Test void normalizedAnchorsAndSizesSurviveScaleWithoutWritingClampsIntoState() {
        UiState s=initialized();s.anchor(true,.1,.1);s.resize(true,.3,.2);
        for(int scale:new int[]{1,2,3,4}) {
            var p=LayoutMath.calculate(1920/scale,1080/scale,s,18,true,false);
            assertEquals(.1,(double)p.upper().frame().x()/p.viewportWidth(),.003);
            assertEquals(.3,(double)p.upper().body().width()/p.viewportWidth(),.003);
            assertEquals(.2,(double)p.upper().body().height()/p.viewportHeight(),.003);
            assertEquals(.3,s.upperWidth);assertEquals(.2,s.upperHeight);
        }
    }
    @Test void modesAndControlsFitAndManualSystemHeightIsAllowed() {
        UiState s=initialized();s.resize(false,.3,.12);
        var p=LayoutMath.calculate(960,540,s,18,true,false);
        assertEquals(65,p.lower().body().height());
        for(int i=0;i<2;i++)assertTrue(p.upper().header().contains(p.upper().control(i).x(),p.upper().control(i).top()));
        s.maximize(true);p=LayoutMath.calculate(960,540,s,18,true,false);
        assertEquals(2,p.upper().frame().x());assertTrue(p.upper().frame().bottom()<=520);
        s.minimize(true);p=LayoutMath.calculate(960,540,s,18,true,false);
        assertEquals(0,p.upper().body().height());assertEquals(15,p.upper().tabs());
        s.close(true);p=LayoutMath.calculate(960,540,s,18,true,false);
        assertEquals(34,p.upper().frame().width());assertEquals(0,p.upper().tabs());
    }
    @Test void maximizingSystemExpandsOnlySystemAndRestoresTwoLineDefault() {
        UiState s=initialized();
        var before=LayoutMath.calculate(960,540,s,18,true,false);
        s.maximize(false);
        var expanded=LayoutMath.calculate(960,540,s,18,true,false);
        assertEquals(before.upper(),expanded.upper());
        assertEquals(956,expanded.lower().body().width());
        assertTrue(expanded.lower().body().height()>18);
        assertTrue(expanded.lower().frame().bottom()<=520);
        s.maximize(false);
        assertEquals(before,LayoutMath.calculate(960,540,s,18,true,false));
    }

    @Test void defaultMinimumWidthExceptionDoesNotChangeSavedSize() {
        UiState s=initialized();var p=LayoutMath.calculate(320,180,s,18,true,false);
        assertEquals(130,p.upper().body().width());assertNull(s.upperWidth);assertNull(s.lowerWidth);
    }
}
