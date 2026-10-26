package school.magiccodex.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 통계 누적 카운터 (오너 2026-10-26 메모 #96): 화면 없이 기록만 쌓아 두고, 서버가 끝날 때 운영자가 DB 로 정리한다.
 *
 * <p><b>렉 없음</b>: {@link #add}는 메모리 맵에 더하기만 한다 (아무 스레드, IO 없음). 운영 기록 기록 스레드가
 * stats-flush-minutes(기본 5분)마다·플레이어가 나갈 때·서버가 꺼질 때 모아서 codex_player_stats 에 한 번에 더한다(upsert).
 * DB 가 안 되면 메모리에 되돌려 두고 다음에 다시 쓴다 (키 수 상한을 넘으면 새 키는 버림).</p>
 *
 * <p>순수 로직 (Bukkit 없음, JUnit 대상).</p>
 */
final class AuditStats {
    /** 대기 중인 증가분 하나. */
    record Delta(String player, String key, long amount) {}
    private record Key(String player, String key) {}

    private static final Map<Key, Long> PENDING = new ConcurrentHashMap<>();
    /** 메모리에 둘 최대 키 수 (DB 가 오래 안 될 때 메모리 보호). */
    static final int MAX_KEYS = 200_000;
    private static volatile boolean enabled = true;
    private static volatile boolean overflowWarned;

    private AuditStats() {}

    static void enabled(boolean on) { enabled = on; }

    /** 통계를 다른 곳에도 알리는 받는 쪽 (마법 발견 v2: DiscoveryStats). 통계가 꺼져 있어도 알린다. 예외는 삼킨다. */
    @FunctionalInterface interface Forward { void add(UUID player, String key, long amount); }
    private static volatile Forward forward;
    static void forward(Forward f) { forward = f; }

    /** 카운터 더하기. 키는 96자로 자른다. amount 0 이면 아무 일 없음. */
    static void add(UUID player, String key, long amount) {
        var f = forward;
        if (f != null && player != null && key != null && amount > 0) try { f.add(player, key, amount); } catch (RuntimeException | LinkageError ignored) { }
        if (!enabled || player == null || key == null || key.isBlank() || amount == 0) return;
        var k = new Key(player.toString(), key.length() > 96 ? key.substring(0, 96) : key);
        if (PENDING.size() >= MAX_KEYS && !PENDING.containsKey(k)) { overflowWarned = true; return; }
        PENDING.merge(k, amount, Long::sum);
    }

    /** 처음 버림이 생겼는지 (경고 한 번용). 읽으면 지운다. */
    static boolean takeOverflow() { boolean w = overflowWarned; overflowWarned = false; return w; }

    /** 대기분을 전부 꺼낸다 (키마다 원자적으로 지움). player 가 null 이 아니면 그 유저 것만. */
    static List<Delta> drain(String player) {
        var out = new ArrayList<Delta>();
        for (var k : PENDING.keySet()) {
            if (player != null && !k.player().equals(player)) continue;
            Long v = PENDING.remove(k);
            if (v != null && v != 0) out.add(new Delta(k.player(), k.key(), v));
        }
        return out;
    }

    /** DB 쓰기에 실패한 묶음을 되돌려 둔다. */
    static void restore(List<Delta> rows) {
        for (var d : rows) PENDING.merge(new Key(d.player(), d.key()), d.amount(), Long::sum);
    }

    static int pending() { return PENDING.size(); }

    /** 사망 원인 → 통계 칸 (대략): 몹 / 플레이어 / 기타. */
    static String deathBucket(boolean byPlayer, boolean byMob) { return byPlayer ? "플레이어" : byMob ? "몹" : "기타"; }
}
