package school.magiccodex.party;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import school.magiccodex.party.KyudichiMatchmaker.Entry;

class KyudichiMatchmakerTest {
    static Entry party(int rating, long since) { return new Entry(UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), rating, since); }
    static Entry solo(int rating, long since) { return new Entry(UUID.randomUUID(), List.of(UUID.randomUUID()), rating, since); }
    static Entry duo(int rating, long since) { return new Entry(UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID()), rating, since); }

    @Test void closePartiesMatchAtOnce() {
        var p = KyudichiMatchmaker.find(List.of(party(1000, 0), party(1080, 0)), 1000);
        assertNotNull(p);
    }

    @Test void rangeWidensWithWaiting() {
        var q = List.of(party(1000, 0), party(1200, 0));
        assertNull(KyudichiMatchmaker.find(q, 10_000));              // ±100
        assertNotNull(KyudichiMatchmaker.find(q, 35_000));           // ±250
        var far = List.of(party(1000, 0), party(1900, 0));
        assertNull(KyudichiMatchmaker.find(far, 50_000));
        assertNotNull(KyudichiMatchmaker.find(far, 61_000));          // 60 s: anyone
    }

    @Test void randomTeamsFromSolosAndDuos() {
        var q = new ArrayList<Entry>();
        q.add(solo(1000, 0)); q.add(duo(1010, 0)); q.add(solo(990, 0)); q.add(solo(1020, 0)); q.add(solo(1005, 0));
        var p = KyudichiMatchmaker.find(q, 1000);
        assertNotNull(p);
        assertEquals(3, p.a().members().size()); assertEquals(3, p.b().members().size());
        var all = new java.util.HashSet<UUID>(p.a().members()); all.addAll(p.b().members());
        assertEquals(6, all.size());
    }

    @Test void duelPairsTwoSolosByRating() {
        assertNull(KyudichiMatchmaker.find(List.of(solo(1000, 0)), 1000, 1));
        assertNull(KyudichiMatchmaker.find(List.of(solo(1000, 0), solo(1400, 0)), 1000, 1));   // ±100 at first
        var p = KyudichiMatchmaker.find(List.of(solo(1000, 0), solo(1400, 0), solo(1050, 0)), 1000, 1);
        assertNotNull(p);
        assertEquals(1, p.a().members().size()); assertEquals(1, p.b().members().size());
        assertTrue(Math.abs(p.a().rating() - p.b().rating()) <= 100);
    }

    @Test void premadeAndRandomOnlyMixAfterOneMinute() {
        var q = List.of(party(1000, 0), solo(1000, 0), solo(1000, 0), solo(1000, 0));
        assertNull(KyudichiMatchmaker.find(q, 20_000));
        assertNotNull(KyudichiMatchmaker.find(q, 61_000));
    }

    @Test void partyRatingIsHighest() {
        assertEquals(1300, KyudichiMatchmaker.Team.of(List.of(solo(1300, 0), duo(900, 0))).rating());
    }
}
