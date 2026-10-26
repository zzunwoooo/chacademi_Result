package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EditGeometryTest {
    private LayoutMath.Panel panel(){
        var s=new WindowState();s.x=.2;s.y=.2;s.width=.4;s.height=.3;
        return HeaderLayout.apply(LayoutMath.window(1000,600,s,20,800),800,1000);
    }
    @Test void allEightGripsAndInteriorAreDistinct(){
        var f=panel().frame();
        int[] xs={f.x(),f.x()+f.width()/2,f.right()-1};
        int[] ys={f.top(),f.top()+f.height()/2,f.bottom()-1};
        int[][] expected={{5,4,6},{1,0,2},{9,8,10}};
        for(int y=0;y<3;y++)for(int x=0;x<3;x++)
            assertEquals(expected[y][x],EdgeResizeSession.edges(f,xs[x],ys[y]));
        assertEquals(0,EdgeResizeSession.edges(f,f.right(),f.bottom()));
    }
    @Test void eachGripKeepsOppositeEdgesAndUnselectedAxes(){
        var p=panel();var f=p.frame();
        for(int edge:new int[]{1,2,4,8,5,6,9,10}){
            var session=EdgeResizeSession.begin(p,300,200,1000,600,edge,10);
            var b=session.move(320,212,1000,600);
            assertEquals((edge&1)!=0?.22:.2,b.x(),1e-9);
            assertEquals((edge&4)!=0?.22:.2,b.y(),1e-9);
            assertEquals((edge&2)!=0?.62:.6,b.x()+b.width(),1e-9);
            double bottom=(double)f.bottom()/600;
            assertEquals(bottom+((edge&8)!=0?.02:0),b.y()+b.height()+16.0/600,1e-9);
        }
    }
    @Test void horizontalAndVerticalSizesHaveNoAspectConstraint(){
        var p=panel();
        var right=EdgeResizeSession.begin(p,0,0,1000,600,2,10).move(100,300,1000,600);
        assertEquals(.5,right.width(),1e-9);assertEquals(.3,right.height(),1e-9);
        var down=EdgeResizeSession.begin(p,0,0,1000,600,8,10).move(100,60,1000,600);
        assertEquals(.4,down.width(),1e-9);assertEquals(.4,down.height(),1e-9);
    }
    @Test void viewportAndMinimumClampsDoNotInvertEdges(){
        var p=panel();
        var small=EdgeResizeSession.begin(p,0,0,1000,600,5,10).move(900,900,1000,600);
        assertEquals(.13,small.width(),1e-9);assertEquals(10.0/600,small.height(),1e-9);
        var large=EdgeResizeSession.begin(p,0,0,1000,600,10,10).move(900,900,1000,600);
        assertEquals(.998,large.x()+large.width(),1e-9);
        assertEquals(578.0/600,large.y()+large.height()+16.0/600,1e-9);
    }
    @Test void scaleChangesUseNormalizedPointerAndCurrentChromePixels(){
        var p=panel();
        var b=EdgeResizeSession.begin(p,300,200,1000,600,2,10).move(150,100,500,300);
        assertEquals(.4,b.width(),1e-9);
        assertEquals(.2,b.x(),1e-9);
    }
    @Test void onlyControlAndShiftReleaseEndEditing(){
        for(int key:new int[]{340,341,344,345}){
            assertTrue(EditGesturePolicy.modifierReleased(key,0));
            assertFalse(EditGesturePolicy.modifierReleased(key,1));
            assertFalse(EditGesturePolicy.modifierReleased(key,2));
        }
        assertFalse(EditGesturePolicy.modifierReleased(342,0));
        assertFalse(EditGesturePolicy.modifierReleased(65,0));
    }
    @Test void controlAndShiftAreBothRequired(){
        assertFalse(EditGesturePolicy.held(false,false));
        assertFalse(EditGesturePolicy.held(true,false));
        assertFalse(EditGesturePolicy.held(false,true));
        assertTrue(EditGesturePolicy.held(true,true));
    }
    @Test void removedModesMigrateButClosedAndAppearanceAndGeometrySurvive(){
        for(var mode:UiState.Mode.values()){
            var s=new WindowState();s.mode=mode;s.restore=UiState.Mode.MAXIMIZED;
            s.x=.3;s.y=.4;s.width=.55;s.height=.12;s.backgroundOpacity=.7;s.locked=true;
            s.sanitize();
            assertEquals(mode==UiState.Mode.CLOSED?mode:UiState.Mode.NORMAL,s.mode);
            assertEquals(UiState.Mode.NORMAL,s.restore);
            assertEquals(.55,s.width);assertEquals(.12,s.height);assertEquals(.7,s.backgroundOpacity);
            assertTrue(s.locked);s.open();assertEquals(UiState.Mode.NORMAL,s.mode);
        }
    }
    @Test void reconnectAndScaleChangesRetainSavedGeometry() throws Exception {
        var file=java.nio.file.Files.createTempDirectory("chat-edit-").resolve("layout.json");
        var saved=new LayoutState();var state=new WindowState();
        state.x=.4;state.y=.5;state.width=.3;state.height=.25;saved.windows.add(state);
        var store=new LayoutStateStore(file);store.save(saved);
        for(int[] viewport:new int[][]{{1000,600},{320,180},{1920,1080},{480,270}}){
            var loaded=store.load().windows.get(0);
            var p=HeaderLayout.apply(LayoutMath.window(viewport[0],viewport[1],loaded,20,800),800,viewport[0]);
            var frame=InputLayout.attach(p).frame();
            assertTrue(frame.x()>=2&&frame.top()>=2);
            assertTrue(frame.right()<=viewport[0]-2&&frame.bottom()<=viewport[1]-2);
            assertEquals(.4,loaded.x);assertEquals(.5,loaded.y);
            assertEquals(.3,loaded.width);assertEquals(.25,loaded.height);
        }
    }
    @Test void resizedBoundsAgreeWithRenderedBoundsAtMinimumAndScreenEdges(){
        for(boolean typing:new boolean[]{false,true}){
            var p=typing?InputLayout.attach(panel()):panel();
            for(int edge:new int[]{1,2,4,8,5,6,9,10})
                for(int offset:new int[]{-2000,0,2000}){
                    var b=EdgeResizeSession.begin(p,0,0,1000,600,edge,10).move(offset,offset,1000,600);
                    var saved=new WindowState();saved.anchor(b.x(),b.y());saved.resize(b.width(),b.height());
                    var rendered=HeaderLayout.apply(LayoutMath.window(1000,600,saved,20,800),800,1000);
                    if(typing)rendered=InputLayout.attach(rendered);
                    assertEquals(b.x()*1000,rendered.frame().x(),1);
                    assertEquals(b.y()*600,rendered.frame().top(),1);
                    assertEquals(b.width()*1000,rendered.body().width(),1);
                    assertEquals(b.height()*600,rendered.body().height(),1);
                }
        }
    }
    @Test void explicitNarrowWidthDoesNotGrowWhenNewWhisperTabsArrive(){
        var p=panel();assertEquals(400,p.frame().width());
        assertTrue(HeaderLayout.tabs(p).right()<p.control(0).x());
    }
}
