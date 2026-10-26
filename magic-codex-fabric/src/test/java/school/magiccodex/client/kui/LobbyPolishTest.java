package school.magiccodex.client.kui;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class LobbyPolishTest {
 final List<String> texts=new ArrayList<>();final Map<String,Integer> colors=new HashMap<>();int depth;
 Canvas canvas(){return (Canvas)Proxy.newProxyInstance(Canvas.class.getClassLoader(),new Class<?>[]{Canvas.class},(p,m,a)->{
  if(m.getName().equals("width"))return ((String)a[0]).length()*(Float)a[1]*.6f;
  if(m.getName().equals("text")){texts.add((String)a[0]);colors.put((String)a[0],(Integer)a[4]);}
  if(m.getName().equals("push"))depth++;
  if(m.getName().equals("pop")){depth--;assertTrue(depth>=0,"Unbalanced canvas transform");}
  return m.getReturnType()==boolean.class?false:null;
 });}
 Pen render(int tab,int mode,float left,float right){LobbyView.edges(left,right);LobbyView.keyCapture(false);var p=new Pen(canvas(),-10000,-10000,null);LobbyView.content(p,Samples.lobby(tab,mode));assertEquals(0,depth);return p;}
 Pen.Hit hit(Pen p,String id){return p.hits().stream().filter(h->h.id().equals(id)).findFirst().orElseThrow();}
 @AfterEach void reset(){LobbyView.edges(0,0);LobbyView.keyCapture(false);LobbyView.tierInfo(false);}
 @Test void wideWindowMovesLeftMenusAndRightActionsWithTheirClickTargets(){
  var normal=render(0,0,0,0);var wide=render(0,0,59,59);
  for(var id:List.of(LobbyView.tabId(0),LobbyView.modeId(1),LobbyView.PRACTICE,LobbyView.SPELLS))assertEquals(hit(normal,id).x()-59,hit(wide,id).x(),.001,id);
  for(var id:List.of(LobbyView.START,LobbyView.PARTY_TOGGLE,LobbyView.INVITE))assertEquals(hit(normal,id).x()+59,hit(wide,id).x(),.001,id);
  var button=hit(wide,LobbyView.START);assertEquals(LobbyView.START,wide.hitAt(button.x()+button.w()/2,button.y()+button.h()/2));
 }
 @Test void specialKeyHitAreaUsesScaledPositionAndIsAbsentFromDuel(){
  var p=render(0,0,59,59);var h=hit(p,LobbyView.SPECIAL_KEY);assertEquals(62*.7,h.w(),.001);assertEquals(62*.7,h.h(),.001);
  assertEquals(LobbyView.SPECIAL_KEY,p.hitAt(h.x()+h.w()/2,h.y()+h.h()/2));
  assertTrue(render(0,1,59,59).hits().stream().noneMatch(x->x.id().equals(LobbyView.SPECIAL_KEY)));
 }
 @Test void recordSearchMovesRightWhilePlayerRowsStayCentered(){
  var normal=render(3,0,0,0);var wide=render(3,0,300,300);
  assertEquals(hit(normal,LobbyView.RECORD_SEARCH).x()+300,hit(wide,LobbyView.RECORD_SEARCH).x(),.001);
  var ranks=render(2,0,0,0);var wideRanks=render(2,0,300,300);
  var rows=ranks.hits().stream().filter(h->h.id().startsWith("row:")).toList();assertFalse(rows.isEmpty());
  for(var row:rows)assertEquals(row.x(),hit(wideRanks,row.id()).x(),.001);
 }
 @Test void allTabsAndModesKeepBalancedTransformsAtWideAspectRatios(){for(float edge:new float[]{0,59,600})for(int mode=0;mode<2;mode++)for(int tab=0;tab<4;tab++)render(tab,mode,edge,edge);}
 @Test void duelUsesOneLineTitleAndInvalidNegativeEdgesDoNotShiftControls(){
  var normal=render(0,1,0,0);assertTrue(texts.contains("1:1 대전"));assertFalse(texts.contains("랭크 대전"));
  var invalid=render(0,1,-50,-20);assertEquals(hit(normal,LobbyView.START),hit(invalid,LobbyView.START));
 }

 @Test void tierInformationButtonTracksTheRightEdgeInBothModes(){
  for(int mode=0;mode<2;mode++){var normal=render(0,mode,0,0);var wide=render(0,mode,300,300);var h=hit(wide,LobbyView.TIER_INFO);assertEquals(hit(normal,LobbyView.TIER_INFO).x()+300,h.x(),.001);assertEquals(LobbyView.TIER_INFO,wide.hitAt(h.x()+h.w()/2,h.y()+h.h()/2));}
 }
 @Test void tierTableHasAllRatingRangesAndHighlightsCurrentTier(){
  LobbyView.tierInfo(true);render(0,0,59,59);
  for(var text:List.of("티어 정보","~ 899","900 ~ 1099","1100 ~ 1299","1300 ~ 1499","1500 ~ 1699","1700 ~","1700 이상 1~3위","챌린저"))assertTrue(texts.contains(text),text);
  assertEquals(0xFF2BD3EA,colors.get("골드").intValue());assertNotEquals(0xFF2BD3EA,colors.get("실버").intValue());
  assertArrayEquals(new int[]{900,1100,1300,1500,1700},LobbyView.TIER_FLOORS);
 }
}
