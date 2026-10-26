package school.magiccodex.paper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WhisperPeersTest {
 @Test void repliesOnlyFollowDeliveredConversationsAndEndOnDisconnect(){
  var p=new WhisperPeers();var a=UUID.randomUUID();var b=UUID.randomUUID();var c=UUID.randomUUID();
  assertFalse(p.contains(a,b));p.delivered(a,b);assertTrue(p.contains(a,b));assertTrue(p.contains(b,a));
  assertFalse(p.contains(c,b));p.remove(a);assertFalse(p.contains(b,a));assertFalse(p.contains(a,b));
 }
 @Test void unfriendingRevokesTheReplyRightForThatPairOnly(){
  var p=new WhisperPeers();var a=UUID.randomUUID();var b=UUID.randomUUID();var c=UUID.randomUUID();
  p.delivered(a,b);p.delivered(a,c);p.forget(b,a);p.forget(b,c);
  assertFalse(p.contains(a,b));assertFalse(p.contains(b,a));assertTrue(p.contains(a,c));assertTrue(p.contains(c,a));
 }
 @Test void legacyWindCooldownDoesNotBlockButManaStillLimits(){
  var a=new ManaAccount(2,10,0);a.cooldown("wind_message",50000,0);
  var wind=new ManaSpells.Spell("wind_message","wind","magic.wind",1,1000);
  assertEquals(0,ManaCasting.attempt(a,wind,true,1,()->true).cooldownMillis());
  assertEquals(school.magiccodex.protocol.ManaProtocol.OK,ManaCasting.attempt(a,wind,true,2,()->true).status());
  assertEquals(school.magiccodex.protocol.ManaProtocol.EMPTY,ManaCasting.attempt(a,wind,true,3,()->true).status());
 }
}