package school.magiccodex.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingRankProtocol;
import school.magiccodex.protocol.HousingRankProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** #51 하우징 랭킹 채널: 왕복, 잘못된 값 거절, 숫자 표시. */
class HousingRankProtocolTest {
    @Test void roundTripsEveryMessage() {
        var q = new Query(3, HousingRankProtocol.HEARTS, 2);
        assertEquals(q, HousingRankProtocol.toServer(HousingRankProtocol.encode(q)));
        var v = new Visit(UUID.randomUUID());
        assertEquals(v, HousingRankProtocol.toServer(HousingRankProtocol.encode(v)));
        var rows = new ArrayList<Row>();
        for (int i = 0; i < 6; i++) rows.add(new Row(i < 2 ? 1 : i + 1, UUID.randomUUID(), "별빛학생" + i, 48 - i, 1284 - i, 215 - i, i % 3));
        var p = new Page(9, HousingRankProtocol.DAILY, 0, 8, rows, "");
        assertEquals(p, HousingRankProtocol.toClient(HousingRankProtocol.encode(p)));
        assertEquals(new Open(1), HousingRankProtocol.toClient(HousingRankProtocol.encode(new Open(1))));
        assertEquals(new Notice("친구의 방만 방문할 수 있어요."), HousingRankProtocol.toClient(HousingRankProtocol.encode(new Notice("친구의 방만 방문할 수 있어요."))));
    }

    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new Query(1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Page(1, 0, 2, 2, List.of(), ""));
        byte[] good = HousingRankProtocol.encode(new Query(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> HousingRankProtocol.toServer(Arrays.copyOf(good, good.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> HousingRankProtocol.toClient(new byte[]{9, 1}));
    }

    @Test void numbersUseThousandsSeparator() { assertEquals("1,284", HousingRankProtocol.number(1284)); assertEquals("0", HousingRankProtocol.number(0)); }
}
