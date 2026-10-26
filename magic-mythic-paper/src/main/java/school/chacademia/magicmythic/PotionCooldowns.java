package school.chacademia.magicmythic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 포션 쿨타임 (2026-10-26 오너 결정): 체력 포션·마나 포션은 쿨이 <b>따로</b> 돈다 — 체력 포션을 마셔도 마나 포션은 막히지 않는다.
 * 순수 로직 (Bukkit 없음, 메인 스레드 전용). 서버 메모리에만 둔다: 나가면 지우고, 서버 이동·재시작이면 다시 0 (10~15초라 공유 DB 불필요).
 */
final class PotionCooldowns {
    enum Kind { HEALTH, MANA }

    /** player → [체력 끝 시각, 마나 끝 시각] (ms). */
    private final Map<UUID, long[]> until = new HashMap<>();

    /** 남은 쿨 (ms, 0 = 쓸 수 있음). */
    long remaining(UUID id, Kind kind, long now) {
        long[] u = until.get(id);
        return u == null ? 0 : Math.max(0, u[kind.ordinal()] - now);
    }

    /** 쿨 시작. millis <= 0 이면 쿨 없음. 다른 종류의 쿨은 건드리지 않는다. */
    void start(UUID id, Kind kind, long now, long millis) {
        if (millis <= 0) return;
        until.computeIfAbsent(id, k -> new long[Kind.values().length])[kind.ordinal()] = now + millis;
    }

    void forget(UUID id) { until.remove(id); }

    /** 끝난 기록 정리 (가끔 부름). */
    void prune(long now) {
        until.values().removeIf(u -> { for (long t : u) if (t > now) return false; return true; });
    }

    int size() { return until.size(); }

    /** 알림용 남은 초 (올림, 최소 1). */
    static long seconds(long millis) { return Math.max(1, (millis + 999) / 1000); }
}
