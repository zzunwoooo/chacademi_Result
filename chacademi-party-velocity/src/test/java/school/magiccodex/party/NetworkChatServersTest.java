package school.magiccodex.party;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** C02 (2026-10-21): chat servers come from party.properties (chat-servers), so an arena named e.g. "pvp" works; trust is still required. */
class NetworkChatServersTest {
 @Test void configuredArenaNameIsAChatServerOnlyWhenTrusted()throws Exception {
  var chat=new NetworkChat(Set.of("school","wild","pvp"),Set.of("school","wild","pvp"));
  assertTrue(chat.networkServer("pvp"));assertFalse(chat.networkServer("arena"));
  var id=UUID.randomUUID();var source=new Object();
  assertTrue(chat.accept(id,source,source,"pvp",true,NetworkChatTest.wire(2,"별빛",0),0));
  assertEquals("별빛",chat.sender(id,Map.of(id,new NetworkChat.Connection(source,"pvp")),1).nickname());
  // listed as a chat server but not trusted: no identity, no chat
  var untrusted=new NetworkChat(Set.of("school","wild"),Set.of("school","wild","pvp"));
  assertFalse(untrusted.networkServer("pvp"));
  assertFalse(untrusted.accept(id,source,source,"pvp",true,NetworkChatTest.wire(2,"별빛",0),0));
 }
}
