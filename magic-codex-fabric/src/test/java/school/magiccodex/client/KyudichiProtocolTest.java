package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.KyudichiProtocol;
import school.magiccodex.protocol.KyudichiProtocol.*;

/** Locks the 큐디치 wire format (same GOLDEN strings in kyudichi-paper KyudichiWireTest). */
class KyudichiProtocolTest {
    static final UUID A = new UUID(1, 2), B = new UUID(3, 4);
    static final List<Message> SAMPLES = List.of(
            new Match(2, 0, 30, 20, 372000, 1, "경기장1", List.of(new Person(A, "준우", 0, 2), new Person(B, "서하", 1, 1))),
            new Key(3, 400, 0),
            new Balls(List.of(new Ball(0, 0, 1.5f, 64f, -3.25f, 1), new Ball(1, 1, 0f, 70f, 2f, 2))),
            new Feed(2, 1, "서하", "준우", 3),
            new Ping(9, A, "준우", 10f, 80f, 10f, B),
            new Result(1, 80, 50, 18, 1268, 1, 2, List.of(new Line(A, "준우", 0, 3, 2, 0, 1, true))),
            new Shrines(List.of(new Shrine(3, 1f, 2f, 3f))),
            new Roster(List.of(new Entry(A, 2, "starlight", 1250), new Entry(B, -1, "", 1000))),
            new Bounds(-40f, 60f, -80f, 40f, 120f, 80f));

    @Test void roundTrip() {
        for (Message m : SAMPLES) assertEquals(m, KyudichiProtocol.decode(KyudichiProtocol.encode(m)));
        for (Request r : List.of(new PingRequest(2, 1f, 2f, 3f, null), new PingRequest(13, 0f, 0f, 0f, A), new Forfeit(true), new Ready(false), new Aim(1.5f, 70f, -2f)))
            assertEquals(r, KyudichiProtocol.decodeRequest(KyudichiProtocol.encode(r)));
    }

    /** Same strings in kyudichi-paper KyudichiWireTest: both sides must produce/accept exactly these bytes. */
    static final String GOLDEN_MATCH = "01010200001e00140005ad2001000aeab2bdeab8b0ec9ea53102000000000000000100000000000000020006eca480ec9ab00002000000000000000300000000000000040006ec849ced95980101";
    static final String GOLDEN_PING = "010509000000000000000100000000000000020006eca480ec9ab04120000042a00000412000000100000000000000030000000000000004";
    static final String GOLDEN_PING_REQUEST = "01010d0000000000000000000000000100000000000000010000000000000002";

    @Test void golden() {
        var hex = HexFormat.of();
        assertEquals(SAMPLES.get(0), KyudichiProtocol.decode(hex.parseHex(GOLDEN_MATCH)));
        assertEquals(GOLDEN_MATCH, hex.formatHex(KyudichiProtocol.encode(SAMPLES.get(0))));
        assertEquals(GOLDEN_PING, hex.formatHex(KyudichiProtocol.encode(SAMPLES.get(4))));
        assertEquals(new PingRequest(13, 0f, 0f, 0f, A), KyudichiProtocol.decodeRequest(hex.parseHex(GOLDEN_PING_REQUEST)));
    }

    @Test void rejectsBadInput() {
        byte[] key = KyudichiProtocol.encode(new Key(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.decode(java.util.Arrays.copyOf(key, key.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.decode(java.util.Arrays.copyOf(key, key.length + 1)));
        byte[] v2 = key.clone(); v2[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.decode(v2));
        byte[] badAction = key.clone(); badAction[2] = 9;
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.decode(badAction));
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.decodeRequest(new byte[]{1, 9}));
        assertThrows(IllegalArgumentException.class, () -> KyudichiProtocol.encode(new Balls(List.of(new Ball(5, 0, 0, 0, 0, 0)))));
    }
}
