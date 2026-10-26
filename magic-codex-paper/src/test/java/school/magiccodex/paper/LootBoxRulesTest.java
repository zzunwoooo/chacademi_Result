package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LootBoxRulesTest {
    @Test void gradeNames() {
        assertEquals(0, LootBoxRules.grade("일반")); assertEquals(1, LootBoxRules.grade("고급"));
        assertEquals(2, LootBoxRules.grade("unique")); assertEquals(3, LootBoxRules.grade(" 전설 "));
        assertEquals(-1, LootBoxRules.grade("희귀"));
    }

    @Test void drawFollowsWeightsAndSkipsZero() {
        int[] w = {0, 1, 3};
        var random = new SplittableRandom(7);
        int[] hit = new int[3];
        for (int i = 0; i < 40000; i++) hit[LootBoxRules.draw(w, random)]++;
        assertEquals(0, hit[0]);
        assertEquals(0.25, hit[1] / 40000.0, 0.02);
        assertEquals(-1, LootBoxRules.draw(new int[]{0, 0}, random));
    }

    @Test void drawManyIsExactCount() {
        var out = LootBoxRules.drawMany(new int[]{1, 1}, 10, new SplittableRandom(1));
        assertEquals(10, out.length);
        assertNull(LootBoxRules.drawMany(new int[]{0}, 10, new SplittableRandom(1)));
    }

    @Test void uniqueRewardsSkipOwnedAndNeverRepeatInOneOpening() {
        int[] w = {10, 10, 10, 5};
        String[] u = {"bed", "sofa", "lamp", null};
        var random = new SplittableRandom(3);
        for (int t = 0; t < 2000; t++) {
            var out = LootBoxRules.drawUnique(w, u, Set.of("bed"), 3, random);
            assertNotNull(out);
            var seen = new HashSet<String>();
            for (int i : out) { assertNotEquals(0, i); if (u[i] != null) assertTrue(seen.add(u[i])); }
        }
        // 가질 수 있는 가구 2개 + 일반 보상 0 → 3개는 못 뽑음
        assertNull(LootBoxRules.drawUnique(new int[]{1, 1, 1}, new String[]{"a", "b", "c"}, Set.of("a"), 3, random));
        assertEquals(2, LootBoxRules.uniqueLeft(new int[]{1, 1, 1}, new String[]{"a", "b", "c"}, Set.of("a")));
        // 모두 가졌으면 하나도 못 뽑음
        assertNull(LootBoxRules.drawUnique(new int[]{1}, new String[]{"a"}, Set.of("a"), 1, random));
    }

    @Test void representativeIsHighestGrade() {
        assertEquals(3, LootBoxRules.representative(new int[]{0, 0, 3, 1}));
        assertEquals(0, LootBoxRules.representative(new int[]{0}));
    }

    @Test void percentText() {
        assertEquals("25%", LootBoxRules.percent(1, 4));
        assertEquals("33.33%", LootBoxRules.percent(1, 3));
        assertEquals("0%", LootBoxRules.percent(0, 3));
    }

    @Test void idsAreUniqueAndSafe() {
        assertEquals("api", LootBoxRules.id("API 상자", Set.of()));
        assertEquals("box", LootBoxRules.id("상자", Set.of()));
        assertEquals("box_2", LootBoxRules.id("상자", Set.of("box")));
        assertEquals("api_box", LootBoxRules.id("API box", Set.of()));
    }
}
