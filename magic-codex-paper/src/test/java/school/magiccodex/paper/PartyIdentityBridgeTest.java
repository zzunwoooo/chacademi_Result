package school.magiccodex.paper;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PartyIdentityBridgeTest {
 @Test void v2CarriesOnlyNicknameAndBoundedHouse()throws Exception {
  for(int house=-1;house<=3;house++)try(var in=new DataInputStream(new ByteArrayInputStream(PartyIdentityBridge.encodeIdentity("별빛",house)))){
   assertEquals(2,in.readUnsignedByte());assertEquals("별빛",in.readUTF());assertEquals(house,in.readByte());assertEquals(0,in.available());
  }
 }
 @Test void invalidIdentityCannotBePublished(){
  assertThrows(IllegalArgumentException.class,()->PartyIdentityBridge.encodeIdentity("별빛",4));
  assertThrows(IllegalArgumentException.class,()->PartyIdentityBridge.encodeIdentity("별빛",-2));
  for(String bad:new String[]{"","x".repeat(33),"a\nb","\u00a7cname","a\u202Eb"})assertThrows(IllegalArgumentException.class,()->PartyIdentityBridge.encodeIdentity(bad,0));
 }
}
