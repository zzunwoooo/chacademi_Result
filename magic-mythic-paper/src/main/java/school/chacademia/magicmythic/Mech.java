package school.chacademia.magicmythic;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.skills.*;
import io.lumine.mythic.bukkit.BukkitAdapter;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Base for every cha* mechanic. MagicCodexBridge casts CHA_* skills with an entity target (when one was chosen)
 * and a location target (aim point or caster position). Mechanics written without a targeter inherit both, so a
 * location call is skipped whenever the same cast also carries an entity target - otherwise one hit would land twice.
 */
abstract class Mech implements ITargetedEntitySkill, ITargetedLocationSkill {
    final MagicMythicPlugin plugin;
    final Params p;

    Mech(MagicMythicPlugin plugin, Params p) { this.plugin = plugin; this.p = p; }

    record Ctx(SkillMetadata data, LivingEntity caster, Entity target, Location at, double damage) {
        /** Configured damage: Bridge variable "damage" x mult + flat. */
        double dmg(Params p) { return Math.max(0, damage * p.d("mult", 1) + p.d("flat", 0)); }
    }

    @Override public ThreadSafetyLevel getThreadSafetyLevel() { return ThreadSafetyLevel.SYNC_ONLY; }

    @Override public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
        LivingEntity caster = caster(data);
        Entity e = target == null ? null : target.getBukkitEntity();
        if (caster == null || e == null || !e.isValid() || plugin.targets().excluded(e) || !plugin.protection().pair(caster,e.getLocation())) return SkillResult.INVALID_TARGET;
        return traced(new Ctx(data, caster, e, e.getLocation(), variable(data, "damage", 0))) ? SkillResult.SUCCESS : SkillResult.INVALID_TARGET;
    }

    @Override public SkillResult castAtLocation(SkillMetadata data, AbstractLocation location) {
        LivingEntity caster = caster(data);
        if (caster == null || location == null) return SkillResult.INVALID_TARGET;
        if (hasEntityTargets(data)) return SkillResult.SUCCESS; // the entity call handles this cast
        Location at = BukkitAdapter.adapt(location);
        if (at.getWorld() == null || !plugin.protection().pair(caster,at)) return SkillResult.INVALID_TARGET;
        return traced(new Ctx(data, caster, null, at, variable(data, "damage", 0))) ? SkillResult.SUCCESS : SkillResult.INVALID_TARGET;
    }

    abstract boolean run(Ctx c);

    /** run() with the spell trace set (attribution for damage/heal hooks) and the cast noted for acquisition checks. */
    final boolean traced(Ctx c) {
        var named = SpellTrace.of(p);
        var now = new SpellTrace.Now(named.spell(), named.element(), variable(c.data(), "pvp_scale", -1));
        var before = SpellTrace.set(now);
        try {
            if (c.caster() instanceof org.bukkit.entity.Player pl) DiscoveryHooks.cast(pl, now);
            return run(c);
        } finally { SpellTrace.set(before); }
    }

    static LivingEntity caster(SkillMetadata data) {
        try {
            var e = data.getCaster().getEntity().getBukkitEntity();
            return e instanceof LivingEntity le && le.isValid() ? le : null;
        } catch (RuntimeException ignored) { return null; }
    }

    /** Reads a skill variable set by the Bridge (data.getVariables().putDouble). Reflection keeps us tolerant of
     *  small VariableRegistry API differences between MythicMobs builds. */
    static double variable(SkillMetadata data,String name,double fallback){
        try{double value=data.getVariables().getDouble(name);return Double.isFinite(value)?value:fallback;}
        catch(RuntimeException error){return fallback;}
    }
    static boolean hasEntityTargets(SkillMetadata data){
        var targets=data.getEntityTargets();return targets!=null&&!targets.isEmpty();
    }

    /** Center of an area effect: caster or the given target. */
    Location center(Ctx c) {
        return p.s("center", "target").equalsIgnoreCase("caster") ? c.caster().getLocation() : c.at();
    }
}
