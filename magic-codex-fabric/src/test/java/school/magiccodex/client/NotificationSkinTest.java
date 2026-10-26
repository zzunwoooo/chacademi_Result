package school.magiccodex.client;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
/** 2026-10-24 알림 외형: 승인 아이콘 10종만 쓰고, 긴 문구는 자르지 않고 줄을 늘린다. */
class NotificationSkinTest {
 @Test void kindsMapOnlyToApprovedIcons(){
  assertEquals("warning",NotificationSkin.icon("error"));assertEquals("warning",NotificationSkin.icon("warning"));
  assertEquals("social",NotificationSkin.icon("friend"));assertEquals("quest",NotificationSkin.icon("quest"));
  assertEquals("mail",NotificationSkin.icon("shop"));assertEquals("system",NotificationSkin.icon("system"));
  assertEquals("system",NotificationSkin.icon(null));assertEquals("system",NotificationSkin.icon("brand_new_kind"));
  for(var i:NotificationSkin.ICONS)assertEquals(i,NotificationSkin.icon(i));
  assertEquals(10,NotificationSkin.ICONS.size());
 }
 @Test void longTextWrapsAndGrowsInsteadOfTruncating(){
  java.util.function.ToDoubleFunction<String> w=s->s.codePointCount(0,s.length())*20.0;
  var short1=SharedNotifications.layout("새 우편 3건","우편함에서 확인할 수 있어요.",w,w);
  assertEquals(SharedNotifications.MIN_H,short1.height());
  String body="아주 긴 본문이 들어와도 끝까지 읽을 수 있어야 하고 닫기 버튼이나 다른 카드와 겹치면 안 됩니다. ".repeat(3);
  var tall=SharedNotifications.layout("이름이 아주 아주 아주 아주 아주 긴 알림 제목입니다",body,w,w);
  assertTrue(tall.title().size()>1);assertTrue(tall.body().size()>3);assertTrue(tall.height()>short1.height());
  String joined=String.join("",tall.body()).replace(" ","");assertEquals(body.replace(" ",""),joined);
  float room=SharedNotifications.TEXT_RIGHT-SharedNotifications.TEXT_X;for(var l:tall.body())assertTrue(w.applyAsDouble(l)<=room);
 }
}
