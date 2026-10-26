package school.magiccodex.paper;

import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HouseBoardProtocol;
import static org.junit.jupiter.api.Assertions.*;

/** 기숙사 점수 홀로그램 (메모 #106): 패킷 왕복·순위·방향 규칙. */
class HouseBoardProtocolTest {
    @Test void roundTrip() {
        var s = new HouseBoardProtocol.State("world", 48, List.of(120L, -5L, 3_000L, 0L),
                List.of(new HouseBoardProtocol.Board(10.5, 72.4, -3.5, HouseBoardProtocol.NORTH), new HouseBoardProtocol.Board(0, 64, 0, HouseBoardProtocol.EAST)));
        assertEquals(s, HouseBoardProtocol.decode(HouseBoardProtocol.encode(s)));
    }
    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> HouseBoardProtocol.decode(new byte[]{9, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new HouseBoardProtocol.Board(0, 0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new HouseBoardProtocol.State("w", 200, List.of(0L, 0L, 0L, 0L), List.of()));
        byte[] ok = HouseBoardProtocol.encode(HouseBoardProtocol.State.EMPTY);
        byte[] longer = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> HouseBoardProtocol.decode(longer));
    }
    @Test void orderAndRanks() {
        var scores = List.of(50L, 80L, 50L, 10L);
        assertArrayEquals(new int[]{1, 0, 2, 3}, HouseBoardProtocol.order(scores));   // 같은 점수는 기숙사 번호 순
        assertArrayEquals(new int[]{2, 1, 2, 4}, HouseBoardProtocol.ranks(scores));   // 같은 점수 같은 순위, 다음은 건너뜀
        assertArrayEquals(new int[]{1, 0, 2, 3}, HouseBoardProtocol.slots(scores));
        assertArrayEquals(new int[]{1, 1, 1, 1}, HouseBoardProtocol.ranks(List.of(0L, 0L, 0L, 0L)));
    }
    @Test void facing() {
        assertEquals(HouseBoardProtocol.SOUTH, HouseBoardProtocol.facingOf(0f));
        assertEquals(HouseBoardProtocol.WEST, HouseBoardProtocol.facingOf(80f));
        assertEquals(HouseBoardProtocol.NORTH, HouseBoardProtocol.facingOf(-170f));
        assertEquals(HouseBoardProtocol.EAST, HouseBoardProtocol.facingOf(-100f));
        assertEquals(HouseBoardProtocol.SOUTH, HouseBoardProtocol.facingOf(359f));
        assertEquals(HouseBoardProtocol.EAST, HouseBoardProtocol.facingOf("동"));
        assertEquals(HouseBoardProtocol.NORTH, HouseBoardProtocol.facingOf("north"));
        assertEquals(-1, HouseBoardProtocol.facingOf("위"));
        assertArrayEquals(new int[]{0, -1}, HouseBoardProtocol.normal(HouseBoardProtocol.NORTH));
    }
}
