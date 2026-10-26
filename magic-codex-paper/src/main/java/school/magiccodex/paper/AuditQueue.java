package school.magiccodex.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영 기록 대기열 — 순수 로직 (JUnit 대상). 게임 스레드는 {@link #offer} 만 하고(잠금 아주 짧게, IO 없음),
 * 기록 스레드가 {@link #drain} 으로 몇 초마다 묶어서 가져간다.
 * 상한을 넘으면 "사소한 종류"(접속·낚시 등) 중 가장 오래된 것부터 버리고, 사소한 것이 없으면 가장 오래된 것을 버린다.
 */
final class AuditQueue<T> {
    private record Item<T>(long seq, T value) {}
    private final ArrayDeque<Item<T>> minor = new ArrayDeque<>(), major = new ArrayDeque<>();
    private final int limit;
    private long seq, dropped;

    AuditQueue(int limit) { this.limit = Math.max(16, limit); }

    /** 넣는다. 상한 때문에 버린 개수(0 또는 1)를 돌려준다. */
    synchronized int offer(T value, boolean isMinor) {
        int lost = 0;
        if (minor.size() + major.size() >= limit) {
            if (!minor.isEmpty()) minor.pollFirst();
            else major.pollFirst();
            lost = 1; dropped++;
        }
        (isMinor ? minor : major).addLast(new Item<>(seq++, value));
        return lost;
    }

    /** 들어온 순서대로 최대 max 개를 꺼낸다. */
    synchronized List<T> drain(int max) {
        var out = new ArrayList<T>(Math.min(max, minor.size() + major.size()));
        while (out.size() < max && (!minor.isEmpty() || !major.isEmpty())) {
            var a = minor.peekFirst(); var b = major.peekFirst();
            out.add((b == null || a != null && a.seq() < b.seq() ? minor.pollFirst() : major.pollFirst()).value());
        }
        return out;
    }

    synchronized int size() { return minor.size() + major.size(); }

    /** 지금까지 상한 때문에 버린 개수. */
    synchronized long dropped() { return dropped; }
}
