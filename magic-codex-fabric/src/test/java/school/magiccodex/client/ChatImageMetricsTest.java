package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatImageMetricsTest {
 @Test void imageAndAdvanceScaleTogetherWithoutRowOverlap(){
  for(float scale:new float[]{0.5f,1f,1.3f,2f,4f}){
   assertTrue(ChatImageMetrics.ADVANCE*scale>ChatImageMetrics.SIZE*scale);
   assertEquals(12*scale,(ChatImageMetrics.BOTTOM-ChatImageMetrics.TOP)*scale,0.0001);
   assertTrue(13*scale>=ChatImageMetrics.SIZE*scale);
  }
 }
 @Test void colorNeverTintsImageButFadeAlphaSurvives(){
  for(int alpha:new int[]{0,1,64,128,255})for(int tint:new int[]{0x55FFFF,0x000000,0xFFAA00})
   assertEquals((alpha<<24)|0xFFFFFF,ChatImageMetrics.untinted((alpha<<24)|tint));
  assertFalse(ChatImageMetrics.isIcon(0xE101));assertFalse(ChatImageMetrics.isIcon(0xE116));
 }
}
