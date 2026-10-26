package school.chacademia.magicmythic;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;

/**
 * chasummon{type=...} - things that stay for a while: companions, objects, lights.
 *
 *  golem ticks=1200 range=8 reach=2.2 period=36 windup=12 hitr=1.5 knock=0.6 - a virtual earth golem walks with the
 *        caster and punches the nearest enemy (104 그놈; the body is the VFX model)
 *  emissary ticks=600 r=8 amount=6 period=40 - a little star heals the most hurt ally (caster included) every period (164)
 *  pets ticks=600        - a new tamed wolf and cat owned by the caster; they die when the time is up (154 소환장)
 *  conduit count=5 r=6 ticks=400 amount=50 ratio=0.5 pickup=1.3 - mana stones around the caster; the caster or an ally
 *        who walks in takes amount + ratio x caster 마력 mana (157 마력 전선)
 *  light ticks=1200 level=15 - a light that follows the caster's head; real light (light block changes sent to nearby
 *        players, nothing is placed on the server) (148 반딧불 표식)
 *  ladder h=8 ticks=600 range=5 - the aimed wall becomes climbable: forward/jump climbs, sneak holds, no fall damage (103)
 *  spring uses=3 ticks=600 up=1.1 forward=0.5 - a launch pad at the aim point; stepping on it throws the player up and
 *        toward the caster's facing at cast time; the next fall after a launch does no damage (127 용수철 지반)
 */
