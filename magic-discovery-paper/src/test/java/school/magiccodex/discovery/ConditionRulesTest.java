package school.magiccodex.discovery;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 2026-10-26 발견 조건 v2 의 순수 규칙. */
class ConditionRulesTest {
    @Test void learnedKeysAreOnlyTheSixElementsAndAll() {
        assertTrue(ConditionRules.learnedKey("learned.fire"));assertTrue(ConditionRules.learnedKey("learned.all"));
        assertFalse(ConditionRules.learnedKey("learned.lightning"));assertFalse(ConditionRules.learnedKey("cast.fire"));assertFalse(ConditionRules.learnedKey(null));
        assertTrue(ConditionRules.stateLike("learned.dark"));assertTrue(ConditionRules.stateLike("state.friends"));assertFalse(ConditionRules.stateLike("kill.element.fire"));
    }
    @Test void learnedCountsGroupByElementAndTotal() {
        var cats = new LinkedHashMap<String, String>();
        cats.put("ember_bolt", "fire"); cats.put("rekindle", "fire"); cats.put("wind_push", "wind"); cats.put("odd", "other"); cats.put("falling_star", "fire");
        var learned = Set.of("ember_bolt", "rekindle", "wind_push", "odd");
        var c = ConditionRules.learnedCounts(cats, learned::contains);
        assertEquals(2d, c.get("learned.fire")); assertEquals(1d, c.get("learned.wind")); assertEquals(0d, c.get("learned.dark")); assertEquals(4d, c.get("learned.all"));
    }
    @Test void nightBiomesSkyAndLightningDistance() {
        assertFalse(ConditionRules.night(6000)); assertTrue(ConditionRules.night(13000)); assertTrue(ConditionRules.night(23000)); assertFalse(ConditionRules.night(23001)); assertTrue(ConditionRules.night(24000 + 18000));
        assertTrue(ConditionRules.desert("desert")); assertFalse(ConditionRules.desert("badlands"));
        assertTrue(ConditionRules.swamp("mangrove_swamp")); assertTrue(ConditionRules.swamp("swamp"));
        assertTrue(ConditionRules.deepOcean("deep_cold_ocean")); assertFalse(ConditionRules.deepOcean("ocean")); assertFalse(ConditionRules.deepOcean("deep_dark"));
        assertTrue(ConditionRules.underSky(70, 70)); assertFalse(ConditionRules.underSky(40, 70));
        assertTrue(ConditionRules.nearLightning(32 * 32)); assertFalse(ConditionRules.nearLightning(32 * 32 + 1));
    }
    @Test void wholeBlocksKeepFractionsAndDropTeleports() {
        double[] acc = new double[1];
        assertEquals(0, ConditionRules.whole(acc, 0.6)); assertEquals(1, ConditionRules.whole(acc, 0.6)); assertEquals(0.2, acc[0], 1e-9);
        assertEquals(0, ConditionRules.whole(acc, 50)); assertEquals(0, ConditionRules.whole(acc, -1)); assertEquals(0, ConditionRules.whole(acc, Double.NaN));
    }
    @Test void cooldownCountsOncePerGap() {
        var last = new HashMap<UUID, Integer>(); var id = UUID.randomUUID();
        assertTrue(ConditionRules.cooled(last, id, 100, 60)); assertFalse(ConditionRules.cooled(last, id, 159, 60)); assertTrue(ConditionRules.cooled(last, id, 160, 60));
    }
    @Test void replantOnlySameSpotWithinWindowAndOnce() {
        var r = new ConditionRules.Replant(); var a = UUID.randomUUID(); var b = UUID.randomUUID();
        long at = ConditionRules.Replant.key(10, 64, -5), other = ConditionRules.Replant.key(11, 64, -5);
        assertNotEquals(at, other); assertNotEquals(ConditionRules.Replant.key(10, 65, -5), at); assertNotEquals(ConditionRules.Replant.key(-10, 64, 5), ConditionRules.Replant.key(10, 64, -5));
        r.harvested(a, at, 0);
        assertFalse(r.replanted(b, at, 10)); assertFalse(r.replanted(a, other, 10)); assertTrue(r.replanted(a, at, 10)); assertFalse(r.replanted(a, at, 11));
        r.harvested(a, at, 0); assertFalse(r.replanted(a, at, ConditionRules.Replant.WINDOW + 1));
        for (int i = 0; i < 100; i++) r.harvested(a, ConditionRules.Replant.key(i, 1, 1), i);
        assertEquals(ConditionRules.Replant.LIMIT, r.size()); r.quit(a); assertEquals(0, r.size());
    }
}
