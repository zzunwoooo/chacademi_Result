package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import java.util.List;
import school.magiccodex.protocol.ShopProtocol;
import static org.junit.jupiter.api.Assertions.*;
class ShopNpcNameProtocolTest {
 @Test void npcNameSurvivesWire(){var r=new ShopProtocol.Response(1,2,"test","상점","elena-neutral",3,"100","",List.of(),"","엘레나");assertEquals(r,ShopProtocol.response(ShopProtocol.encode(r)));}
 @Test void commandOpenedShopHasEmptyNpc(){var r=new ShopProtocol.Response(1,2,"test","상점","elena-neutral",3,"100","",List.of());assertEquals("",ShopProtocol.response(ShopProtocol.encode(r)).npcName());}
 @Test void roleSurvivesWireAndOldPacketsReadAsEmpty(){var r=new ShopProtocol.Response(1,2,"pipi","피피의 잡화점","pipi-neutral",3,"100","",List.of(),"","피피","잡화상인");assertEquals(r,ShopProtocol.response(ShopProtocol.encode(r)));
  byte[] full=ShopProtocol.encode(new ShopProtocol.Response(1,2,"t","상점","p",3,"1","",List.of(),"","n",""));byte[] old=java.util.Arrays.copyOf(full,full.length-2);assertEquals("",ShopProtocol.response(old).role());}
}
