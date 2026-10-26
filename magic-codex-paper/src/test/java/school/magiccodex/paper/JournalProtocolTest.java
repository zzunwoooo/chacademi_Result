package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import java.util.*;import school.magiccodex.protocol.JournalProtocol;import static org.junit.jupiter.api.Assertions.*;
class JournalProtocolTest {
 @Test void boundedUnicodeChunkRoundTrips(){var e=new JournalProtocol.Entry("story","ch1-3","title","\ud55c".repeat(2000),"active",true,"\ud55c".repeat(1200),"\ud55c".repeat(500));var m=new JournalProtocol.Message(1,"token","","","",0,2,List.of(e,e));var bytes=JournalProtocol.encode(m);assertTrue(bytes.length<30000);assertEquals(m,JournalProtocol.decode(bytes));}
 @Test void malformedFieldsAndTrailingBytesAreRejected(){assertThrows(IllegalArgumentException.class,()->new JournalProtocol.Entry("command","id","","","active",true,"",""));assertThrows(IllegalArgumentException.class,()->new JournalProtocol.Entry("story","../x","","","active",true,"",""));var m=new JournalProtocol.Message(0,"t","","","",0,0,List.of());byte[] good=JournalProtocol.encode(m);assertThrows(IllegalArgumentException.class,()->JournalProtocol.decode(Arrays.copyOf(good,good.length+1)));assertThrows(IllegalArgumentException.class,()->JournalProtocol.decode(new byte[30001]));}
}
