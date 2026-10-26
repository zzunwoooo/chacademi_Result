package school.chacademia.gimmick;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Device behaviour that does not need the magic plugin:
 *  - device state changes (mirror/dial position, water level, prism colour, facing) with a one-step history for 248 되감기,
 *  - floating platform: once opened travels its 정답 route (앞으로/위로 N칸) one way (riders move with it), back on 초기화,
 *  - wind vent: while on, throws players standing on it upward (config vent-launch),
 *  - water wheel: water flowing in or under its blocks turns it on,
 *  - teleport gates linked by 250 차원 표식 (walk in = go to the other gate).
 */
final class Devices {
    private final GimmickPlugin plugin;
    private final Map<String, Gimmick.Snapshot> history = new HashMap<>();
    private final Map<String, String> links = new HashMap<>();   // gate id -> other gate id
    private final Map<String, Long> linkUntil = new HashMap<>();  // gate id -> tick
    private long tick;

    Devices(GimmickPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    // ------------------------------------------------------------------ state changes (+ history)
    void remember(Gimmick g) { history.put(g.id, g.snapshot(tick)); }
    /** 248 되감기: undo the last change made within `seconds`. */
    boolean rewind(Gimmick g, double seconds) {
        var s = history.remove(g.id);
        if (s == null || tick - s.at() > seconds * 20) return false;
        boolean turned = s.facing() != g.facing;
        g.facing = s.facing(); g.pos = s.pos(); g.level = s.level(); g.color = s.color();
        if (turned) plugin.world().transform(g);
        if (Signals.INPUTS.contains(plugin.catalog().get(g.model).kind())) g.latched = s.latched(); // outputs that opened stay open
        plugin.world().markDirty();
        plugin.world().deviceLayers(g);
        changed(g);
        return true;
    }
    void turn(Gimmick g, int step) { remember(g); g.facing = Math.floorMod(g.facing + step, 4); plugin.world().transform(g); changed(g); }
    void position(Gimmick g, int step) { remember(g); g.pos = Math.floorMod(g.pos + step, 4); plugin.world().markDirty(); plugin.world().deviceLayers(g); changed(g); }
    boolean level(Gimmick g, int step) {
        int next = Math.clamp(g.level + step, 0, 3);
        if (next == g.level) return false;
        remember(g); g.level = next; plugin.world().markDirty(); plugin.world().deviceLayers(g); changed(g);
        return true;
    }
    void color(Gimmick g, String color) { remember(g); g.color = Colors.norm(color); plugin.world().markDirty(); plugin.world().deviceLayers(g); changed(g); }
    private void changed(Gimmick g) { plugin.signals().dirty(); }

    void forget(Gimmick g) { history.remove(g.id); String o = links.remove(g.id); if (o != null) { links.remove(o); linkUntil.remove(o); } linkUntil.remove(g.id); }

    // ------------------------------------------------------------------ platform
    /** Route from the 정답: 앞으로 2/4/6/8칸 (facing) or 위로 2/4/6/8칸. */
    private Vector route(Gimmick g) {
        if (!plugin.catalog().get(g.model).is("platform")) return null;
        int a = Math.floorMod(g.answer < 0 ? 1 : g.answer, Signals.PLATFORM_ANSWERS.size());
        int n = (a % 4 + 1) * 2;
        if (a >= 4) return new Vector(0, n, 0);
        int[] f = Gimmick.forward(g.facing);
        return new Vector(f[0] * n, 0, f[1] * n);
    }
    /** Current offset of a floating platform from its anchor (zero for everything else). */
    Vector platformOffset(Gimmick g) {
        if (g.travel <= 0) return new Vector();
        var m = route(g);
        return m == null ? new Vector() : m.clone().multiply(g.travel);
    }
    void reset(Gimmick g) {
        if (g.travel != 0) { g.travel = 0; plugin.world().transform(g); }
        history.remove(g.id);
    }

    // ------------------------------------------------------------------ gates (250 차원 표식)
    void link(Gimmick a, Gimmick b, int seconds) {
        for (Gimmick g : List.of(a, b)) { String o = links.remove(g.id); if (o != null) { links.remove(o); linkUntil.remove(o); show(o, false); } }
        links.put(a.id, b.id); links.put(b.id, a.id);
        long until = tick + seconds * 20L;
        linkUntil.put(a.id, until); linkUntil.put(b.id, until);
        show(a.id, true); show(b.id, true);
    }
    Gimmick linked(Gimmick g) { String o = links.get(g.id); return o == null ? null : plugin.world().get(o); }
    private void show(String id, boolean on) { plugin.signals().dirty(); }

    // ------------------------------------------------------------------ tick
    private void tick() {
        tick++;
        // gate links expire
        if (!linkUntil.isEmpty()) for (var e : List.copyOf(linkUntil.entrySet())) if (e.getValue() < tick) {
            linkUntil.remove(e.getKey()); links.remove(e.getKey()); show(e.getKey(), false);
        }
        for (Gimmick g : plugin.world().all()) {
            if (g.attached() || !g.live()) continue;
            var info = plugin.catalog().get(g.model);
            switch (info.kind()) {
                case "platform" -> platform(g, info);
                case "vent" -> { if (tick % 4 == 0 && plugin.world().on(g)) vent(g, info); }
                default -> {}
            }
        }
        if (tick % 10 == 0) {
            var wet = new HashSet<String>();
            for (Gimmick g : plugin.world().all()) if (!g.attached() && plugin.catalog().get(g.model).is("wheel") && wheel(g, plugin.catalog().get(g.model))) wet.add(g.id);
            plugin.signals().water(wet);
        }
    }

    private void platform(Gimmick g, ModelCatalog.Info info) {
        var m = route(g);
        if (m == null) return;
        if (!plugin.signals().on(g) || g.travel >= 1) return; // one way: once it started it finishes, and stays (초기화 brings it back)
        double len = Math.max(0.1, m.length());
        double step = Math.min(1 - g.travel, plugin.getConfig().getDouble("platform-speed", 2) / 20 / len);
        Vector before = platformOffset(g);
        BoundingBox top = g.box(info).expand(0, 0, 0, 0, 1.2, 0);
        g.travel += step;
        Vector after = platformOffset(g);
        Vector delta = after.clone().subtract(before);
        Location at = g.location();
        if (at != null) {
            g.x += delta.getX(); g.y += delta.getY(); g.z += delta.getZ();
            if (g.handle != null) g.handle.teleport(g.location());
            if (g.hitbox != null) g.hitbox.teleport(g.location());
            double surface = g.y + info.height() * g.scale; // model top after this step
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || !top.contains(p.getLocation().toVector())) continue;
                var to = p.getLocation().add(delta);
                to.setY(Math.max(to.getY(), surface)); // stand on the moving top (there is no barrier while it travels)
                p.teleport(to);
                p.setVelocity(p.getVelocity().setY(0));
                p.setFallDistance(0);
            }
        }
        plugin.world().cellsChanged();
        if (g.travel >= 1 || g.travel - step <= 0) plugin.collision().refresh(g); // barrier lifted when it sets off, back when it lands
        plugin.world().markDirty();
    }

    private void vent(Gimmick g, ModelCatalog.Info info) {
        double launch = plugin.getConfig().getDouble("vent-launch", 1.3);
        var box = g.box(info).expand(0, 0, 0, 0, 2.5, 0);
        var at = g.location();
        if (at == null) return;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !box.contains(p.getLocation().toVector())) continue;
            var v = p.getVelocity();
            if (v.getY() < launch * 0.8) p.setVelocity(v.setY(launch));
            p.setFallDistance(0);
        }
    }

    private boolean wheel(Gimmick g, ModelCatalog.Info info) {
        World w = Bukkit.getWorld(g.world);
        if (w == null) return false;
        boolean water = false;
        for (int[] c : g.columns(info)) for (int dy = -1; dy <= 0 && !water; dy++) {
            if (!w.isChunkLoaded(c[0] >> 4, c[1] >> 4)) continue;
            Block b = w.getBlockAt(c[0], g.by + dy, c[1]);
            water = b.getType() == Material.WATER || b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
        }
        return water;
    }
}
