package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

/**
 * chahit - instant hit.
 *  center=target|caster  r=0 (0: only the given target)  max=16  cone=0 (degrees, in front of caster, r = length)
 *  mult/flat (x Bridge damage)  knock up pull(toward caster) slam(downward speed)  ignite(ticks)
 *  effect=TYPE:ticks:amp,...  execute=0.2 (normal mobs die, players/bosses only below this health ratio)
 *  hpcost=0.5 hpscale=2.5 (spend own current health, add spent x scale to damage)  shield=ticks (disable shields)
 *  undead=true (only undead are hit: zombies, skeletons ...)  spell=id (VFX event external:hit on each target)
 */
final class HitMech extends Mech {
    HitMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        var targets = plugin.targets();
        if(!plugin.protection().pair(c.caster(),c.at()))return false;
        double dmg = c.dmg(p);
        double hpCost = p.d("hpcost", 0);
        if (hpCost > 0) {
            double hp = c.caster().getHealth();
            double spend = Math.max(0, hp * Math.clamp(hpCost, 0, 0.95));
            if (spend < 1) return false;
            c.caster().setHealth(Math.max(1, hp - spend));
            dmg += spend * p.d("hpscale", 1);
        }
        Location center = center(c);
        double r = p.d("r", 0);
        double cone = p.d("cone", 0);
        List<LivingEntity> hits;
        if (cone > 0) hits = inCone(c, r, cone, p.i("max", 16));
        else if (r <= 0) hits = c.target() != null && targets.enemy(c.caster(), c.target()) ? List.of((LivingEntity) c.target()) : List.of();
        else hits = targets.enemiesNear(c.caster(), center, r, p.i("max", 16));
        if (p.b("undead", false)) hits = hits.stream().filter(Targets::undead).toList();
        Location impactAt = cone > 0 ? c.caster().getEyeLocation().add(c.caster().getEyeLocation().getDirection().multiply(r / 2)) : center;
        boolean consumed = !MagicImpactEvent.fire(c.caster(), p.s("element", ""), impactAt, Math.max(cone > 0 ? r / 2 : r, 0.8), MagicImpactEvent.Kind.HIT);
        if (hits.isEmpty()) return result(true, consumed); // a consumed impact (gimmick took it) still counts as a successful hit
        // HIT cancel contract (MagicImpactEvent): damage below is applied whether or not a gimmick consumed the hit.
        String spell = p.s("spell", "");
        for (LivingEntity t : hits) {
            apply(c, t, center, dmg);
            if (!spell.isBlank() && c.caster() instanceof Player pl) Links.vfx(pl, spell, p.s("hitevent", "external:hit"), t.getLocation(), t.getUniqueId());
        }
        return true;
    }

    /** Cast success for a HIT: something was hit, or a gimmick consumed the impact as an input. */
    static boolean result(boolean hitsEmpty, boolean consumed) { return !hitsEmpty || consumed; }

    private List<LivingEntity> inCone(Ctx c, double length, double angle, int max) {
        Location eye = c.caster().getEyeLocation();
        Vector dir = eye.getDirection().setY(0).normalize();
        var out = new ArrayList<LivingEntity>();
        for (LivingEntity t : plugin.targets().enemiesNear(c.caster(), c.caster().getLocation(), length, 64)) {
            Vector to = t.getLocation().toVector().subtract(c.caster().getLocation().toVector()).setY(0);
            if (Geometry.inCone(dir, to, angle)) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    void apply(Ctx c, LivingEntity t, Location center, double dmg) {
        var targets = plugin.targets();
        double execute = p.d("execute", 0);
        if (execute > 0) {
            boolean protectedTarget = t instanceof Player || targets.boss(t);
            double ratio = t.getHealth() / Targets.maxHealth(t);
            if (!protectedTarget || ratio < execute) {
                // 전투 스펙 2026-10-26: 처형은 감소 0 (예전엔 방어구가 health+1 피해를 줄여 처형이 실패함).
                targets.execute(c.caster(), t, p.s("element", ""));
                // Never bypass a cancelled or reduced damage event with setHealth.
                return;
            }
        }
        if(!targets.hurt(c.caster(), t, dmg, p.s("element", "")))return;
        plugin.reactions().hit(c.caster(), t, p.s("element", ""), dmg);
        targets.effects(c.caster(), t, p.effects("effect"));
        targets.sideEffects(c.caster(), t, p);
        int shield = p.i("shield", 0);
        if (shield > 0 && t instanceof Player pl && pl.isBlocking()) pl.setCooldown(Material.SHIELD, shield);
        Vector facing = c.caster().getLocation().getDirection();
        double pull = p.d("pull", 0), slam = p.d("slam", 0);
        if (pull > 0) {
            Vector v = c.caster().getLocation().toVector().subtract(t.getLocation().toVector());
            double dist = v.length();
            if (dist > 1.2) t.setVelocity(v.setY(0).normalize().multiply(Math.min(pull, dist * 0.35)).setY(0.25));
        } else if (slam > 0) {
            t.setVelocity(new Vector(0, -slam, 0));
        } else {
            Location from = p.s("center", "target").equalsIgnoreCase("caster") || p.d("r", 0) <= 0 ? c.caster().getLocation() : center;
            targets.knock(t, from, facing, p.d("knock", 0), p.d("up", 0));
        }
    }
}