final class SummonMech extends Mech {
    SummonMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    private static final Map<UUID, Long> NO_FALL = new HashMap<>();
    private static final Set<UUID> SUMMONS = new HashSet<>();
    private static long clock = 0;
    static final String SUMMON_TAG = "chacademia_spell_summon";

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false;
        if (!plugin.protection().pair(pl, pl.getLocation())) return false;
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "golem" -> golem(pl, c);
            case "emissary" -> emissary(pl);
            case "pets" -> pets(pl);
            case "conduit" -> conduit(pl);
            case "light" -> light(pl);
            case "ladder" -> ladder(pl, c.at());
            case "spring" -> spring(pl, c.at());
            default -> false;
        };
    }

    private void vfx(Player pl, String event, Location at, UUID target) {
        String spell = p.s("spell", "");
        if (!spell.isBlank()) Links.vfx(pl, spell, event, at, target);
    }

    // ------------------------------------------------------------------ 104 golem
    private boolean golem(Player pl, Ctx c) {
        int ticks = Math.clamp(p.i("ticks", 1200), 20, 20 * 600), period = Math.max(10, p.i("period", 36)), windup = Math.clamp(p.i("windup", 12), 0, 60);
        double range = p.d("range", 8), reach = p.d("reach", 2.2), hitR = p.d("hitr", 1.5), dmg = c.dmg(p), speed = Math.clamp(p.d("speed", 0.06), 0.01, 0.5);
        return plugin.ticker().add(new Ticker.Tick() {
            Location pos = pl.getLocation().add(flat(pl.getLocation()).multiply(2));
            LivingEntity target; int age = 0, nextAttack = 0, impactAt = -1; Location hit;boolean walking;
            public String kind() { return "summon"; }
            public void stop() { if (pl.isOnline()) vfx(pl, "external:dismiss", pos, null); }
            public boolean tick() {
                if (!pl.isOnline() || pl.isDead() || age++ >= ticks || !pl.getWorld().equals(pos.getWorld())) return false;
                if (!plugin.protection().pair(pl, pos)) return false;
                if(age==1)vfx(pl,"external:summon",pos,null);
                Location previous=pos.clone();
                if (impactAt == age && hit != null) {
                    vfx(pl, "external:impact", hit, null);
                    for (LivingEntity t : plugin.targets().enemiesNear(pl, hit, hitR, 8))
                        if (plugin.targets().hurt(pl, t, dmg)) plugin.targets().knock(t, pos, pos.getDirection(), p.d("knock", 0.6), 0.25);
                    MagicImpactEvent.fire(pl, p.s("element", "earth"), hit, hitR, MagicImpactEvent.Kind.HIT); // result unused (HIT contract: damage is never cancelled)
                }
                if (age % 10 == 1 && (target == null || !target.isValid() || target.isDead() || target.getLocation().distanceSquared(pl.getLocation()) > range * range * 2.25)) {
                    var near = plugin.targets().enemiesNear(pl, pl.getLocation(), range, 1);
                    target = near.isEmpty() ? null : near.getFirst();
                }
                Location goal = target != null ? target.getLocation() : pl.getLocation().subtract(flat(pl.getLocation()).multiply(1.5));
                Vector to = goal.toVector().subtract(pos.toVector()).setY(0);
                double dist = to.length();
                if (pos.distanceSquared(pl.getLocation()) > Math.pow(range + 6, 2)) { pos = pl.getLocation(); return true; } // leash
                if (target != null && dist <= reach) {
                    pos.setDirection(to.lengthSquared() > 1e-4 ? to : pos.getDirection());
                    if (age >= nextAttack) {
                        nextAttack = age + period;
                        hit = pos.clone().add(flat(pos).multiply(1.5));
                        impactAt = age + windup;
                        vfx(pl, "external:attack", pos, target.getUniqueId());
                    }
                } else if (dist > (target != null ? reach * 0.8 : 1.2)) {
                    Vector step = to.normalize().multiply(Math.min(dist, speed));
                    pos.add(step);
                    pos.setDirection(step);
                    Block ground = pos.getBlock();
                    if (!ground.isPassable()) pos.setY(ground.getY() + 1);
                    else if (ground.getRelative(BlockFace.DOWN).isPassable()) pos.setY(pos.getY() - 0.25);
                }
                boolean moving=previous.distanceSquared(pos)>0.00001;
                if(moving!=walking){walking=moving;vfx(pl,moving?"external:walk":"external:idle",pos,null);}
                vfx(pl,"external:pose",pos,null);
                return true;
            }
        });
    }

    /** Horizontal facing from yaw (never NaN, even looking straight up/down). */
    static Vector flat(Location l) {
        double yaw = Math.toRadians(l.getYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    // ------------------------------------------------------------------ 164 emissary
    private boolean emissary(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 600), 20, 20 * 600), period = Math.max(5, p.i("period", 40));
        double r = p.d("r", 8), amount = p.d("amount", 6);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0, next = 10;
            public String kind() { return "summon"; }
            public void stop() { if (pl.isOnline()) vfx(pl, "external:dismiss", pl.getLocation(), null); }
            public boolean tick() {
                if (!pl.isOnline() || pl.isDead() || age++ >= ticks || !plugin.protection().at(pl.getLocation())) return false;
                if (age < next) return true;
                Player worst = null; double ratio = 1;
                for (Player ally : plugin.targets().alliesNear(pl, pl.getLocation(), r, 64)) {
                    double q = ally.getHealth() / Targets.maxHealth(ally);
                    if (q < ratio) { ratio = q; worst = ally; }
                }
                if (worst == null) { next = age + 10; return true; }
                Targets.heal(pl, worst, amount);
                vfx(pl, "external:heal", worst.getLocation(), worst.getUniqueId());
                next = age + period; // wait after each heal
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 154 pets
    private boolean pets(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 600), 20, 20 * 600);
        Location at = pl.getLocation();
        var spawned = new ArrayList<Tameable>();
        for (Class<? extends Tameable> kind : List.of(Wolf.class, Cat.class)) {
            Location spot = at.clone().add(ThreadLocalRandom.current().nextDouble(-1.5, 1.5), 0, ThreadLocalRandom.current().nextDouble(-1.5, 1.5));
            if (!spot.getBlock().isPassable()) spot = at.clone();
            Tameable t = at.getWorld().spawn(spot, kind, e -> {
                e.setTamed(true); e.setOwner(pl); e.setAdult(); e.setPersistent(false); e.setRemoveWhenFarAway(false);
                e.addScoreboardTag(SUMMON_TAG);
            });
            SUMMONS.add(t.getUniqueId());
            spawned.add(t);
            vfx(pl, kind == Wolf.class ? "external:summonWolf" : "external:summonCat", t.getLocation(), t.getUniqueId());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Tameable t : spawned) {
                if (!t.isValid()) { SUMMONS.remove(t.getUniqueId()); continue; }
                if (pl.isOnline()) vfx(pl, t instanceof Wolf ? "external:deathWolf" : "external:deathCat", t.getLocation(), t.getUniqueId());
                SUMMONS.remove(t.getUniqueId());
                t.remove(); // no vanilla "died" message to the owner
            }
        }, ticks);
        return true;
    }

    // ------------------------------------------------------------------ 157 conduit
    private boolean conduit(Player pl) {
        int count = Math.clamp(p.i("count", 5), 1, 12), ticks = Math.clamp(p.i("ticks", 400), 20, 20 * 300);
        double r = p.d("r", 6), pickup = p.d("pickup", 1.3), amount = p.d("amount", 50) + p.d("ratio", 0.5) * Links.power(pl);
        var stones = new ArrayList<Location>();
        var rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count * 4 && stones.size() < count; i++) {
            double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(1.5, Math.max(1.6, r));
            Location s = pl.getLocation().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            Block b = s.getBlock();
            for (int k = 0; k < 3 && !b.isPassable(); k++) b = b.getRelative(BlockFace.UP);
            if (!b.isPassable() || !plugin.protection().pair(pl, b.getLocation())) continue;
            Location stone = b.getLocation().add(0.5, 1.2, 0.5);
            stones.add(stone);
            vfx(pl, "external:summon", stone, null);
        }
        if (stones.isEmpty()) return false;
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public String kind() { return "summon"; }
            public void stop() { if (pl.isOnline()) for (Location s : stones) vfx(pl, "external:expire", s, null); }
            public boolean tick() {
                if (!pl.isOnline() || age++ >= ticks || stones.isEmpty()) return false;
                if (age % 2 != 0) return true;
                for (Iterator<Location> it = stones.iterator(); it.hasNext(); ) {
                    Location s = it.next();
                    for (Player ally : plugin.targets().alliesNear(pl, s, pickup + 1, 4)) {
                        if (ally.getLocation().add(0, 1, 0).distanceSquared(s) > pickup * pickup) continue;
                        Links.add(ally, amount);
                        vfx(pl, "external:pickup", s, ally.getUniqueId());
                        it.remove();
                        break;
                    }
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 148 light
    private boolean light(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 1200), 20, 20 * 600), level = Math.clamp(p.i("level", 15), 1, 15);
        BlockData light = Material.LIGHT.createBlockData(d -> ((Levelled) d).setLevel(level));
        return plugin.ticker().add(new Ticker.Tick() {
            Block shown; int age = 0;
            public String kind() { return "summon"; }
            public void stop() { restore(); }
            private void restore() {
                if (shown == null) return;
                for (Player v : shown.getWorld().getPlayers()) if (v.getLocation().distanceSquared(shown.getLocation()) < 64 * 64) v.sendBlockChange(shown.getLocation(), shown.getBlockData());
                shown = null;
            }
            public boolean tick() {
                if (!pl.isOnline() || pl.isDead() || age++ >= ticks) return false;
                if (age % 2 != 0) return true;
                Block head = pl.getEyeLocation().add(0, 0.6, 0).getBlock();
                if (!head.getType().isAir()) head = pl.getEyeLocation().getBlock();
                boolean moved = !head.equals(shown);
                if (moved) restore();
                if (!head.getType().isAir()) return true; // nowhere to show the light this tick
                if (moved || age % 20 == 0) {
                    for (Player v : head.getWorld().getPlayers()) if (v.getLocation().distanceSquared(head.getLocation()) < 48 * 48) v.sendBlockChange(head.getLocation(), light);
                    shown = head;
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 103 ladder
    private boolean ladder(Player pl, Location aim) {
        var hit = pl.rayTraceBlocks(Math.clamp(p.d("range", 5), 1, 16), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return false;
        BlockFace face = hit.getHitBlockFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN) return false;
        Block wall = hit.getHitBlock();
        int h = Math.clamp(p.i("h", 8), 1, 16), height = 0;
        while (height < h && wall.getRelative(0, height, 0).getType().isSolid()) height++;
        if (height == 0) return false;
        Block base = wall.getRelative(face);
        if (!plugin.protection().pair(pl, base.getLocation())) return false;
        final int top = height;
        int ticks = Math.clamp(p.i("ticks", 600), 20, 20 * 300);
        vfx(pl, "external:grow", base.getLocation().add(0.5, 0, 0.5), null);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public String kind() { return "summon"; }
            public void stop() { if (pl.isOnline()) vfx(pl, "external:ladder_end", base.getLocation().add(0.5, 0, 0.5), null); }
            public boolean tick() {
                if (age++ >= ticks || !plugin.protection().at(base.getLocation())) return false;
                var box = new org.bukkit.util.BoundingBox(base.getX() - 0.1, base.getY() - 0.5, base.getZ() - 0.1, base.getX() + 1.1, base.getY() + top + 0.6, base.getZ() + 1.1);
                for (Entity e : base.getWorld().getNearbyEntities(box)) {
                    if (!(e instanceof Player climber) || climber.isFlying() || !plugin.targets().ally(pl, climber)) continue;
                    Vector v = climber.getVelocity();
                    var input = climber.getCurrentInput();
                    double vy = input.isSneak() ? 0 : (input.isForward() || input.isJump()) ? 0.2 : Math.max(v.getY(), -0.15);
                    climber.setVelocity(new Vector(v.getX(), vy, v.getZ()));
                    climber.setFallDistance(0);
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 127 spring
    private boolean spring(Player pl, Location aim) {
        Block ground = aim.getBlock();
        if (ground.isPassable()) ground = ground.getRelative(BlockFace.DOWN);
        if (ground.isPassable()) return false;
        Location pad = ground.getLocation().add(0.5, 1.0, 0.5);
        if (!plugin.protection().pair(pl, pad)) return false;
        Vector dir = flat(pl.getLocation());
        final Vector launch = dir.multiply(p.d("forward", 0.5)).setY(p.d("up", 1.1));
        int ticks = Math.clamp(p.i("ticks", 600), 20, 20 * 300);
        int[] uses = {Math.clamp(p.i("uses", 3), 1, 20)};
        vfx(pl, "external:place", pad, null);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0; final Map<UUID, Integer> cooldown = new HashMap<>();
            public String kind() { return "summon"; }
            public void stop() { if (pl.isOnline()) vfx(pl, "external:crumble", pad, null); }
            public boolean tick() {
                if (age++ >= ticks || uses[0] <= 0 || !plugin.protection().at(pad)) return false;
                for (Entity e : pad.getWorld().getNearbyEntities(pad, 0.9, 0.7, 0.9)) {
                    if (!(e instanceof Player jumper) || jumper.isFlying() || !plugin.targets().ally(pl, jumper)) continue;
                    if (cooldown.getOrDefault(jumper.getUniqueId(), -1) > age || jumper.getVelocity().getY() > 0.1) continue;
                    if (Math.abs(jumper.getLocation().getY() - pad.getY()) > 0.6) continue;
                    jumper.setVelocity(launch);
                    jumper.setFallDistance(0);
                    NO_FALL.put(jumper.getUniqueId(), clock + 200);
                    cooldown.put(jumper.getUniqueId(), age + 10);
                    vfx(pl, "external:launch", pad, jumper.getUniqueId());
                    if (--uses[0] <= 0) return false;
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ shared listeners
    static final class Hooks implements Listener {
        Hooks(MagicMythicPlugin plugin) {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> { clock++; if ((clock & 255) == 0) NO_FALL.values().removeIf(t -> t < clock); }, 1, 1);
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void fall(EntityDamageEvent e) {
            if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
            Long until = NO_FALL.remove(e.getEntity().getUniqueId());
            if (until != null && until >= clock) e.setCancelled(true);
        }
        @EventHandler public void death(EntityDeathEvent e) {
            if (!SUMMONS.remove(e.getEntity().getUniqueId())) return;
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }
    static void removeAll() {
        for (UUID id : new ArrayList<>(SUMMONS)) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        SUMMONS.clear();
    }
}
