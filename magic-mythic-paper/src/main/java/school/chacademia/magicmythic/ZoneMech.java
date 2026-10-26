package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

/**
 * chazone - lasting area.
 *  center=target|caster follow=false r=3 ticks=100 period=20 max=16
 *  mult/flat (damage per pulse) effect ignite suck (pull toward center per pulse) lift (upward per pulse)
 *  heal=0 (health per pulse to allies, caster included)
 *  wall=0 (width; makes a wall facing the caster, depth = r, height=3)
 *  trap=true trapr=3 trapmult=1 (explode once when an enemy steps within r, then end)
 *  key=name limit=2 (keep at most N zones with this key per caster; oldest removed)
 *  knock up (push away from the centre on each pulse hit)  pick=random (with max: random enemies per pulse, not nearest)
 *  solid=true (with wall: a barrier - enemies are pushed out of the wall box and hostile projectiles entering it vanish;
 *              no real blocks)  height=3 (wall height)
 *  manaregen=1.1 (players inside get this mana regeneration multiplier while inside; same spell never stacks)
 *  spell=id (VFX events: external:strike at each pulse hit, external:hit where a projectile hits the barrier)
 */
final class ZoneMech extends Mech {
    ZoneMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        String key = p.s("key", "");
        int limit = p.i("limit", 0);
        if (!key.isBlank() && limit > 0) {
            var mine = new ArrayList<Zone>();
            for (Zone z : Zone.ACTIVE) if (z.owner.equals(c.caster().getUniqueId()) && z.key.equals(key)) mine.add(z);
            while (mine.size() >= limit) { Zone oldest = mine.removeFirst(); oldest.dead = true; }
        }
        var zone = new Zone(c, center(c).clone(), c.caster().getLocation().getDirection(), key);
        if (!plugin.ticker().add(zone)) return false;
        Zone.ACTIVE.add(zone);
        return true;
    }

    private final class Zone implements Ticker.Tick {
        static final List<Zone> ACTIVE = new ArrayList<>();
        final Ctx c; final Location center; final Vector facing; final UUID owner; final String key;
        final int ticks = Math.clamp(p.i("ticks", 100), 1, 20 * 600), period = Math.max(1, p.i("period", 20));
        final double r = Math.clamp(p.d("r", 3), 0.5, 16), wall = p.d("wall", 0);
        final boolean follow = p.b("follow", false), trap = p.b("trap", false);
        int age = 0; boolean dead = false;

        Zone(Ctx c, Location center, Vector facing, String key) {
            this.c = c; this.center = center; this.facing = facing; this.owner = c.caster().getUniqueId(); this.key = key;
        }
        @Override public String kind() { return "zone"; }
        final Set<UUID> regen = new HashSet<>();
        final String regenKey = "magicmythic:" + (p.s("spell", "").isBlank() ? "zone" : p.s("spell", ""));
        /** #79: a trap's armed loop ends only on external:trigger (exploded) or expire (removed early: limit, region, caster gone). */
        boolean fired;
        @Override public void stop() {
            if (trap && !fired) { fired = true; vfx("expire", center, null); }
            ACTIVE.remove(this);
            for (UUID id : regen) if (ACTIVE.stream().noneMatch(z -> z != this && z.regen.contains(id) && z.regenKey.equals(regenKey))) Links.regen(id, regenKey, 1);
            regen.clear();
        }
        private void vfx(String event, Location at, UUID target) {
            String spell = p.s("spell", "");
            if (!spell.isBlank() && c.caster() instanceof Player pl) Links.vfx(pl, spell, event, at, target);
        }

        @Override public boolean tick() {
            if (dead || age++ >= ticks || !c.caster().isValid()) return false;
            if (follow) { var l = c.caster().getLocation(); center.setX(l.getX()); center.setY(l.getY()); center.setZ(l.getZ()); }
            if(!plugin.protection().pair(c.caster(),center))return false;
            if (trap) return tickTrap();
            if (p.b("solid", false) && wall > 0) barrier();
            double regenMult = p.d("manaregen", 0);
            if (regenMult > 0 && (age - 1) % 10 == 0) {
                var inside = new HashSet<UUID>();
                for (Player ally : plugin.targets().alliesNear(c.caster(), center, r, 64)) inside.add(ally.getUniqueId());
                for (UUID id : inside) if (regen.add(id)) Links.regen(id, regenKey, regenMult);
                for (UUID id : new ArrayList<>(regen)) if (!inside.contains(id)) {
                    regen.remove(id);
                    if (ACTIVE.stream().noneMatch(z -> z != this && z.regen.contains(id) && z.regenKey.equals(regenKey))) Links.regen(id, regenKey, 1);
                }
            }
            if ((age - 1) % period != 0) return true;
            MagicImpactEvent.fire(c.caster(), p.s("element", ""), center, wall > 0 ? Math.max(wall / 2, r) : r, MagicImpactEvent.Kind.ZONE);
            var targets = plugin.targets();
            double heal = p.d("heal", 0);
            if (heal > 0) for (Player ally : targets.alliesNear(c.caster(), center, r, 16)) {
                targets.heal(c.caster(), ally, heal, "");
            }
            double dmg = c.dmg(p), suck = p.d("suck", 0), lift = p.d("lift", 0);
            int ignite = p.i("ignite", 0);
            double search = wall > 0 ? Math.max(wall, r) : r;
            if (dmg <= 0 && p.d("knock", 0) == 0 && suck == 0 && lift == 0 && p.s("effect", "").isBlank() && p.s("status", "").isBlank() && ignite <= 0) return true;
            var found = new ArrayList<>(targets.enemiesNear(c.caster(), center, search + 1, p.s("pick", "").equals("random") ? 64 : p.i("max", 16)));
            if (p.s("pick", "").equals("random")) { Collections.shuffle(found); found = new ArrayList<>(found.subList(0, Math.min(found.size(), p.i("max", 16)))); }
            for (LivingEntity t : found) {
                if (wall > 0 && !Geometry.inWall(center.toVector(), facing, t.getLocation().toVector(), wall, Math.max(1, r), p.d("height", 3))) continue;
                if (wall <= 0 && Targets.closestDistance(t, center) > r) continue;
                if(!targets.hurt(c.caster(), t, dmg, p.s("element", "")))continue;
                plugin.reactions().hit(c.caster(), t, p.s("element", ""), dmg);
                vfx("external:strike", t.getLocation(), t.getUniqueId());
                targets.knock(t, center, facing, p.d("knock", 0), p.d("up", 0));
                targets.effects(c.caster(), t, p.effects("effect"));
                targets.sideEffects(c.caster(), t, p);
                if (suck > 0) {
                    Vector v = center.toVector().subtract(t.getLocation().toVector()).setY(0);
                    if (v.length() > 0.6) t.setVelocity(v.normalize().multiply(Math.min(suck, v.length() * 0.5)).setY(0.05));
                }
                if (lift > 0) t.setVelocity(t.getVelocity().setY(lift));
            }
            return true;
        }

        /** Stone wall: keep enemies out of the wall box and stop hostile projectiles there. */
        private void barrier() {
            double height = p.d("height", 3), depth = Math.max(1, r);
            Vector f = facing.clone().setY(0);
            if (f.lengthSquared() < 1e-6) return;
            f.normalize();
            for (Entity e : center.getWorld().getNearbyEntities(center, wall / 2 + 2, height + 1, wall / 2 + 2)) {
                Vector pos = e.getLocation().toVector();
                if (e instanceof Projectile pr) {
                    if (!Geometry.inWall(center.toVector(), facing, pos, wall + 0.4, depth + 0.6, height)) continue;
                    if (pr.getShooter() instanceof Entity shooter && (shooter.equals(c.caster()) || shooter instanceof Player && !plugin.targets().enemy(c.caster(), shooter))) continue;
                    vfx("external:hit", pr.getLocation(), null);
                    pr.remove();
                    continue;
                }
                if (!(e instanceof LivingEntity le) || !plugin.targets().enemy(c.caster(), le)) continue;
                if (!Geometry.inWall(center.toVector(), facing, pos, wall + 0.6, depth + 0.8, height)) continue;
                double side = pos.clone().subtract(center.toVector()).dot(f) >= 0 ? 1 : -1;
                Vector out = f.clone().multiply(side * 0.45);
                le.setVelocity(new Vector(out.getX(), Math.min(0.1, le.getVelocity().getY()), out.getZ()));
            }
        }

        private boolean tickTrap() {
            var targets = plugin.targets();
            var near = targets.enemiesNear(c.caster(), center, r, 1);
            if (near.isEmpty()) return true;
            double dmg = c.damage() * p.d("trapmult", p.d("mult", 1)) + p.d("flat", 0);
            fired = true; vfx("external:trigger", center, null); // #79: explode branch stops the armed loop
            for (LivingEntity t : targets.enemiesNear(c.caster(), center, p.d("trapr", 3), p.i("max", 16))) {
                if(!targets.hurt(c.caster(), t, dmg, p.s("element", "")))continue;
                plugin.reactions().hit(c.caster(), t, p.s("element", ""), dmg);
                targets.effects(c.caster(), t, p.effects("effect"));
                targets.sideEffects(c.caster(), t, p);
                targets.knock(t, center, facing, p.d("knock", 0.6), p.d("up", 0.3));
            }
            return false;
        }
    }
}
