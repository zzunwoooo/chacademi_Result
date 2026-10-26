package school.magiccodex.party;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 튜토리얼 서버 분산 (2026-10-25; 2026-10-26부터 기본은 tutorial 하나라 꺼짐, 2개 이상 적었을 때의 동작): 무리 안에서 인원이 적은 서버로, 허용치·같은 서버 유지·꺼진 서버 제외. */
class TutorialRouterTest {
 static final List<String> G=List.of("tutA","tutB");
 static Map<String,Integer> counts(int a,int b){var m=new LinkedHashMap<String,Integer>();m.put("tutA",a);m.put("tutB",b);return m;}
 @Test void parsesListAndOnlyTwoOrMoreEnable(){
  assertEquals(List.of("a","b"),TutorialRouter.parseList(" a, b ,a,,"));
  assertEquals(List.of(),TutorialRouter.parseList(""));
  assertEquals(List.of("tutorial"),TutorialRouter.parseList("tutorial")); // 2026-10-26 기본: 하나 → 분산 꺼짐
 }
 @Test void picksTheEmptierServer(){
  assertEquals("tutB",TutorialRouter.pick(G,counts(5,2),"tutA",null,0));
  assertEquals("tutA",TutorialRouter.pick(G,counts(2,5),"tutA",null,0));
  assertEquals("tutA",TutorialRouter.pick(G,counts(3,3),"tutA",null,0));
  assertEquals("tutB",TutorialRouter.pick(G,counts(3,3),"tutB",null,0));
 }
 @Test void toleranceKeepsTheRequestedServer(){
  assertEquals("tutA",TutorialRouter.pick(G,counts(5,3),"tutA",null,2));
  assertEquals("tutB",TutorialRouter.pick(G,counts(6,3),"tutA",null,2));
 }
 @Test void stickyServerWinsWhileItIsUp(){
  assertEquals("tutA",TutorialRouter.pick(G,counts(9,0),"tutB","tutA",0));
  var onlySecond=new LinkedHashMap<String,Integer>();onlySecond.put("tutB",9);
  assertEquals("tutB",TutorialRouter.pick(G,onlySecond,"tutA","tutA",0));
 }
 @Test void noServerUpKeepsTheRequest(){
  assertEquals("tutA",TutorialRouter.pick(G,Map.of(),"tutA",null,0));
 }
}
