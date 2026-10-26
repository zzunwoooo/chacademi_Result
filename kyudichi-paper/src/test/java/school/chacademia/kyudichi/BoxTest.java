package school.chacademia.kyudichi;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class BoxTest {
    @Test void blockCornersAreInclusive() {
        Box b = new Box("w", 5, 10, 5, 0, 0, 0);
        assertTrue(b.contains(0, 0, 0)); assertTrue(b.contains(5.99, 10.99, 5.99)); assertFalse(b.contains(6, 5, 5));
        assertEquals("6×11×6", b.size());
        assertEquals(b, Box.parse(b.serialize()));
    }

    @Test void fastBallCannotSkipThinGoal() {
        Box goal = new Box("w", 0, 0, 0, 0, 3, 3);                 // 1 block thick ring plane
        assertTrue(goal.segmentHits(new Vector(-5, 1, 1), new Vector(5, 1, 1)));
        assertFalse(goal.segmentHits(new Vector(-5, 1, 1), new Vector(-1, 1, 1)));
        assertFalse(goal.segmentHits(new Vector(-5, 9, 1), new Vector(5, 9, 1)));
    }

    @Test void clampInside() {
        Box b = new Box("w", 0, 0, 0, 9, 9, 9);
        assertNull(b.clampInside(new Vector(5, 5, 5), 0.5));
        assertEquals(new Vector(9.5, 5, 5), b.clampInside(new Vector(12, 5, 5), 0.5));
    }

    @Test void durations() {
        assertEquals(3_600_000L, KyudichiCommands.duration("1h"));
        assertEquals(86_400_000L + 1_800_000L, KyudichiCommands.duration("1d30m"));
        assertEquals(1_800_000L, KyudichiCommands.duration("30분"));
        assertEquals(3_600_000L, KyudichiCommands.duration("1시간"));
        assertEquals(2 * 86_400_000L + 3 * 3_600_000L, KyudichiCommands.duration("2일3시간"));
        assertThrows(IllegalArgumentException.class, () -> KyudichiCommands.duration("soon"));
        assertThrows(IllegalArgumentException.class, () -> KyudichiCommands.duration("1주"));
    }

    @Test void pingLimit() {
        var p = new Pings(); var id = java.util.UUID.randomUUID();
        assertTrue(p.allow(id, 0)); assertTrue(p.allow(id, 100)); assertTrue(p.allow(id, 200)); assertFalse(p.allow(id, 300));
        assertTrue(p.allow(id, 5001));
    }
}
