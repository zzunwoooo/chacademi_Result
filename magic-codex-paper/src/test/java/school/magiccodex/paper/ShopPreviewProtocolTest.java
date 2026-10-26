package school.magiccodex.paper;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.ShopProtocol;
class ShopPreviewProtocolTest {
 private ShopProtocol.Response response(byte[] bytes,String receipt){return new ShopProtocol.Response(12,34,"general","상점","elena-neutral",2,"100","",List.of(new ShopProtocol.Product(UUID.randomUUID().toString(),"긴 커스텀 아이템", "10","5",3,bytes)),receipt);}
 @Test void preservesFullPreviewBeyondOldOneKilobyteLimitAndCompletedReceipt(){byte[] bytes=new byte[32768];new Random(17).nextBytes(bytes);String receipt=UUID.randomUUID().toString();var r=ShopProtocol.response(ShopProtocol.encode(response(bytes,receipt)));assertArrayEquals(bytes,r.products().getFirst().preview());assertEquals(receipt,r.completedOperation());}
 @Test void rejectsOversizePreviewAndPacket(){assertThrows(IllegalArgumentException.class,()->ShopProtocol.response(ShopProtocol.encode(response(new byte[32769],""))));assertThrows(IllegalArgumentException.class,()->ShopProtocol.encode(response(new byte[60000],"")));}
 @Test void failureSnapshotHasNoCompletedReceipt(){assertEquals("",ShopProtocol.response(ShopProtocol.encode(response(new byte[0],""))).completedOperation());}
 @Test void invalidOrTrailingCompletionReceiptIsRejected(){assertThrows(IllegalArgumentException.class,()->ShopProtocol.response(ShopProtocol.encode(response(new byte[0],"invalid"))));byte[] packet=ShopProtocol.encode(response(new byte[0],""));assertThrows(IllegalArgumentException.class,()->ShopProtocol.response(Arrays.copyOf(packet,packet.length+1)));}
}