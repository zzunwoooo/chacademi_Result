package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingProtocol.*;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;

/** Locks the 하우징 wire format (same GOLDEN strings in housing-paper HousingWireTest). */
class HousingProtocolTest {
    static final UUID A = new UUID(1, 2), B = new UUID(3, 4);
    static final Person OWNER = new Person(A, "준우", 1, "", false);
    static final Layout LAYOUT = new Layout("wall_star", "", List.of(new Placement("bed", 1, 0, 0, 1), new Placement("frame", 2, 1, 0, 0)));
    static final List<Message> SAMPLES = List.of(
            new RoomMessage(new Room(HousingProtocol.F_OPEN | HousingProtocol.F_EDIT, OWNER, 3, 4, 5000, 12, 2, 40, 0,
                    Map.of(0, LAYOUT), Map.of("bed", 1, "frame", 2, "wall_star", 1))),
            new Viewers(A, 3, List.of(new Viewer(B, "서하"))),
            new Guestbook(A, List.of(new Entry(7, new Person(B, "서하", 0, "0123456789abcdef0123456789abcdef", true), 1_700_000_000_000L, "방 예쁘다!"))),
            new FriendList(List.of(new Friend(new Person(B, "서하", 0, "", false), true, 5))),
            new Result(true, "저장했어요."),
            new Catalog(3, List.of(new Def("bed", "아카데미 침대", 0, 0, 2, 1, 6, 0xFF1F2F5A, 0xFFE0B860, "", 1))));

    @Test void roundTrip() {
        for (Message m : SAMPLES) assertEquals(m, HousingProtocol.decode(HousingProtocol.encode(m)));
        for (Request r : List.of(new Visit(A), new Close(), new Save(2, LAYOUT), new Expand(3), new Like(B), new Post(A, "안녕"), new Delete(A, 99), new Friends()))
            assertEquals(r, HousingProtocol.decodeRequest(HousingProtocol.encode(r)));
    }

    /** #108 전시 값 (빗자루 거치대·아티팩트 진열장). */
    @Test void showcase() {
        var s = new Showcase(A, "chacus_2000_angel", "차큐스 2000 (엔젤)", List.of("lucky_coin", "wind_charm"));
        assertEquals(s, HousingProtocol.decode(HousingProtocol.encode(s)));
        var empty = new Showcase(A, null, null, List.of());
        assertEquals(new Showcase(A, "", "", List.of()), HousingProtocol.decode(HousingProtocol.encode(empty)));
        assertThrows(IllegalArgumentException.class, () -> new Showcase(A, "", "", java.util.Collections.nCopies(HousingProtocol.MAX_SHOWCASE_ARTIFACTS + 1, "x")));
        assertThrows(IllegalArgumentException.class, () -> new Showcase(A, "", "", List.of(" ")));
    }

    /** Same strings in housing-paper HousingWireTest: both sides must produce/accept exactly these bytes. */
    static final String GOLDEN_ROOM = "010103000000000000000100000000000000020006eca480ec9ab001000000030400000000000013880000000c0000000200000028000100000977616c6c5f7374617200000200036265640100000100056672616d650201000000030003626564000100056672616d650002000977616c6c5f737461720001";
    static final String GOLDEN_SAVE = "010302000977616c6c5f7374617200000200036265640100000100056672616d6502010000";

    @Test void golden() {
        var hex = HexFormat.of();
        assertEquals(GOLDEN_ROOM, hex.formatHex(HousingProtocol.encode(SAMPLES.get(0))));
        assertEquals(SAMPLES.get(0), HousingProtocol.decode(hex.parseHex(GOLDEN_ROOM)));
        assertEquals(new Save(2, LAYOUT), HousingProtocol.decodeRequest(hex.parseHex(GOLDEN_SAVE)));
    }

    @Test void rejectsBadPackets() {
        byte[] ok = HousingProtocol.encode(new Visit(A));
        assertThrows(IllegalArgumentException.class, () -> HousingProtocol.decodeRequest(new byte[] {1}));
        byte[] trailing = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> HousingProtocol.decodeRequest(trailing));
        byte[] version = ok.clone(); version[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> HousingProtocol.decodeRequest(version));
        assertThrows(IllegalArgumentException.class, () -> HousingProtocol.decodeRequest(new byte[] {1, 42}));
        String tooLong = "가".repeat(HousingProtocol.GUESTBOOK_CHARS + 1);
        byte[] post = HousingProtocol.encode(new Post(A, tooLong));
        assertThrows(IllegalArgumentException.class, () -> HousingProtocol.decodeRequest(post));
    }

    @Test void cleanText() {
        assertEquals("안녕 하세요", HousingProtocol.cleanText("  안녕\n\n 하세요\u0000 "));
        assertEquals("", HousingProtocol.cleanText(" \t "));
        assertEquals(HousingProtocol.GUESTBOOK_CHARS, HousingProtocol.cleanText("가".repeat(99)).length());
    }
}
