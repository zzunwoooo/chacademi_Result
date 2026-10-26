package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import school.magiccodex.protocol.ShinyProtocol;
import java.util.UUID;
class ShinyProtocolTest {
 @Test void roundTripTracksUuidAndEntityId(){for(boolean value:new boolean[]{false,true}){var s=new ShinyProtocol.State(421,UUID.randomUUID(),value);assertEquals(s,ShinyProtocol.decode(ShinyProtocol.encode(s)));}}
 @Test void rejectsWrongLengths(){for(int length:new int[]{0,20,22,32768})assertThrows(IllegalArgumentException.class,()->ShinyProtocol.decode(new byte[length]));}
 @Test void rejectsNonBooleanFlags(){byte[] bytes=ShinyProtocol.encode(new ShinyProtocol.State(1,UUID.randomUUID(),true));bytes[20]=2;assertThrows(IllegalArgumentException.class,()->ShinyProtocol.decode(bytes));}
}
