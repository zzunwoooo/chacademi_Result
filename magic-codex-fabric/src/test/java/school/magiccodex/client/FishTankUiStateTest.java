package school.magiccodex.client;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.FishTankProtocol;
class FishTankUiStateTest {
 @Test void fishTrapHasNoAimPreviewAndUsesDirectUtilityCast(){assertNull(SpellTargets.get("fish_trap"));}

 @Test void mouseProjectionRoundTripsAcrossGuiScalesAndCameraZoom(){
  for(int gui=1;gui<=4;gui++)for(float zoom:new float[]{1,1.3f,1.6f,2.2f})for(float world:new float[]{-300,0,235,1000}){
   float center=1920f/gui/2,camera=235;float logical=FishTankUiMath.local(world,camera,zoom,center);double physical=logical*gui;
   assertEquals(world,FishTankUiMath.world(physical/gui,camera,zoom,center),.001);
  }
 }
 @Test void selectionSwitchKeepsCurrentFocusAndBlendsWithoutOvershoot(){
  assertTrue(FishTankUiMath.fresh(0,"old"));assertTrue(FishTankUiMath.fresh(1,null));assertFalse(FishTankUiMath.fresh(.5f,"old"));
  float focus=100,target=800;assertEquals(focus,focus+(target-focus)*FishTankUiMath.blend(0,6));
  for(int i=0;i<90;i++){float next=focus+(target-focus)*FishTankUiMath.blend(1/60f,6);assertTrue(next>=focus&&next<=target);focus=next;}assertEquals(target,focus,1);
 }
 @Test void cameraOpenAndCancelReachExactEndpoints(){
  float p=0;for(int i=0;i<60;i++)p=FishTankUiMath.progress(p,true,1/60f);assertEquals(1,p);
  for(int i=0;i<50;i++)p=FishTankUiMath.progress(p,false,1/60f);assertEquals(0,p);
  assertEquals(0,FishTankUiMath.ease(-1));assertEquals(1,FishTankUiMath.ease(2));float previous=0;
  for(int i=0;i<=100;i++){float value=FishTankUiMath.ease(i/100f);assertTrue(value>=previous-.00001f);previous=value;}
 }
 @Test void approvedMagnificationBoundsRetain380PixelUpperTarget(){
  for(float length:new float[]{40,60,80,100,128}){var m=FishTankUiMath.magnification(length);assertTrue(m.zoom()>=1.3&&m.zoom()<=2.2f);assertTrue(m.presentation()>=1&&m.presentation()<=2.4f);assertTrue(length*m.zoom()*m.presentation()<=380.01f);}
 }
 @Test void releaseNeedsTwoClicksWithinThreeSecondsAndResetsOnSelectionOrClose(){
  var c=new FishReleaseConfirmation();assertFalse(c.click(1000));assertTrue(c.armed(2000));assertTrue(c.click(2000));assertFalse(c.armed(2001));
  assertFalse(c.click(3000));assertFalse(c.click(6001));c.reset();assertFalse(c.armed(6002));assertFalse(c.click(6002));
 }
 @Test void authoritativeInsertUpdateRoundTripsFromTwelveToThirteen(){
  var fish=new ArrayList<FishTankProtocol.Fish>();for(int i=0;i<13;i++)fish.add(new FishTankProtocol.Fish(UUID.randomUUID().toString(),"fish_001",25.5f,1760000000000L,100,false));
  var before=new FishTankProtocol.State(FishTankProtocol.KIND_STATE,100,false,fish.subList(0,12),List.of(),List.of(),"");var after=new FishTankProtocol.State(FishTankProtocol.KIND_STATE,100,false,fish,List.of(),List.of(),"");
  assertEquals(12,((FishTankProtocol.State)FishTankProtocol.decode(FishTankProtocol.encode(before))).fish().size());assertEquals(13,((FishTankProtocol.State)FishTankProtocol.decode(FishTankProtocol.encode(after))).fish().size());assertEquals(before.capacity(),after.capacity());
 }
 @Test void sizesReadAcrossAndWithinSpeciesAndDepthNoLongerMasksThem(){
  assertEquals(18,FishTankUiMath.absoluteLength(3),.01);assertTrue(FishTankUiMath.absoluteLength(10)>FishTankUiMath.absoluteLength(5)*1.3f);
  float small=FishTankUiMath.length(8,8,15),big=FishTankUiMath.length(15,8,15);assertTrue(big/small>1.5f,"same species 8cm vs 15cm");
  assertEquals(FishTankUiMath.absoluteLength(12),FishTankUiMath.length(12,12,12),.001);
  assertTrue(FishTankUiMath.depthScale(0)/FishTankUiMath.depthScale(1)>.85f);assertTrue(big*FishTankUiMath.depthScale(0)>small*FishTankUiMath.depthScale(1));
  assertTrue(Float.isFinite(FishTankUiMath.length(Float.NaN,1,2)));
 }
}

