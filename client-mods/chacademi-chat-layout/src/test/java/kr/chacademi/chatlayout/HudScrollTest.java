package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HudScrollTest {
 @Test void wheelMovesBothWaysAndShiftUsesSingleLine(){
  var s=new HudScroll();var pane=new Object();
  assertEquals(3,s.lines(pane,1,false));assertEquals(-3,s.lines(pane,-1,false));
  assertEquals(1,s.lines(pane,1,true));
 }
 @Test void trackpadRemainderBelongsOnlyToHoveredPane(){
  var s=new HudScroll();var a=new Object();var b=new Object();
  assertEquals(0,s.lines(a,.2,false));assertEquals(1,s.lines(a,.2,false));
  assertEquals(0,s.lines(b,.2,false));s.reset();assertEquals(0,s.lines(b,.2,false));
 }
 @Test void invalidAndExcessiveWheelCannotOverflow(){
  var s=new HudScroll();var a=new Object();
  assertEquals(0,s.lines(a,Double.NaN,false));assertEquals(30,s.lines(a,1e99,false));
 }
}