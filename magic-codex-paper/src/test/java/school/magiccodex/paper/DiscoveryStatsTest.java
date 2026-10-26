package school.magiccodex.paper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 2026-10-26 발견 조건 v2: 서버 통계 → 발견 카운터 이름표, 통계 전달은 통계가 꺼져 있어도 된다. */
class DiscoveryStatsTest {
    @Test void statsMapToDiscoveryKeys() {
        assertEquals(List.of("taming.success"), DiscoveryStats.keys("교화"));
        assertEquals(List.of("fish.caught_fish"), DiscoveryStats.keys("낚시"));
        assertEquals(List.of("fishing.rare"), DiscoveryStats.keys("낚시:등급1"));
        assertEquals(List.of("fishing.rare", "fishing.legendary"), DiscoveryStats.keys("낚시:등급3"));
        assertEquals(List.of(), DiscoveryStats.keys("낚시:등급0"));
        assertEquals(List.of("duel.won"), DiscoveryStats.keys("결투승리"));
        assertEquals(List.of("broom.flown"), DiscoveryStats.keys("빗자루비행"));
        assertEquals(List.of(), DiscoveryStats.keys("마법발견")); assertEquals(List.of(), DiscoveryStats.keys(null));
    }
    @Test void forwardSeesStatsEvenWhenStatsAreOff() {
        var seen = new ArrayList<String>();
        AuditStats.forward((p, k, n) -> seen.add(k + "=" + n));
        try {
            AuditStats.enabled(false); var id = UUID.randomUUID();
            AuditStats.add(id, "교화", 2); AuditStats.add(id, "교화", 0); AuditStats.add(null, "교화", 1);
            assertEquals(List.of("교화=2"), seen); assertEquals(0, AuditStats.pending());
            AuditStats.forward((p, k, n) -> { throw new IllegalStateException("boom"); });
            AuditStats.enabled(true); AuditStats.add(id, "x", 1); assertEquals(1, AuditStats.drain(null).size());
        } finally { AuditStats.forward(null); AuditStats.enabled(true); AuditStats.drain(null); }
    }
}
