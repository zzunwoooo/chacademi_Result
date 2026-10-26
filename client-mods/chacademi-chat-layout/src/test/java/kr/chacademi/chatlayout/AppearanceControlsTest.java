package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class AppearanceControlsTest {
 @Test void limitsAreStableAndReversible(){
  assertEquals(1,AppearanceControls.opacity(0.98,1));
  assertEquals(0,AppearanceControls.opacity(0.02,-1));
  assertEquals(0.5,AppearanceControls.opacity(AppearanceControls.opacity(0.5,1),-1));
  assertEquals(2f,AppearanceControls.scale(1.99f,1));
  assertEquals(0.5f,AppearanceControls.scale(0.51f,-1));
  assertEquals(1f,AppearanceControls.scale(AppearanceControls.scale(1f,1),-1));
 }
 @Test void compactSystemAndLargePaneButtonsStayInsideAndSeparate(){
  for(int height:new int[]{23,30,80,300}){
   var body=new LayoutMath.Rect(2,20,220,height);
   for(int i=0;i<4;i++){
    var b=AppearanceControls.button(body,i);
    assertTrue(b.top()>=body.top());assertTrue(b.bottom()<=body.bottom());
    assertTrue(b.right()<body.right());
    var clip=ContentInsets.messageClipWithControls(body);
    assertTrue(clip.right()<=b.x());
    for(int j=i+1;j<4;j++){
     var other=AppearanceControls.button(body,j);
     assertFalse(b.x()<other.right()&&b.right()>other.x()&&b.top()<other.bottom()&&b.bottom()>other.top());
    }
   }
  }
 }
 @Test void wrappingReservesRailAtEveryTextScale(){
  for(float scale:new float[]{0.5f,0.9f,1f,1.5f,2f})
   assertEquals(29,AppearanceControls.rightPadding(scale)*scale,scale/2+0.001);
 }
 @Test void perPaneOpacitySurvivesSaveAndLegacyDefaults(@TempDir Path dir)throws Exception{
  var state=new LayoutState();var first=new WindowState();var second=new WindowState();
  first.backgroundOpacity=0.7;state.windows.add(first);state.windows.add(second);
  var store=new LayoutStateStore(dir.resolve("layout.json"));store.save(state);
  var restored=store.load();
  assertEquals(0.7,restored.windows.get(0).backgroundOpacity);
  assertNull(restored.windows.get(1).backgroundOpacity);assertEquals(0.5,restored.backgroundOpacity);
 }
 @Test void malformedPaneOpacityFallsBack(){
  var state=new WindowState();state.backgroundOpacity=Double.NaN;state.sanitize();assertNull(state.backgroundOpacity);
  state.backgroundOpacity=4.0;state.sanitize();assertEquals(1.0,state.backgroundOpacity);
 }
}