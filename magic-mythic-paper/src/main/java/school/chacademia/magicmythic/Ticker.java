package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** One server-thread loop for every running bolt, zone and timed helper. */
final class Ticker implements AutoCloseable {
    interface Tick {
        /** @return false when finished */
        boolean tick();
        default void stop() {}
        default String kind() { return "other"; }
    }
    private final MagicMythicPlugin plugin;
    private final List<Tick> running = new ArrayList<>();
    private final Map<Tick, SpellTrace.Now> traces = new IdentityHashMap<>();
    private final BukkitTask task;

    Ticker(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 1, 1);
    }

    boolean add(Tick t) {
        int limit = plugin.getConfig().getInt("limits." + t.kind() + "s", 512);
        long same = running.stream().filter(r -> r.kind().equals(t.kind())).count();
        if (same >= limit) return false;
        running.add(t);
        traces.put(t, SpellTrace.current());
        return true;
    }

    private void step() {
        if (running.isEmpty()) return;
        var copy = new ArrayList<>(running);
        for (Tick t : copy) {
            boolean alive;
            var before = SpellTrace.set(traces.get(t));
            try { alive = t.tick(); }
            catch (RuntimeException e) {
                plugin.getLogger().warning("마법 진행 중 오류: " + e.getClass().getSimpleName() + " " + e.getMessage());
                alive = false;
            } finally { SpellTrace.set(before); }
            if (!alive) { running.remove(t); traces.remove(t); safeStop(t); }
        }
    }

    void removeIf(java.util.function.Predicate<Tick> filter) {
        var it = running.iterator();
        while (it.hasNext()) { var t = it.next(); if (filter.test(t)) { it.remove(); traces.remove(t); safeStop(t); } }
    }

    private void safeStop(Tick t) { try { t.stop(); } catch (RuntimeException ignored) {} }

    String summary() {
        var counts = new TreeMap<String, Integer>();
        for (Tick t : running) counts.merge(t.kind(), 1, Integer::sum);
        return counts.isEmpty() ? "없음" : counts.toString();
    }

    @Override public void close() {
        task.cancel();
        for (Tick t : new ArrayList<>(running)) safeStop(t);
        running.clear();
        traces.clear();
    }
}
