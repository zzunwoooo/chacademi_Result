package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MoneyPouchRulesTest {
    /** utility-items.yml 기본 표와 같은 값. */
    private static final List<Map<?, ?>> DEFAULT = List.of(
            Map.of("name", "동전 몇 닢", "weight", 45, "min", 250, "max", 750),
            Map.of("name", "묵직한 동전", "weight", 30, "min", 750, "max", 2000),
            Map.of("name", "은화 한 줌", "weight", 17, "min", 2000, "max", 5000),
            Map.of("name", "금화 주머니", "weight", 6.5, "min", 5000, "max", 10000),
            Map.of("name", "대박! 30분 노동치", "weight", 1.5, "min", 15000, "max", 15000));

    @Test void defaultTableAverageIsAbout1945() {
        var tiers = MoneyPouchRules.parse(DEFAULT);
        assertEquals(5, tiers.size());
        assertEquals(1945, MoneyPouchRules.expected(tiers), 0.5);
    }

    @Test void rollsStayInsideTheirTierAndRoundDown() {
        var tiers = MoneyPouchRules.parse(DEFAULT);
        var random = new SplittableRandom(7);
        int jackpots = 0;
        for (int i = 0; i < 20_000; i++) {
            var r = MoneyPouchRules.roll(tiers, 10, random);
            assertTrue(r.amount() >= r.tier().min() && r.amount() <= r.tier().max(), r.toString());
            assertEquals(0, r.amount() % 10, r.toString());
            if (r.tier().name().startsWith("대박")) { jackpots++; assertEquals(15000, r.amount()); }
        }
        assertTrue(jackpots > 150 && jackpots < 450, "대박 약 1.5% : " + jackpots);
    }

    @Test void roundingNeverGoesBelowTierMinimum() {
        assertEquals(100, MoneyPouchRules.rounded(105, 10, 100));
        assertEquals(105, MoneyPouchRules.rounded(105, 1, 100));
        assertEquals(1000, MoneyPouchRules.rounded(1009, 10, 800));
        assertEquals(15, MoneyPouchRules.rounded(14, 10, 15));
    }

    @Test void badRowsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MoneyPouchRules.parse(List.of()));
        assertThrows(IllegalArgumentException.class, () -> MoneyPouchRules.parse(List.of(Map.of("name", "x", "weight", 0, "min", 1, "max", 2))));
        assertThrows(IllegalArgumentException.class, () -> MoneyPouchRules.parse(List.of(Map.of("name", "x", "weight", 1, "min", 5, "max", 2))));
        assertThrows(IllegalArgumentException.class, () -> MoneyPouchRules.parse(List.of(Map.of("name", "", "weight", 1, "min", 1, "max", 2))));
        assertThrows(IllegalArgumentException.class, () -> MoneyPouchRules.parse(List.of(Map.of("name", "x", "weight", "많이", "min", 1, "max", 2))));
    }
}
