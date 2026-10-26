package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CombatLockRulesTest {
    @Test void labelStripsSlashNamespaceAndCase() {
        assertEquals("home", CombatLockRules.label("/HuskHomes:Home 집1"));
        assertEquals("집", CombatLockRules.label("/집"));
        assertEquals("tpa", CombatLockRules.label("/tpa Steve"));
        assertNull(CombatLockRules.label("/"));
        assertNull(CombatLockRules.label(null));
    }

    @Test void commandListIsNormalised() {
        var set = CombatLockRules.commandSet(List.of("/Spawn", "huskhomes:warp", " 집 ", ""));
        assertTrue(set.contains("spawn"));
        assertTrue(set.contains("warp"));
        assertTrue(set.contains("집"));
        assertEquals(3, set.size());
    }

    @Test void remainingInsideWindowOnly() {
        assertEquals(0, CombatLockRules.remaining(-1, 5_000, 10_000));
        assertEquals(7_000, CombatLockRules.remaining(2_000, 5_000, 10_000));
        assertEquals(0, CombatLockRules.remaining(2_000, 12_000, 10_000));
        // 시계가 뒤로 가도 창보다 길게 막지 않음
        assertEquals(10_000, CombatLockRules.remaining(9_000, 1_000, 10_000));
    }

    @Test void secondsRoundUp() {
        assertEquals(1, CombatLockRules.seconds(1));
        assertEquals(7, CombatLockRules.seconds(6_001));
        assertEquals("전투 중이라 이동할 수 없어요 · 10초 뒤 가능", CombatLockRules.message(10_000));
    }

    @Test void serversAndClamp() {
        var s = CombatLockRules.settings(true, 500, List.of("School"), List.of("home"), List.of("Companion_Return"), 99_999);
        assertEquals(120_000, s.windowMs());
        assertEquals(10_000, s.notifyCooldownMs());
        assertTrue(s.appliesTo("school"));
        assertFalse(s.appliesTo("arena"));
        assertTrue(s.appliesTo(""));
        assertTrue(s.spells().contains("companion_return"));
        assertTrue(CombatLockRules.settings(true, 10, List.of(), List.of(), List.of(), 0).appliesTo("arena"));
        assertFalse(CombatLockRules.settings(false, 10, List.of(), List.of(), List.of(), 0).appliesTo("school"));
    }

    @Test void defaultsCoverSchoolAndWild() {
        var d = CombatLockRules.Settings.DEFAULT;
        assertTrue(d.appliesTo("school") && d.appliesTo("wild1") && d.appliesTo("wild2"));
        assertEquals(10_000, d.windowMs());
        assertTrue(d.commands().contains("spawn") && d.commands().contains("home"));
    }
}
