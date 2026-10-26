package school.magiccodex.discovery;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** A failed or slow permission save must never let an older write finish after a newer write. */
final class PermissionWriteQueue {
    private final Map<UUID, CompletableFuture<Void>> pending = new HashMap<>();

    synchronized CompletableFuture<Void> enqueue(UUID player, Supplier<CompletableFuture<Void>> action) {
        var previous = pending.getOrDefault(player, CompletableFuture.completedFuture(null));
        var next = previous.handle((ok, error) -> null).thenCompose(ignored -> action.get());
        pending.put(player, next);
        next.whenComplete((ok, error) -> remove(player, next));
        return next;
    }

    /** 아직 끝나지 않은 쓰기가 있으면 true: 캐시만 보고 쓰기를 생략하면 안 된다. */
    synchronized boolean busy(UUID player) { return pending.containsKey(player); }

    private synchronized void remove(UUID player, CompletableFuture<Void> completed) {
        pending.remove(player, completed);
    }
}
