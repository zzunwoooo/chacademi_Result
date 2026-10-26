package school.magiccodex.paper;

import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.BagProtocol;
import static org.junit.jupiter.api.Assertions.*;

class BagPagesTest {
    private static List<byte[]> slots() {
        var out = new ArrayList<byte[]>();
        for (int i = 0; i < 27; i++) out.add(i % 5 == 0 ? new byte[]{(byte) i, 7, 7} : new byte[0]);
        return out;
    }

    @Test void pageRoundTripKeepsEverySlot() {
        byte[] page = BagPageCodec.encodePage(slots());
        var back = BagPageCodec.decodePage(page);
        assertEquals(27, back.size());
        for (int i = 0; i < 27; i++) assertArrayEquals(slots().get(i), back.get(i));
        assertEquals(6, BagPageCodec.itemCount(page));
        assertFalse(BagPageCodec.isEmpty(page));
        assertTrue(BagPageCodec.isEmpty(BagPageCodec.emptyPage()));
        assertEquals(27, BagPageCodec.decodePage(new byte[0]).size());
    }

    @Test void damagedPagesAreRefusedNotGuessed() {
        byte[] page = BagPageCodec.encodePage(slots());
        assertThrows(IllegalArgumentException.class, () -> BagPageCodec.decodePage(Arrays.copyOf(page, page.length - 1)));
        byte[] longer = Arrays.copyOf(page, page.length + 1);
        assertThrows(IllegalArgumentException.class, () -> BagPageCodec.decodePage(longer));
        assertThrows(IllegalArgumentException.class, () -> BagPageCodec.encodePage(List.of()));
    }

    @Test void journalKeepsOnlyTheNewestAndAcknowledgesExactly() throws Exception {
        var dir = Files.createTempDirectory("bag-journal");
        var j = new BagPageCodec.Journal(dir);
        UUID id = UUID.randomUUID();
        byte[][] pages = {BagPageCodec.encodePage(slots()), new byte[0], new byte[0]};
        j.save(id, "school", 2, 10, pages);
        j.save(id, "school", 3, 11, pages);
        j.save(id, "wild", 1, 5, pages);
        assertEquals(2, j.pending().size());
        var e = j.read(id, "school");
        assertEquals(3, e.active());
        assertEquals(11, e.seq());
        assertArrayEquals(pages[0], e.pages()[0]);
        j.acknowledge(id, "school", 10);          // older save finished late: newer record stays
        assertNotNull(j.read(id, "school"));
        j.acknowledge(id, "school", 11);
        assertNull(j.read(id, "school"));
        assertEquals(1, j.pending().size());
    }

    @Test void protocolOnlyAcceptsRealPages() {
        var r = BagProtocol.decodeRequest(BagProtocol.encode(new BagProtocol.Request(BagProtocol.SWITCH, 3)));
        assertEquals(3, r.page());
        assertThrows(IllegalArgumentException.class, () -> new BagProtocol.Request(BagProtocol.SWITCH, 4));
        assertThrows(IllegalArgumentException.class, () -> new BagProtocol.Request(BagProtocol.SWITCH, 0));
        assertThrows(IllegalArgumentException.class, () -> BagProtocol.decodeRequest(new byte[]{1, 1, 9}));
        assertThrows(IllegalArgumentException.class, () -> BagProtocol.decodeRequest(new byte[]{1, 1, 2, 0}));
        var s = BagProtocol.decodeState(BagProtocol.encode(new BagProtocol.State(2, 1, true, "")));
        assertEquals(2, s.unlocked());
        assertTrue(s.ready());
    }
}
