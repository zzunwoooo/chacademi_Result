package school.chacademia.magicmythic;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.TelegraphProtocol;
import school.magiccodex.protocol.TelegraphProtocol.Show;

/**
 * Ground warnings for enemy area attacks ({@link TelegraphProtocol}). A warning is sent to every listening player
 * nearby, and after its ticks the callback runs with the same {@link Show} so the hit test matches what was drawn.
 * The warning is cancelled (CLEAR, no hit) when the caster dies or leaves the world first.
 * Load: one packet (~60 bytes) per warning to players within range, one delayed task, one entity scan at the hit;
 * the client draws it. Nothing runs per tick.
 */
final class Telegraphs implements AutoCloseable, Listener {
    private final MagicMythicPlugin plugin;
    private final Map<Integer, Live> live = new HashMap<>();
    private int nextId = 1;
    private double range = 64;
    private int limit = 128;

    private record Live(Show show, LivingEntity caster, String world, boolean cancelOnDeath, BukkitTask task) {}

    Telegraphs(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, TelegraphProtocol.CHANNEL);
        reload();
    }

    void reload() {
        range = Math.clamp(plugin.getConfig().getDouble("telegraph.range", 64), 16, 160);
        limit = Math.clamp(plugin.getConfig().getInt("telegraph.limit", 128), 8, 1024);
    }

    /** Next id for a warning (wraps, never 0). */
    private final java.util.Set<Integer> silent = new java.util.HashSet<>();

    int id() { int id = nextId++; if (nextId == Integer.MAX_VALUE) nextId = 1; return id; }

    /**
     * Shows the warning and runs `then` after its ticks (main thread). cancelOnDeath: the caster's death removes it.
     * @return false when too many are live (the skill is skipped)
     */
    boolean start(Show show, LivingEntity caster, World world, boolean cancelOnDeath, Consumer<Show> then) {
        return start(show, caster, world, cancelOnDeath, true, then);
    }

    /** warn=false: same delayed hit on the same shape, but nothing is drawn (mob basic attacks - owner 2026-10-19: only area skills warn). */
    boolean start(Show show, LivingEntity caster, World world, boolean cancelOnDeath, boolean warn, Consumer<Show> then) {
        if (live.size() >= limit) return false;
        if (warn) send(world, show.x(), show.z(), TelegraphProtocol.encode(show)); else silent.add(show.id());
        int id = show.id();
        // One delayed task per warning (no per-tick work). A caster dying early is handled by the death listener.
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Live l = live.remove(id);
            if (l == null) return;
            silent.remove(id);
            if (cancelOnDeath && caster != null && (!caster.isValid() || caster.isDead() || !caster.getWorld().equals(world))) { sendClear(l); return; }
            try { then.accept(show); } catch (RuntimeException e) { plugin.getLogger().warning("예고 장판 판정 오류: " + e); }
        }, show.ticks());
        live.put(id, new Live(show, caster, world.getName(), cancelOnDeath, task));
        return true;
    }

    /** Removes a live warning without hitting. */
    void clear(int id) {
        Live l = live.remove(id);
        if (l == null) return;
        l.task().cancel();
        sendClear(l);
    }

    private void sendClear(Live l) {
        if (silent.remove(l.show().id())) return;   // never drawn
        World w = Bukkit.getWorld(l.world());
        if (w != null) send(w, l.show().x(), l.show().z(), TelegraphProtocol.encode(new TelegraphProtocol.Clear(l.show().id())));
    }

    /** The caster died: its warnings vanish at once and never hit. Costs nothing while no warning is live. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        if (live.isEmpty()) return;
        var dead = event.getEntity();
        for (var e : List.copyOf(live.entrySet())) if (e.getValue().cancelOnDeath() && dead.equals(e.getValue().caster())) clear(e.getKey());
    }

    int count() { return live.size(); }
    boolean live(int id) { return live.containsKey(id); }

    private void send(World world, double x, double z, byte[] bytes) {
        double r2 = range * range;
        for (Player p : world.getPlayers()) {
            var l = p.getLocation();
            if ((l.getX() - x) * (l.getX() - x) + (l.getZ() - z) * (l.getZ() - z) > r2) continue;
            if (!p.getListeningPluginChannels().contains(TelegraphProtocol.CHANNEL)) continue;
            p.sendPluginMessage(plugin, TelegraphProtocol.CHANNEL, bytes);
        }
    }

    /**
     * Top of the ground under `at`: the first block with a collision box at or below at.y + 0.5 (down 8 blocks).
     * A mob standing on a slab gets the slab's top. Falls back to at.y over a deep drop.
     */
    static double ground(Location at) {
        World w = at.getWorld();
        if (w == null) return at.getY();
        int x = at.getBlockX(), z = at.getBlockZ(), y0 = (int) Math.floor(at.getY() + 0.5);
        for (int y = y0; y >= Math.max(w.getMinHeight(), y0 - 8); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isPassable() || b.isLiquid()) continue;
            var box = b.getBoundingBox();
            double top = box.getVolume() > 0 ? box.getMaxY() : y + 1;
            if (top <= at.getY() + 0.5) return top;
        }
        return at.getY();
    }

    @Override public void close() {
        for (var id : List.copyOf(live.keySet())) clear(id);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, TelegraphProtocol.CHANNEL);
    }
}
