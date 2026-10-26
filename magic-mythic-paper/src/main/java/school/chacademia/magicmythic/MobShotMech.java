package school.chacademia.magicmythic;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * chamobshot - a mob's light basic projectile (no red warning: it can be seen and dodged). Owner 2026-10-19:
 * "원거리 몹은 가벼운 투사체". The look is a PortableVFX projectile-role system moved by the server (startCast /
 * updateCast), the hit is checked here; nothing is spawned in the world.
 *
 *   count=1 (max 5)  spread=12 (degrees between shots, fanned around the aim)  interval=3 (ticks between shots)
 *   speed=0.8 (blocks/tick)  range=20  radius=0.7  homing=0 (0..0.5: how hard it turns toward the target each tick)
 *   rise=0 (extra upward start speed for arcing shots, blocks/tick; gravity 0.04 when rise > 0)
 *   fromy=1.4 fromz=0.6 (launch point above / in front of the caster's feet)
 *   flat / mult (damage, Ctx.dmg)  element  status=id:ticks[:power],...  ignite  knock up
 *   hits=players|enemies (default players)
 *   vfx=pack/system (projectile role, follows the shot)  hitvfx=pack/system (static, played where it hits or stops)
 *   tint=FFFFFF  burst=0 (radius: when the shot lands or hits, everything inside takes the hit instead - thrown rocks, bottles)
 *   orbit=0 (ticks the shots first circle the caster at 1.6 blocks before flying - foxfire) 
 *   count up to 8, spread up to 360 (radial bursts)
 *   Player AIM spells (no entity target): homing / orbit end aim at the aimed point (until a shot is within 1.5 blocks).
 *
 * Server cost: one Ticker entry per flying shot (removed on hit, block or range); a shot lives at most range/speed ticks.
 */
final class MobShotMech extends Mech {
    MobShotMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        LivingEntity caster = c.caster();
        int count = Math.clamp(p.i("count", 1), 1, 8), interval = Math.clamp(p.i("interval", 3), 0, 20);
        double spread = Math.clamp(p.d("spread", 12), 0, 360);
        double dmg = c.dmg(p);
        if (count == 1 || interval == 0) {
            for (int k = 0; k < count; k++) launch(c, offset(k, count, spread), dmg);
            return true;
        }
        for (int k = 0; k < count; k++) {
            final double off = offset(k, count, spread);
            if (k == 0) { launch(c, off, dmg); continue; }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (caster.isValid() && !caster.isDead()) launch(c, off, dmg);
            }, (long) k * interval);
        }
        return true;
    }

    /** The entity the shots go for: never the caster itself (player SELF spells run the skill @self). */
    static Entity aimed(Ctx c) { return c.target() == null || c.target().equals(c.caster()) ? null : c.target(); }

    /** Fan the shots symmetrically around the aim: 0, +s, -s, +2s, -2s ... (odd counts) or ±s/2 pairs (even). */
    static double offset(int k, int count, double spread) {
        if (count % 2 == 1) return k == 0 ? 0 : ((k + 1) / 2) * spread * (k % 2 == 1 ? 1 : -1);
        int pair = k / 2; double v = (pair + 0.5) * spread; return k % 2 == 0 ? v : -v;
    }

    private void launch(Ctx c, double yawOffset, double dmg) {
        LivingEntity caster = c.caster();
        Location feet = caster.getLocation();
        Location aim = aimed(c) instanceof LivingEntity le ? le.getLocation().add(0, le.getHeight() * 0.55, 0) : c.at();
        Vector flat = aim.toVector().subtract(feet.toVector()).setY(0);
        if (flat.lengthSquared() < 1e-4) flat = feet.getDirection().setY(0);
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1);
        flat.normalize();
        Location start = feet.clone().add(flat.clone().multiply(p.d("fromz", 0.6))).add(0, p.d("fromy", 1.4), 0);
        Vector dir = aim.toVector().subtract(start.toVector());
        if (dir.lengthSquared() < 1e-4) dir = flat.clone();
        dir.normalize();
        if (yawOffset != 0) dir.rotateAroundY(Math.toRadians(yawOffset));
        // #35: a player on a broom - same CarrierShot rule as chabolt (start ahead by flight speed x ping, inherit forward speed),
        // otherwise the shot leaves from where the server last saw the rider and looks like it comes out behind them.
        double extra = 0;
        Vector lead = p.i("orbit", 0) > 0 ? null : CarrierShot.lead(caster);
        if (lead != null) {
            double len = lead.length();
            var wall = start.getWorld().rayTraceBlocks(start, lead.clone().multiply(1 / len), len, org.bukkit.FluidCollisionMode.NEVER, true);
            double go = wall == null ? len : Math.max(0, wall.getHitPosition().distance(start.toVector()) - 0.3);
            start.add(lead.clone().multiply(go / len));
            extra = CarrierShot.extra(caster, dir);
        }
        plugin.ticker().add(new Shot(c, start, dir, dmg, extra));
    }

    private final class Shot implements Ticker.Tick {
        final Ctx c; final Location pos; Vector vel; final double dmg;
        final double speed, range = Math.clamp(p.d("range", 20), 2, 48),
            radius = Math.clamp(p.d("radius", 0.7), 0.2, 3), homing = Math.clamp(p.d("homing", 0), 0, 0.5), rise = Math.clamp(p.d("rise", 0), 0, 1);
        final boolean playersOnly = !p.s("hits", "players").toLowerCase(Locale.ROOT).startsWith("e");
        final double burst = Math.clamp(p.d("burst", 0), 0, 6); final int orbit = Math.clamp(p.i("orbit", 0), 0, 60);
        double travelled; GameplayVisual visual; boolean done; int age; final double orbitPhase;
        /** Player AIM spells have no entity target: home on the aimed point instead (until the shot reaches it). */
        final Location aimPoint; boolean aimReached;

        Shot(Ctx c, Location start, Vector dir, double dmg, double carrierSpeed) {
            this.speed = Math.min(Math.clamp(p.d("speed", 0.8), 0.1, 3) + Math.max(0, carrierSpeed), 4);
            this.c = c; this.pos = start.clone(); this.dmg = dmg; this.orbitPhase = Math.atan2(dir.getX(), dir.getZ());
            Location at = aimed(c) instanceof LivingEntity ? null : c.at();
            this.aimPoint = at != null && at.getWorld() == start.getWorld() && at.distanceSquared(c.caster().getLocation()) > 4 ? at.clone().add(0, burst > 0 ? 0 : 1.0, 0) : null;   // a ground aim point: fly at chest height over it
            this.vel = dir.clone().multiply(speed); if (rise > 0) vel.setY(vel.getY() + rise);
            String id = TelegraphMech.vfxId(p.s("vfx", ""));
            if (!id.isEmpty()) visual = GameplayVisual.cast(null, id, pos, vel.clone().normalize());
        }
        @Override public String kind() { return "mobshot"; }

        @Override public boolean tick() {
            if (done || pos.getWorld() == null || !c.caster().isValid()) return false;
            if (age++ < orbit) {   // circle the caster first, then fly from where it is
                double a = orbitPhase + age * 0.35; var cl = c.caster().getLocation();
                pos.setX(cl.getX() + Math.sin(a) * 1.6); pos.setY(cl.getY() + p.d("fromy", 1.4) + Math.sin(age * 0.5) * 0.15); pos.setZ(cl.getZ() + Math.cos(a) * 1.6);
                if (visual != null) visual.move(pos, new Vector(Math.cos(a), 0, -Math.sin(a)));
                if (age == orbit) {
                    Vector goal = aimed(c) instanceof LivingEntity t ? t.getLocation().add(0, t.getHeight() * 0.55, 0).toVector() : aimPoint != null ? aimPoint.toVector() : null;
                    if (goal != null && goal.distanceSquared(pos.toVector()) > 1e-4) vel = goal.subtract(pos.toVector()).normalize().multiply(speed);
                }
                return true;
            }
            if (homing > 0) {
                Vector goal = null;
                if (aimed(c) instanceof LivingEntity t && t.isValid() && !t.isDead()) goal = t.getLocation().add(0, t.getHeight() * 0.55, 0).toVector();
                else if (aimPoint != null && !aimReached) {
                    goal = aimPoint.toVector();
                    if (goal.distanceSquared(pos.toVector()) < 2.25) { aimReached = true; goal = null; }
                }
                Vector to = goal == null ? null : goal.subtract(pos.toVector());
                if (to != null && to.lengthSquared() > 1e-4) vel = vel.clone().normalize().multiply(1 - homing).add(to.normalize().multiply(homing)).normalize().multiply(speed);
            }
            if (rise > 0) vel.setY(vel.getY() - 0.04);
            double len = vel.length(); int steps = Math.max(1, (int) Math.ceil(len / 0.4)); Vector step = vel.clone().multiply(1.0 / steps);
            for (int i = 0; i < steps; i++) {
                Location prev = pos.clone();
                pos.add(step); travelled += len / steps;
                if (!pos.getWorld().isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4) || !plugin.protection().pair(c.caster(), pos)) return end(false);
                // real collision shapes (slabs, fences, panes), same rule as chabolt
                if (len > 1e-6) {
                    var wall = pos.getWorld().rayTraceBlocks(prev, step, len / steps, org.bukkit.FluidCollisionMode.NEVER, true);
                    if (wall != null && wall.getHitBlock() != null) {
                        var at = wall.getHitPosition().subtract(step.clone().normalize().multiply(0.05));
                        pos.setX(at.getX()); pos.setY(at.getY()); pos.setZ(at.getZ());
                        return end(true);
                    }
                }
                for (Entity e : pos.getWorld().getNearbyEntities(pos, radius + 1, radius + 1, radius + 1)) {
                    if (!(e instanceof LivingEntity t) || !valid(t) || Targets.closestDistance(e, pos) > radius) continue;
                    if (burst <= 0) strike(t);
                    return end(true);
                }
                if (travelled >= range) return end(false);
            }
            if (visual != null) visual.move(pos, vel.clone().normalize());
            return true;
        }

        private boolean valid(LivingEntity t) {
            return !t.equals(c.caster()) && (!playersOnly || t instanceof Player) && plugin.targets().enemy(c.caster(), t);
        }
        private void strike(LivingEntity t) {
            if (plugin.targets().hurt(c.caster(), t, dmg, p.s("element", ""))) {
                plugin.targets().sideEffects(c.caster(), t, p);
                plugin.targets().knock(t, pos.clone().subtract(vel), vel, p.d("knock", 0), p.d("up", 0));
            }
        }
        private boolean end(boolean impact) {
            done = true;
            if (impact && burst > 0) for (Entity e : pos.getWorld().getNearbyEntities(pos, burst, burst, burst))
                if (e instanceof LivingEntity t && valid(t) && Targets.closestDistance(e, pos) <= burst) strike(t);
            if (visual != null) { visual.close(); visual = null; }
            String hit = TelegraphMech.vfxId(p.s("hitvfx", ""));
            if (impact && !hit.isEmpty()) {
                float yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
                plugin.reactions().vfx().effect(hit, pos.clone(), 1, yaw, 20, TelegraphMech.tint(p.s("tint", "FFFFFF")));
            }
            return false;
        }

        @Override public void stop() { if (visual != null) { visual.close(); visual = null; } }
    }
}
