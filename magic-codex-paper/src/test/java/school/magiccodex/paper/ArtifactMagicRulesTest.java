package school.magiccodex.paper;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 그룹 C 아티팩트의 순수 계산 (2026-10-26): 무지개 프리즘 6원소 판정, 거꾸로 된 마나병 과열 게이지. */
class ArtifactMagicRulesTest {
    @Test void rainbowNeedsSixSlotsWithSixDifferentElements() {
        var el = Map.of("a", "fire", "b", "water", "c", "wind", "d", "earth", "e", "light", "f", "dark", "g", "fire");
        assertTrue(ArtifactMagicRules.rainbow(List.of("a", "b", "c", "d", "e", "f"), el));
        assertFalse(ArtifactMagicRules.rainbow(List.of("a", "b", "c", "d", "e"), el));
        assertFalse(ArtifactMagicRules.rainbow(List.of("a", "b", "c", "d", "e", "g"), el));
        assertFalse(ArtifactMagicRules.rainbow(List.of("a", "b", "c", "d", "e", "zz"), el));
    }

    @Test void heatGaugeFillsCoolsLocksAndFollowsManaFraction() {
        var a = new ManaAccount(100, 100, 5);           // 재생 5 -> 과열 최대 100
        a.heatMode(true);
        assertEquals(100, a.snapshot().maximum());
        assertEquals(100, a.snapshot().current());     // 마나 가득 = 과열 0
        assertTrue(a.consume(60));
        assertEquals(40, a.snapshot().current());
        assertEquals(40, a.current, 1e-9);             // 보통 마나도 같은 비율
        a.regenerate();                                // 초당 5 식음
        assertEquals(45, a.snapshot().current(), 1e-9);
        assertTrue(a.consume(50));                     // 가득 참 -> 잠김
        assertTrue(a.heatLocked(System.currentTimeMillis()));
        assertFalse(a.consume(1));
        a.heatMode(false);
        assertEquals(0, a.current, 1e-9);              // 과열 상태로 해제하면 마나 0 (해제로 마나 채우기 없음)
    }

    @Test void refundedFailedCastLiftsTheLockItCaused() {
        var a = new ManaAccount(10, 100, 5);
        a.heatMode(true);                              // 마나 10% = 과열 90
        assertTrue(a.consume(20));
        assertTrue(a.heatLocked(System.currentTimeMillis()));
        a.add(20);                                     // ManaCasting 환불
        assertFalse(a.heatLocked(System.currentTimeMillis()));
    }
}
