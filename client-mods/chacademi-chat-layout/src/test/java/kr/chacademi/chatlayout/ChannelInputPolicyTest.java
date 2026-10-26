package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static kr.chacademi.chatlayout.ChannelInputPolicy.*;
class ChannelInputPolicyTest {
 @Test void onlyExactManagedCategoriesGetNewRoutes(){
  assertEquals(Route.GLOBAL,route("전체",false,"hello"));assertEquals(Route.GLOBAL,route("일반",false,"hello"));assertEquals(Route.HOUSE,route("기숙사",false,"hello"));assertEquals(Route.SYSTEM,route("시스템",false,"hello"));
  for(String tab:new String[]{"파티","귓말","내 탭","전체 로그"})assertEquals(Route.PASS,route(tab,false,"hello"));
 }
 @Test void slashCommandsAndPrivateTabsRetainExistingDispatch(){
  for(String tab:new String[]{"전체","기숙사","시스템","파티"}){assertEquals(Route.PASS,route(tab,false," /help"));assertEquals(Route.PASS,route(tab,true,"message"));}
 }
 @Test void channelCommandsAreBoundedAndCannotBecomePublicFallback(){
  assertEquals("채팅 전체 hello",command(Route.GLOBAL,"hello"));assertEquals("채팅 기숙사 hello",command(Route.HOUSE,"hello"));
  assertTrue(command(Route.HOUSE,"x".repeat(240)).length()<=256);
  for(String bad:new String[]{"","x".repeat(241),"a\nb","\u00a7cred","a\u202Eb"})assertThrows(IllegalArgumentException.class,()->command(Route.HOUSE,bad));
  assertThrows(IllegalArgumentException.class,()->command(Route.SYSTEM,"hello"));assertThrows(IllegalArgumentException.class,()->command(Route.PASS,"hello"));
 }
}
