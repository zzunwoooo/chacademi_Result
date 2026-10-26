package school.magiccodex.client;
import java.util.*;
import school.magiccodex.protocol.SocialProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChatReplyPolicyTest {
 private static SocialProtocol.Response response(int kind,UUID id,long ticket){
  return new SocialProtocol.Response(kind,4,id,ticket,"name","","",0,List.of());
 }
 @Test void composeRequiresFreshBoundTicket(){
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  assertTrue(ChatReplyPolicy.compose(a,0,response(SocialProtocol.COMPOSE,a,7)));
  assertFalse(ChatReplyPolicy.compose(a,0,response(SocialProtocol.COMPOSE,b,7)));
  assertFalse(ChatReplyPolicy.compose(a,7,response(SocialProtocol.COMPOSE,a,7)));
  assertFalse(ChatReplyPolicy.compose(a,0,response(SocialProtocol.COMPOSE,a,0)));
 }
 @Test void acknowledgementCannotBeMisattributedAfterSwitchingTabs(){
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  assertTrue(ChatReplyPolicy.sent(a,7,response(SocialProtocol.SENT,a,7)));
  assertFalse(ChatReplyPolicy.sent(a,7,response(SocialProtocol.SENT,b,7)));
  assertFalse(ChatReplyPolicy.sent(a,7,response(SocialProtocol.SENT,a,8)));
  assertFalse(ChatReplyPolicy.sent(a,0,response(SocialProtocol.SENT,a,0)));
  assertFalse(ChatReplyPolicy.sent(a,7,response(SocialProtocol.NOTICE,a,7)));
 }
 @Test void chatArtworkIsHighResolutionAndTransparent()throws Exception{
  var image=javax.imageio.ImageIO.read(java.nio.file.Path.of("src/main/resources/assets/magiccodex/textures/font/whisper_envelope.png").toFile());
  assertTrue(image.getWidth()>=128);assertTrue(image.getColorModel().hasAlpha());
  assertTrue(image.getHeight()<image.getWidth());
  var font=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/magiccodex/font/whisper.json"))).getAsJsonObject();
  var provider=font.getAsJsonArray("providers").get(0).getAsJsonObject();
  assertEquals(8,provider.get("height").getAsInt());assertEquals("\uE101",provider.getAsJsonArray("chars").get(0).getAsString());
 }
}