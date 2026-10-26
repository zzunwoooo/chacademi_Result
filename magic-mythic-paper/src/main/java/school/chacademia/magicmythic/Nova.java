package school.chacademia.magicmythic;

import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * Shared helpers for the 325~387 spells (chanova{type=<spell id>}, tools/nova_spells.py). One instance per cast.
 *
 * VFX contract: every visual is one system of the spell's pack (PC folder 차카데미 vfx_Claude\<Bundle>, pack id = bundle
 * name in lower case). Server-moved projectiles start the projectile system directly ({@link #shot}); everything else is
 * an `external:<system>` event to the running catalog cast ({@link #fx}), whose phase plays the system of the same name
 * (role/anchor/duration from the delivered NOTES.md). Gameplay never waits for a visual.
 */
final class Nova {
    final MagicMythicPlugin plugin;
    final Params p;
    final Mech.Ctx c;
    final Player pl;
    final String spell, pack, element;

    Nova(MagicMythicPlugin plugin, Params p, Mech.Ctx c, Player pl) {
        this.plugin = plugin; this.p = p; this.c = c; this.pl = pl;
        this.spell = p.s("spell", ""); this.pack = p.s("pack", "").toLowerCase(Locale.ROOT); this.element = p.s("element", "");
    }

    // ------------------------------------------------------------------ VFX
    /** external:<system> to this spell's running cast (static/impact at `at`, attached on `target` when given). */
    void fx(String system, Location at, Entity target) {
        if (!spell.isBlank()) Links.vfx(pl, spell, "external:" + system, at, target == null ? null : target.getUniqueId());
    }
    void fx(String system, Location at) { fx(system, at, null); }
    /** A projectile-role system of the pack, moved by the caller (close() when the shot ends). Null without PortableVFX. */
    GameplayVisual shot(String system, Location at, Vector dir) {
        if (pack.isBlank()) return null;
        return GameplayVisual.cast(pl.getUniqueId(), "claude:" + pack + "/" + system, at, dir.lengthSquared() < 1e-6 ? look() : dir.clone().normalize());
    }

    // ------------------------------------------------------------------ numbers
    /** Bridge damage (base + 마력 x ratio) x mult + flat of this mechanic line. */
    double dmg() { return c.dmg(p); }
    double dmg(double scale) { return c.dmg(p) * scale; }
    int i(String k, int def) { return p.i(k, def); }
    double d(String k, double def) { return p.d(k, def); }
    String s(String k, String def) { return p.s(k, def); }

    // ------------------------------------------------------------------ targets
    boolean enemy(Entity e) { return e instanceof LivingEntity && e != pl && plugin.targets().enemy(pl, e); }
    List<LivingEntity> near(Location at, double r, int max) { return plugin.targets().enemiesNear(pl, at, r, max); }
    List<LivingEntity> near(Location at, double r) { return near(at, r, 32); }
    List<Player> allies(Location at, double r, int max) { return plugin.targets().alliesNear(pl, at, r, max); }
    boolean boss(LivingEntity e) { return plugin.targets().boss(e); }
    /** Spell damage with element + element reaction. False when the target cannot be hurt (PvP rules, protection). */
    boolean hit(LivingEntity t, double amount) {
        if (t == null || !t.isValid() || t.isDead()) return false;
        if (!plugin.targets().hurt(pl, t, amount, element)) return false;
        if (amount > 0) plugin.reactions().hit(pl, t, element, amount);
        return true;
    }
    /** "slow:60:0.3,burn:60" — the 16 status effects (StatusProtocol ids). */
    void status(LivingEntity t, String spec) {
        var st = plugin.status();
        if (st == null || t == null || !t.isValid() || t.isDead() || spec == null || spec.isBlank()) return;
        for (String part : spec.split("[,+]")) {
            String[] f = part.trim().split(":");
            Status s = Status.of(f[0]);
            if (s == null) continue;
            int ticks = 60; double power = 0;
            try { if (f.length > 1) ticks = Integer.parseInt(f[1].trim()); if (f.length > 2) power = Double.parseDouble(f[2].trim()); }
            catch (NumberFormatException ignored) { continue; }
            st.apply(pl, t, s, ticks, power);
        }
    }
    boolean has(LivingEntity t, Status s) { var st = plugin.status(); return st != null && st.has(t, s); }
    void clear(LivingEntity t, Status... s) { var st = plugin.status(); if (st != null) st.clear(t, s); }
    void knock(LivingEntity t, Location from, Vector dir, double knock, double up) { plugin.targets().knock(t, from, dir == null ? look() : dir, knock, up); }
    /** The entity the Bridge chose (ENTITY spells), or null. */
    LivingEntity target() { return c.target() instanceof LivingEntity le && le.isValid() ? le : null; }
    /** Aim point (AIM spells: the aimed block/entity point; SELF: the caster position). */
    Location aim() { return c.at().clone(); }

    // ------------------------------------------------------------------ geometry
    Vector look() { return pl.getEyeLocation().getDirection().normalize(); }
    /** Horizontal facing (unit, y = 0). */
    Vector flat() { double y = Math.toRadians(pl.getLocation().getYaw()); return new Vector(-Math.sin(y), 0, Math.cos(y)); }
    static Vector flat(Vector v) { Vector f = v.clone().setY(0); return f.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : f.normalize(); }
    static Location chest(Entity e) { return e.getLocation().add(0, e.getHeight() * 0.55, 0); }
    /** Same x/z, y = top of the ground under it (8 blocks down at most). */
    static Location ground(Location at) { Location g = at.clone(); g.setY(Telegraphs.ground(at)); return g; }
    /** Hand point: in front of the eyes, a little right and down (where casts leave the hand). */
    Location hand() {
        Vector f = look(), right = flat().crossProduct(new Vector(0, 1, 0)).multiply(-1);
        return pl.getEyeLocation().add(f.multiply(0.6)).add(right.multiply(0.3)).add(0, -0.3, 0);
    }
    static boolean passable(Location at) { Block b = at.getBlock(); return b.isPassable() && !b.isLiquid(); }
    /** Last free point along dir up to dist from `from` (stops before solid blocks). */
    static Location reach(Location from, Vector dir, double dist) {
        var hit = from.getWorld().rayTraceBlocks(from, dir, dist, FluidCollisionMode.NEVER, true);
        if (hit == null) return from.clone().add(dir.clone().normalize().multiply(dist));
        return hit.getHitPosition().toLocation(from.getWorld()).subtract(dir.clone().normalize().multiply(0.4));
    }
    /** Safe standing point for a teleport near `at` (2 free blocks, ground below within 3), or null. */
    static Location stand(Location at) {
        for (int dy = 0; dy >= -3; dy--) {
            Location l = at.clone().add(0, dy, 0); l.setY(Math.floor(l.getY()));
            if (passable(l) && passable(l.clone().add(0, 1, 0)) && !l.clone().add(0, -1, 0).getBlock().isPassable()) return l;
        }
        Location l = at.clone(); l.setY(Math.floor(l.getY()));
        return passable(l) && passable(l.clone().add(0, 1, 0)) ? l : null;
    }

    // ------------------------------------------------------------------ time
    /** Runs `r` after `ticks` (0 = next tick) unless the caster left. */
    void later(int ticks, Runnable r) {
        plugin.ticker().add(new Ticker.Tick() {
            int age;
            @Override public boolean tick() { if (!pl.isOnline()) return false; if (age++ < ticks) return true; r.run(); return false; }
            @Override public String kind() { return "nova"; }
        });
    }
    /** body(age) every tick from 0 until it returns false, the caster leaves or maxTicks pass; then end.run(). */
    void loop(int maxTicks, IntPredicate body, Runnable end) {
        plugin.ticker().add(new Ticker.Tick() {
            int age; boolean done;
            @Override public boolean tick() {
                if (!pl.isOnline() || age > maxTicks) return false;
                return body.test(age++);
            }
            @Override public void stop() { if (!done) { done = true; if (end != null) end.run(); } }
            @Override public String kind() { return "nova"; }
        });
    }

    // ------------------------------------------------------------------ player messages
    void warn(String text) { ChacaLink.notify(pl, "warning", "magic-mythic:" + text, text, "", 2000); }
    void info(String text) { ChacaLink.notify(pl, "system", "magic-mythic:" + text, text, "", 1500); }
    /** Gives back mana the Bridge already took (e.g. a refused cast). Next tick: the Bridge charges after the skill. */
    void refund(double mana) { if (mana > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, mana)); }
    void clearCooldown() { Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell)); }

    // ------------------------------------------------------------------ travelling shot
    /**
     * One server-authoritative shot: moves `speed` blocks/tick along `dir` (homing / gravity / ground-hugging optional),
     * tests blocks and enemies every ≤0.4 block, plays `system` (projectile role) on the way.
     * onHit(target, point) returns true to keep flying (pierce); onEnd(point, wall) runs once where it stops.
     */
    final class Shot implements Ticker.Tick {
        Location pos; Vector dir; double speed, range, radius = 0.6, gravity = 0, turn = 0; int rehit = 0, life = 0, maxLife = 400;
        LivingEntity homing; boolean groundHug = false, passWalls = false;
        String system; GameplayVisual visual;
        BiPredicate<LivingEntity, Location> onHit = (t, at) -> false;
        BiConsumer<Location, Boolean> onEnd = (at, wall) -> {};
        BiConsumer<Location, Vector> onStep = null;
        final Map<UUID, Integer> hitAt = new HashMap<>();
        double travelled; boolean ended;

        Shot(Location from, Vector dir, double speed, double range, String system) {
            this.pos = from.clone(); this.dir = dir.clone().normalize(); this.speed = speed; this.range = range; this.system = system;
        }
        Shot radius(double r) { radius = r; return this; }
        Shot homing(LivingEntity t, double turn) { homing = t; this.turn = turn; return this; }
        Shot gravity(double g) { gravity = g; return this; }
        Shot ground() { groundHug = true; return this; }
        Shot ghost() { passWalls = true; return this; }
        Shot rehit(int ticks) { rehit = ticks; return this; }
        Shot hit(BiPredicate<LivingEntity, Location> f) { onHit = f; return this; }
        Shot end(BiConsumer<Location, Boolean> f) { onEnd = f; return this; }
        Shot step(BiConsumer<Location, Vector> f) { onStep = f; return this; }
        Shot fire() {
            if (system != null && !system.isBlank()) visual = shot(system, pos, dir);
            plugin.ticker().add(this); return this;
        }
        @Override public String kind() { return "nova"; }
        @Override public void stop() { if (visual != null) { visual.close(); visual = null; } if (!ended) { ended = true; onEnd.accept(pos.clone(), false); } }
        @Override public boolean tick() {
            if (!pl.isOnline() || pos.getWorld() == null || ++life > maxLife) return false;
            if (homing != null && homing.isValid() && turn > 0) {
                Vector to = chest(homing).toVector().subtract(pos.toVector());
                if (to.lengthSquared() > 1e-4) dir = dir.multiply(1 - turn).add(to.normalize().multiply(turn)).normalize();
            }
            if (gravity != 0) { Vector v = dir.clone().multiply(speed).add(new Vector(0, -gravity, 0)); speed = v.length(); dir = v.normalize(); }
            int n = Math.max(1, (int) Math.ceil(speed / 0.4)); double st = speed / n;
            for (int k = 0; k < n; k++) {
                Location prev = pos.clone();
                pos.add(dir.clone().multiply(st)); travelled += st;
                if (groundHug) {
                    double g = Telegraphs.ground(pos.clone().add(0, 1, 0));
                    if (Math.abs(g - (pos.getY() - 0.05)) <= 1.2) pos.setY(g + 0.05);
                }
                if (!plugin.protection().pair(pl, pos) || !pos.getWorld().isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4)) return finish(false);
                if (!passWalls) {
                    var wall = pos.getWorld().rayTraceBlocks(prev, dir, st, FluidCollisionMode.NEVER, true);
                    if (wall != null && wall.getHitBlock() != null) {
                        var at = wall.getHitPosition().subtract(dir.clone().multiply(0.05));
                        pos.setX(at.getX()); pos.setY(at.getY()); pos.setZ(at.getZ());
                        return finish(true);
                    }
                }
                for (Entity e : pos.getWorld().getNearbyEntities(pos, radius + 1.5, radius + 1.5, radius + 1.5)) {
                    if (!enemy(e) || Targets.closestDistance(e, pos) > radius) continue;
                    Integer last = hitAt.get(e.getUniqueId());
                    if (last != null && (rehit <= 0 || life - last < rehit)) continue;
                    hitAt.put(e.getUniqueId(), life);
                    if (!onHit.test((LivingEntity) e, pos.clone())) return finish(false);
                }
                if (travelled >= range) return finish(false);
            }
            if (visual != null) visual.move(pos, dir);
            if (onStep != null) onStep.accept(pos.clone(), dir.clone());
            return true;
        }
        private boolean finish(boolean wall) {
            if (visual != null) { visual.move(pos, dir); visual.close(); visual = null; }
            if (!ended) { ended = true; onEnd.accept(pos.clone(), wall); }
            return false;
        }
    }
    Shot shotFrom(Location from, Vector dir, double speed, double range, String system) { return new Shot(from, dir, speed, range, system); }
}
