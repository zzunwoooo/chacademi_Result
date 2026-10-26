package school.magiccodex.paper;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Main-thread state. A failed durable write must never advance the workflow. */
final class FirstNicknameState {
    interface Writer { void write(Set<UUID> pending) throws Exception; }
    private final Set<UUID> pending;
    private final Writer writer;
    FirstNicknameState(Collection<UUID> initial, Writer writer) {
        this.pending = new HashSet<>(initial); this.writer = writer;
    }
    boolean pending(UUID id) { return pending.contains(id); }
    boolean start(UUID id) throws Exception {
        if (pending(id)) return false;
        Set<UUID> next = new HashSet<>(pending); next.add(id);
        writer.write(Set.copyOf(next)); pending.add(id); return true;
    }
    boolean complete(UUID id, BooleanSupplier current, Runnable delivered) throws Exception {
        if (!pending(id) || !current.getAsBoolean()) return false;
        Set<UUID> next = new HashSet<>(pending); next.remove(id);
        // Caller serializes connection changes on the main thread while this write is acknowledged.
        writer.write(Set.copyOf(next)); pending.remove(id);
        delivered.run(); return true;
    }
}
