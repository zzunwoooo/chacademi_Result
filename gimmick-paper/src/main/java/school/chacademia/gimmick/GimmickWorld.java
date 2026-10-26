package school.chacademia.gimmick;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Owns every gimmick: saved placements (gimmicks.yml), live entities per loaded chunk, mob attachments,
 * lookups (by entity, by ray, by area) and animation playback.
 * Spawned entities are non-persistent, so the world save never keeps them; gimmicks.yml is the only truth.
 */
final class GimmickWorld implements Listener {
    static final String TAG = "chaca_gimmick";
    private final GimmickPlugin plugin;
    private final File file;
    private final Map<String, Gimmick> gimmicks = new LinkedHashMap<>();
    private final Map<UUID, Gimmick> byEntity = new HashMap<>();
    private final NamespacedKey idKey, modelKey, scaleKey;
    private int nextId = 1;
    private boolean dirty;

    GimmickWorld(GimmickPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gimmicks.yml");
        idKey = new NamespacedKey(plugin, "gimmick_id");
        modelKey = new NamespacedKey(plugin, "model");
        scaleKey = new NamespacedKey(plugin, "scale");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 40, 40);
    }

    /** Settings shared by every base entity we create. */
    static void prepareBase(Entity e) {
        e.setPersistent(false);
        e.setSilent(true);
        e.setInvulnerable(true);
        e.setGravity(false);
        e.addScoreboardTag(TAG);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
        }
    }

    // ------------------------------------------------------------------ store
    void load() {
        gimmicks.clear();
        var yaml = AtomicYaml.load(file);
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        var sec = yaml.getConfigurationSection("gimmicks");
        if (sec != null) for (String id : sec.getKeys(false)) {
            var s = sec.getConfigurationSection(id);
            if (s != null) { var g = Gimmick.load(id, s.getValues(false)); gimmicks.put(id, g); }
        }
        for (Gimmick g : gimmicks.values()) {
            var info = plugin.catalog().get(g.model);
            if (g.answer < 0 && Signals.hasAnswer(info.kind())) { g.answer = Signals.defaultAnswer(info.kind(), g.facing); dirty = true; }
            g.place(info, null); // platform offsets are applied once Devices is up
        }
        cellsDirty = true;
    }

    /** After loading: put moved floating platforms where they were. */
    void applyOffsets() {
        for (Gimmick g : gimmicks.values()) if (!g.attached()) g.place(plugin.catalog().get(g.model), plugin.devices().platformOffset(g));
        cellsDirty = true;
    }

    void save() {
        var yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Gimmick g : gimmicks.values()) if (!g.attached()) yaml.createSection("gimmicks." + g.id, g.save());
        try { plugin.getDataFolder().mkdirs(); AtomicYaml.save(yaml,file);dirty=false; }
        catch (IOException e) { plugin.getLogger().warning("gimmicks.yml 저장 실패: " + e.getMessage()); }
    }
    void markDirty() { dirty = true; }

    Collection<Gimmick> all() { return Collections.unmodifiableCollection(gimmicks.values()); }
    Gimmick get(String id) { return gimmicks.get(id); }
    Gimmick byEntity(Entity e) { return e == null ? null : byEntity.get(e.getUniqueId()); }

    // ------------------------------------------------------------------ create / remove
    /** New placed gimmick on block (bx, by, bz) = the block the model stands in (its feet), facing 0..3. */
    Gimmick create(String model, World w, int bx, int by, int bz, int facing) {
        var g = new Gimmick("g" + nextId++, model, w.getName(), bx, by, bz, facing);
        var info = plugin.catalog().get(model);
        if (Signals.hasAnswer(info.kind())) g.answer = Signals.defaultAnswer(info.kind(), facing);
        g.place(info, null);
        gimmicks.put(g.id, g);
        cellsDirty = true;
        markDirty();
        if (w.isChunkLoaded(g.chunkX(), g.chunkZ())) spawn(g, true);
        plugin.collision().refresh(g);
        plugin.signals().dirty();
        return g;
    }

    /** Placed gimmicks that already take one of these blocks (placement refuses overlaps). */
    Gimmick overlap(String world, List<int[]> columns, int by, int layers, Gimmick except) {
        for (int[] c : columns) for (int l = 0; l < layers; l++) { Gimmick o = at(world, c[0], by + l, c[1]); if (o != null && o != except) return o; }
        return null;
    }

    // ------------------------------------------------------------------ block index (mana grid, beams, overlap)
    private final Map<String, Gimmick> cells = new HashMap<>();
    private boolean cellsDirty = true;
    void cellsChanged() { cellsDirty = true; }
    static String cell(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }
    /** The placed gimmick taking block (x, y, z): its footprint columns, from the anchor up through the model height. */
    Gimmick at(String world, int x, int y, int z) {
        if (cellsDirty) {
            cells.clear();
            for (Gimmick g : gimmicks.values()) {
                if (g.attached()) continue;
                var info = plugin.catalog().get(g.model);
                int base = (int) Math.floor(g.y + 1e-6), top = base + g.layers(info) - 1;
                var off = plugin.devices().platformOffset(g);
                int dx = (int) Math.round(off.getX()), dz = (int) Math.round(off.getZ());
                for (int[] c : g.columns(info, dx, dz)) for (int yy = base; yy <= Math.max(base, top); yy++) cells.putIfAbsent(cell(g.world, c[0], yy, c[1]), g);
            }
            cellsDirty = false;
        }
        return cells.get(cell(world, x, y, z));
    }

    void remove(Gimmick g) {
        despawn(g);
        gimmicks.remove(g.id);
        cellsDirty = true;
        plugin.collision().clear(g);
        plugin.signals().forget(g);
        plugin.devices().forget(g);
        if (g.attached()) {
            Entity host = Bukkit.getEntity(g.host);
            if (host != null) {
                var pdc = host.getPersistentDataContainer();
                for (var k : List.of(modelKey, scaleKey)) pdc.remove(k);
            }
        }
        markDirty();
    }

    /** Put a model on a mob. The choice is stored on the mob so it comes back after a restart. */
    Gimmick attach(LivingEntity host, String model, double scale) {
        if(GimmickProtection.excluded(host)||!plugin.protection().at(host.getLocation())||!Double.isFinite(scale)||scale<.1||scale>8)return null;
        Gimmick old = byEntity(host);
        if(old==null&&plugin.renderer().hasModel(host))return null;
        if (old != null) remove(old);
        var pdc = host.getPersistentDataContainer();
        pdc.set(modelKey, PersistentDataType.STRING, model);
        pdc.set(scaleKey, PersistentDataType.DOUBLE, scale);
        return attachFromData(host);
    }

    private Gimmick attachFromData(LivingEntity host) {
        var pdc = host.getPersistentDataContainer();
        String model = pdc.get(modelKey, PersistentDataType.STRING);
        if (model == null||GimmickProtection.excluded(host)) return null;
        var g = new Gimmick("m" + host.getUniqueId().toString(), model, host.getUniqueId());
        g.scale = Optional.ofNullable(pdc.get(scaleKey, PersistentDataType.DOUBLE)).orElse(1.0);
        var r = plugin.renderer();
        g.handle = r.hasModel(host) ? null : r.attach(plugin.fullId(model), host, g.scale);
        if (g.handle == null) { plugin.getLogger().warning("모델을 씌우지 못했습니다: " + model + " (렌더러 " + r.name() + ")"); return null; }
        gimmicks.put(g.id, g);
        byEntity.put(host.getUniqueId(), g);
        playState(g, false);
        return g;
    }

    // ------------------------------------------------------------------ live entities
    void spawn(Gimmick g, boolean fresh) {
        if (g.live() || g.attached()) return;
        Location at = g.location();
        if (at == null||!plugin.protection().at(at)) return;
        var info = plugin.catalog().get(g.model);
        g.handle = plugin.renderer().spawn(plugin.fullId(g.model), at, g.scale);
        if (g.handle == null) {
            plugin.getLogger().warning("모델 소환 실패: " + g.id + " " + plugin.fullId(g.model) + " (렌더러 " + plugin.renderer().name() + " 에 등록됐는지 확인)");
            return;
        }
        byEntity.put(g.handle.base().getUniqueId(), g);
        g.hitbox = at.getWorld().spawn(at, Interaction.class, i -> {
            i.setInteractionWidth((float) (info.width() * g.scale));
            i.setInteractionHeight((float) (info.height() * g.scale));
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(TAG);
            i.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, g.id);
        });
        byEntity.put(g.hitbox.getUniqueId(), g);
        playState(g, fresh);
    }

    void despawn(Gimmick g) {
        g.animToken++;
        if (g.handle != null) {
            byEntity.remove(g.handle.base().getUniqueId());
            try { g.handle.remove(); } catch (RuntimeException ignored) {}
            g.handle = null;
        }
        if (g.hitbox != null) { byEntity.remove(g.hitbox.getUniqueId()); g.hitbox.remove(); g.hitbox = null; }
        if (g.attached()) byEntity.remove(g.host);
    }

    /** Move/rotate/scale applied to the live entities and saved. */
    void transform(Gimmick g) {
        if(!g.attached()&&!plugin.protection().at(new Location(Bukkit.getWorld(g.world),g.bx+.5,g.by,g.bz+.5)))return;
        if (g.attached()) {
            if (g.handle != null) g.handle.scale(g.scale);
            Entity host = Bukkit.getEntity(g.host);
            if (host != null) host.getPersistentDataContainer().set(scaleKey, PersistentDataType.DOUBLE, g.scale);
            return;
        }
        g.place(plugin.catalog().get(g.model), plugin.devices().platformOffset(g));
        cellsDirty = true;
        markDirty();
        plugin.collision().refresh(g);
        plugin.signals().dirty();
        Location at = g.location();
        if (at == null || !g.live()) return;
        g.handle.teleport(at);
        g.handle.scale(g.scale);
        if (g.hitbox != null) {
            var info = plugin.catalog().get(g.model);
            g.hitbox.teleport(at);
            g.hitbox.setInteractionWidth((float) (info.width() * g.scale));
            g.hitbox.setInteractionHeight((float) (info.height() * g.scale));
        }
    }

    void spawnLoaded() {
        for (Gimmick g : gimmicks.values()) {
            World w = Bukkit.getWorld(g.world);
            if (!g.attached() && w != null && w.isChunkLoaded(g.chunkX(), g.chunkZ())) spawn(g, false);
        }
        for (World w : Bukkit.getWorlds()) for (LivingEntity e : w.getLivingEntities()) reattach(e);
    }

    void despawnAll() {
        for (Gimmick g : new ArrayList<>(gimmicks.values())) {
            despawn(g);
            if (g.attached()) gimmicks.remove(g.id);
        }
    }

    private void reattach(Entity e) {
        if (!(e instanceof LivingEntity le) || byEntity.containsKey(e.getUniqueId())) return;
        if (le.getPersistentDataContainer().has(modelKey, PersistentDataType.STRING)) attachFromData(le);
    }

    @EventHandler public void chunkLoad(ChunkLoadEvent e) {
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        for (Gimmick g : gimmicks.values())
            if (!g.attached() && !g.live() && g.world.equals(w) && g.chunkX() == cx && g.chunkZ() == cz) spawn(g, false);
    }

    @EventHandler public void chunkUnload(ChunkUnloadEvent e) {
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        for (Gimmick g : gimmicks.values())
            if (!g.attached() && g.live() && g.world.equals(w) && g.chunkX() == cx && g.chunkZ() == cz) despawn(g);
    }

    @EventHandler public void entitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            // Leftovers from a crash (should not happen: our entities are non-persistent).
            if (en.getScoreboardTags().contains(TAG) && !byEntity.containsKey(en.getUniqueId())) { en.remove(); continue; }
            reattach(en);
        }
    }

    @EventHandler public void entitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            Gimmick g = byEntity.get(en.getUniqueId());
            if (g != null && g.attached() && en.getUniqueId().equals(g.host)) { despawn(g); gimmicks.remove(g.id); }
        }
    }

    // ------------------------------------------------------------------ lookups
    /** Gimmick the player is looking at (model box), within `max` blocks. */
    Gimmick looking(Player p, double max) {
        Vector start = p.getEyeLocation().toVector(), dir = p.getEyeLocation().getDirection();
        var block=p.getWorld().rayTraceBlocks(p.getEyeLocation(),dir,max,FluidCollisionMode.NEVER,true);
        // our own collision barriers sit exactly where the models are: they must not hide the model behind them
        if(block!=null&&!(block.getHitBlock()!=null&&plugin.collision().ours(block.getHitBlock())))max=Math.min(max,block.getHitPosition().distance(start));
        Gimmick best = null; double bestDist = max;
        for (Gimmick g : gimmicks.values()) {
            if (!g.live()) continue;
            Location l = g.location();
            if (l == null || l.getWorld() != p.getWorld() || l.distanceSquared(p.getLocation()) > (max + 8) * (max + 8)) continue;
            BoundingBox box = g.box(plugin.catalog().get(g.model)).expand(0.1);
            RayTraceResult hit = box.rayTrace(start, dir, max);
            if (hit == null) continue;
            double d = hit.getHitPosition().distance(start);
            if (d < bestDist) { bestDist = d; best = g; }
        }
        return best;
    }

    /** Live gimmicks whose box comes within `radius` of a point. */
    List<Gimmick> near(Location at, double radius) {
        var out = new ArrayList<Gimmick>();
        for (Gimmick g : gimmicks.values()) {
            if (!g.live()) continue;
            Location l = g.location();
            if (l == null || l.getWorld() != at.getWorld()) continue;
            BoundingBox box = g.box(plugin.catalog().get(g.model));
            if (distance(box, at.toVector()) <= radius) out.add(g);
        }
        return out;
    }

    static double distance(BoundingBox b, Vector p) {
        double dx = Math.max(0, Math.max(b.getMinX() - p.getX(), p.getX() - b.getMaxX()));
        double dy = Math.max(0, Math.max(b.getMinY() - p.getY(), p.getY() - b.getMaxY()));
        double dz = Math.max(0, Math.max(b.getMinZ() - p.getZ(), p.getZ() - b.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ animation
    /** Show the state: on -> active (or activate held), off -> idle. `fresh` plays spawn first. */
    void playState(Gimmick g, boolean fresh) {
        if (g.handle != null) g.handle.stop();
        boolean on = on(g);
        var info = plugin.catalog().get(g.model);
        String steady = on ? (info.has("active") ? "active" : "activate") : "idle";
        if (fresh && info.has("spawn")) play(g, "spawn", steady);
        else play(g, steady, null);
        deviceLayers(g);
    }

    /** Shown state (Signals: own rule for inputs, wiring for outputs, flow for conduits, beam for mirrors / prisms). */
    boolean on(Gimmick g) { return plugin.signals().on(g); }
    /** Signals changed this gimmick's state: barrier + animation. */
    void display(Gimmick g, boolean on) {
        plugin.collision().refresh(g);
        if (g.live()) { transition(g, on, null, null); Bukkit.getScheduler().runTaskLater(plugin, () -> deviceLayers(g), 2); }
    }

    /** Device pose layers that sit on top of the state animation: mirror/dial position, water level, prism colour. */
    void deviceLayers(Gimmick g) {
        if (g.handle == null) return;
        var info = plugin.catalog().get(g.model);
        if (info.is("mirror") && info.has("rot_" + g.pos)) g.handle.layer("pose", "rot_" + g.pos);
        if (info.is("dial") && info.has("pos_" + g.pos)) g.handle.layer("pose", "pos_" + g.pos);
        if (info.is("pillar")) g.handle.layer("level", g.level > 0 && info.has("level_" + g.level) ? "level_" + g.level : null);
        if (info.is("prism")) g.handle.tint("prism", Colors.rgb(g.color));
    }

    /** Play `anim`, then `then` when it finishes (for hold/once animations). */
    void play(Gimmick g, String anim, String then) {
        if (g.handle == null) return;
        var info = plugin.catalog().get(g.model);
        int token = ++g.animToken;
        if (anim != null && (info.has(anim) || !plugin.catalog().known(g.model))) { g.handle.play(anim); g.lastAnim = anim; }
        else if (then != null) { g.handle.play(then); g.lastAnim = then; return; }
        if (then == null || anim == null || !info.has(anim)) return;
        long ticks = info.anims().get(anim).ticks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (g.animToken == token && g.handle != null && (info.has(then) || !plugin.catalog().known(g.model))) { g.handle.play(then); g.lastAnim = then; }
        }, ticks);
    }

    /** Layered animation (kept while the main state animation changes). */
    void overlay(Gimmick g, String anim) {
        if (g.handle != null && anim != null) { g.handle.overlay(anim); g.lastAnim = anim; }
    }

    /** Turned on/off. */
    void transition(Gimmick g, boolean on, String animOn, String animOff) {
        var info = plugin.catalog().get(g.model);
        if (on) play(g, animOn != null ? animOn : "activate", info.has("active") ? "active" : null);
        else play(g, animOff != null ? animOff : (info.has("deactivate") ? "deactivate" : "idle"), "idle");
        if (g.handle != null) Bukkit.getScheduler().runTaskLater(plugin, () -> deviceLayers(g), 1);
    }
}
