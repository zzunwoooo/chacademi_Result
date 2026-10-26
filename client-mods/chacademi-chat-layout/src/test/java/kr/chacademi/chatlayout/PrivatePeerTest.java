package kr.chacademi.chatlayout;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrivatePeerTest {
 @Test void exactRecipientAndServerAccountSurviveRestart(){
  var id=UUID.randomUUID();var p=new PrivatePeer("school.example/내계정",id);
  assertEquals(p,PrivatePeer.parse(p.marker()));
  assertNotEquals(p,new PrivatePeer("wild.example/내계정",id));
  assertNotEquals(p,new PrivatePeer("school.example/다른계정",id));
 }
 @Test void malformedAndOrdinaryTabsAreNotPrivate(){
  assertNull(PrivatePeer.parse("/msg Steve "));
  assertNull(PrivatePeer.parse(PrivatePeer.PREFIX+"invalid abc"));
  assertNull(PrivatePeer.parse(null));
 }
 @Test void commandLookingTextIsLiteralAndInvalidPayloadIsRejected(){
  assertEquals("/op player",PrivatePeer.message("/op player"));
  for(String s:new String[]{""," ","a\nb","x".repeat(241),"\u00a7cred","x\u202Ey"})
   assertThrows(IllegalArgumentException.class,()->PrivatePeer.message(s));
 }
}