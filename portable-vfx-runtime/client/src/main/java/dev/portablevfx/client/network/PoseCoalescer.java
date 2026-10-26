package dev.portablevfx.client.network;

import dev.portablevfx.protocol.PoseEffect;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Client-thread holder for poses admitted through the STREAM overflow bucket. Only the newest
 * pose per instance is kept; at capacity the least recently refreshed (stalest) instance is
 * evicted. Deferred poses are applied at the end of the client tick, or just before a FINISH or
 * IMPACT for the same instance so the original order is preserved.
 */
final class PoseCoalescer {
    static final int MAX_PENDING = 256;
    private final LinkedHashMap<UUID, PoseEffect> pending = new LinkedHashMap<>();

    void offer(PoseEffect pose) {
        PoseEffect previous = pending.remove(pose.instanceId());
        // Re-insert at the tail: insertion order is staleness order.
        pending.put(pose.instanceId(), previous != null && previous.sequence() > pose.sequence() ? previous : pose);
        if (pending.size() > MAX_PENDING) {
            Iterator<UUID> stalest = pending.keySet().iterator();
            stalest.next();
            stalest.remove();
        }
    }

    /** A directly admitted pose supersedes any older deferred one for the same instance. */
    void supersede(UUID instanceId) { pending.remove(instanceId); }

    /** Removes and returns the deferred pose for an instance, or null. */
    PoseEffect take(UUID instanceId) { return pending.remove(instanceId); }

    void clear() { pending.clear(); }

    int size() { return pending.size(); }

    void drain(Consumer<PoseEffect> apply) {
        if (pending.isEmpty()) return;
        var batch = new java.util.ArrayList<>(pending.values());
        pending.clear();
        batch.forEach(apply);
    }
}
