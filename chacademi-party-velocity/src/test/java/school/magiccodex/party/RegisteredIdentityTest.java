package school.magiccodex.party;
import java.io.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RegisteredIdentityTest {
 private byte[] packet(String original)throws Exception{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeByte(3);o.writeUTF("AccountFallback");o.writeByte(1);o.writeUTF(original);return b.toByteArray();}
 @Test void separatesOriginalNicknameFromGeneralChatDisplayFallback()throws Exception{var v=NetworkChat.decode(packet("황준우"));assertEquals("AccountFallback",v.nickname());assertEquals("황준우",v.registeredNickname());assertEquals("",NetworkChat.decode(packet("")).registeredNickname());}
 @Test void rejectsForgedFormattingEvenInOriginalNickname()throws Exception{assertThrows(IOException.class,()->NetworkChat.decode(packet("bad\nname")));}
}
