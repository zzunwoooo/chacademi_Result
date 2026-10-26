package school.chacademia.kyudichi;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Ping spam limit: 3 pings per 5 seconds per player (오너 확정). */
final class Pings {
    static final int MAX = 3; static final long WINDOW_MS = 5000;
    private final Map<UUID, ArrayDeque<Long>> sent = new HashMap<>();

    boolean allow(UUID id) { return allow(id, System.currentTimeMillis()); }
    boolean allow(UUID id, long now) {
        var q = sent.computeIfAbsent(id, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
        if (q.size() >= MAX) return false;
        q.addLast(now);
        return true;
    }
    void forget(UUID id) { sent.remove(id); }
}
