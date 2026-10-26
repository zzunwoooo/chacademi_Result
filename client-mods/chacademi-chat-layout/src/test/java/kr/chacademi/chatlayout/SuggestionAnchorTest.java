package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import kr.chacademi.chatlayout.LayoutMath.Rect;
import static org.junit.jupiter.api.Assertions.*;
class SuggestionAnchorTest {
 @Test void topInputOpensBelow(){
  var p=InputLayout.anchoredPopup(new Rect(10,30,190,12),35,80,10,480,270);
  assertEquals(46,p.top()); assertEquals(120,p.height());
 }
 @Test void bottomInputOpensAbove(){
  var p=InputLayout.anchoredPopup(new Rect(10,240,190,12),35,80,10,480,270);
  assertEquals(236,p.bottom());
 }
 @Test void detachedMovementReanchors(){
  var p=InputLayout.anchoredPopup(new Rect(110,200,190,12),135,80,4,480,270);
  assertEquals(135,p.x()); assertEquals(196,p.bottom());
 }
 @Test void edgesKeepFullRows(){
  var p=InputLayout.anchoredPopup(new Rect(200,80,80,12),270,100,20,320,180);
  assertEquals(0,p.height()%12); assertTrue(p.right()<=318);
  assertTrue(p.bottom()<=178); assertTrue(p.top()>=2); assertTrue(p.height()<240);
 }
 @Test void largerViewportRestoresRows(){
  var small=InputLayout.anchoredPopup(new Rect(10,80,190,12),35,80,10,480,180);
  var large=InputLayout.anchoredPopup(new Rect(10,240,190,12),35,80,10,480,300);
  assertTrue(large.height()>small.height()); assertEquals(120,large.height());
 }
}