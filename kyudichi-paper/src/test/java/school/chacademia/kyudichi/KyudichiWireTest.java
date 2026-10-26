package school.chacademia.kyudichi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.KyudichiProtocol;

/** Same GOLDEN strings as magic-codex-fabric KyudichiProtocolTest (server and client compile the same source; this locks it). */
class KyudichiWireTest {
    @Test void pingRequestFromClient() {
        var r = KyudichiProtocol.decodeRequest(HexFormat.of().parseHex("01010d0000000000000000000000000100000000000000010000000000000002"));
        assertEquals(new KyudichiProtocol.PingRequest(13, 0f, 0f, 0f, new UUID(1, 2)), r);
    }
    @Test void pingToClient() {
        var m = new KyudichiProtocol.Ping(9, new UUID(1, 2), "준우", 10f, 80f, 10f, new UUID(3, 4));
        assertEquals("010509000000000000000100000000000000020006eca480ec9ab04120000042a00000412000000100000000000000030000000000000004",
                HexFormat.of().formatHex(KyudichiProtocol.encode(m)));
    }
}
