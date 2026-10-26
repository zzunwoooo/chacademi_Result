package school.chacademia.magicmythic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TrainingDummyProtocol;
import school.magiccodex.protocol.TrainingDummyProtocol.Kind;

/** #107: 허수아비 누적(10초 안 맞으면 0)·초당 피해, 숫자 모양 구분, 패킷·숫자 형식, 위치 줄 저장 형식. */
class TrainingDummyTest {
    @Test void streakAddsUpAndResetsAfterQuietTime() {
        var s = TrainingDummies.Streak.NONE.add(0, 4, 10_000).add(3000, 6, 10_000).add(4000, 10, 10_000);
        assertEquals(20, s.total(), 1e-9);
        assertEquals(5, s.dps(), 1e-9);                        // 20 over 4 s
        assertSame(s, s.expire(14_000, 10_000));               // exactly 10 s: still there
        assertTrue(s.expire(14_001, 10_000).empty());
        var again = s.add(14_500, 7, 10_000);                  // quiet for more than 10 s: starts from 0
        assertEquals(7, again.total(), 1e-9); assertEquals(14_500, again.start());
    }
    @Test void singleHitCountsAsOneSecond() {
        var s = TrainingDummies.Streak.NONE.add(1000, 7, 10_000);
        assertEquals(7, s.dps(), 1e-9);
        assertEquals(0, TrainingDummies.Streak.NONE.dps(), 1e-9);
    }
    @Test void kindOrder() {
        assertEquals(Kind.REACTION, TrainingDummies.kind(true, true, 50, 20));
        assertEquals(Kind.DOT, TrainingDummies.kind(false, false, 50, 20));
        assertEquals(Kind.HEAVY, TrainingDummies.kind(false, true, 20, 20));
        assertEquals(Kind.NORMAL, TrainingDummies.kind(false, true, 19.9, 20));
    }
    @Test void packetRoundTripsAndRejectsJunk() {
        var h = new TrainingDummyProtocol.Hit(42, 1.5, 66.35, -3.5, 12.5f, Kind.HEAVY, 1234.5f, 88.2f, 200);
        byte[] raw = TrainingDummyProtocol.encode(h);
        assertTrue(raw.length <= TrainingDummyProtocol.MAX_BYTES);
        assertEquals(h, TrainingDummyProtocol.decode(raw));
        raw[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> TrainingDummyProtocol.decode(raw));
        assertThrows(IllegalArgumentException.class, () -> TrainingDummyProtocol.decode(new byte[3]));
        var odd = new TrainingDummyProtocol.Hit(1, 0, 0, 0, Float.NaN, Kind.DOT, -5, Float.POSITIVE_INFINITY, 1);
        assertEquals(0, odd.amount()); assertEquals(0, odd.total()); assertEquals(0, odd.dps()); assertEquals(20, odd.resetTicks());
    }
    @Test void numberFormat() {
        assertEquals("3.5", TrainingDummyProtocol.format(3.46));
        assertEquals("3", TrainingDummyProtocol.format(3.0));
        assertEquals("10", TrainingDummyProtocol.format(9.96));
        assertEquals("1,234", TrainingDummyProtocol.format(1234.4));
        assertEquals("1,000,000", TrainingDummyProtocol.format(999_999.6));
        assertEquals("0", TrainingDummyProtocol.format(-1));
        assertEquals("0", TrainingDummyProtocol.format(Double.NaN));
    }
    @Test void spotLineRoundTrips() {
        var s = new TrainingDummies.Spot("world", 1.5, 64, -3.5, 90);
        assertEquals(s, TrainingDummies.Spot.parse(s.line()));
        assertNull(TrainingDummies.Spot.parse("world;1;2"));
        assertNull(TrainingDummies.Spot.parse("world;a;2;3;0"));
    }
}
