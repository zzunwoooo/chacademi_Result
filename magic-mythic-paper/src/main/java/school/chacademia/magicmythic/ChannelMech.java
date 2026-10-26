package school.chacademia.magicmythic;

import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * chachannel{ticks=240;move=0.3;then=CHA_x} - a chant. The caster must stand still (horizontal movement over `move`
 * blocks breaks it) and must not take damage. When it completes, the `then` skill is cast with the same aim point and
 * the same Bridge damage value (solar_judgment). A broken chant keeps the mana/cooldown already spent.
 * VFX: external:laser_fire (complete) / external:struct_cancel (broken) to the running cast (spell=id).
 */
final class ChannelMech extends Mech {
    ChannelMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    private static final Map<UUID, Long> HURT = new HashMap<>();
    private static final Set<UUID> CHANTING = new HashSet<>();
    private static long now = 0;

    static final class Hooks implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void hurt(EntityDamageEvent e) { if (CHANTING.contains(e.getEntity().getUniqueId()) && e.getFinalDamage() > 0) HURT.put(e.getEntity().getUniqueId(), now); }
    }
    static void tickClock() { now++; }

    @Override boolean run(Ctx c) {
        LivingEntity caster = c.caster();
        UUID id = caster.getUniqueId();
        if (CHANTING.contains(id)) return false;
        int ticks = Math.clamp(p.i("ticks", 240), 1, 20 * 60);
        String then = p.s("then", "");
        if (then.isBlank()) return false;
        Location start = caster.getLocation().clone(), aim = c.at().clone();
        double move = p.d("move", 0.3), damage = c.damage();
        long began = now;
        boolean solar = p.s("spell", "").equals("solar_judgment");
        if(solar)vfx(caster,"external:aim",aim);
        HURT.remove(id);
        boolean added = plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public String kind() { return "channel"; }
            public void stop() { CHANTING.remove(id); HURT.remove(id); }
            public boolean tick() {
                boolean interrupted = !caster.isValid() || caster.isDead() || !caster.getWorld().equals(start.getWorld())
                    || horizontal(caster.getLocation(), start) > move || HURT.getOrDefault(id, -1L) >= began
                    || !plugin.protection().pair(caster, aim);
                boolean broken = !caster.isValid() || caster.isDead() || !caster.getWorld().equals(start.getWorld()) || age < ticks && interrupted;
                if (broken) {
                    if(solar)for(String event:SolarJudgmentSequence.cancel(age))vfx(caster,event,aim);
                    else vfx(caster, "external:struct_cancel", aim);
                    return false;
                }
                ++age;
                if(solar){
                    for(String event:SolarJudgmentSequence.events(age,ticks))vfx(caster,event,aim);
                    if(age==ticks+SolarJudgmentSequence.FIRE_DELAY)cast(caster,then,aim,damage);
                    return age<ticks+SolarJudgmentSequence.FINISH_DELAY;
                }
                if (age < ticks) return true;
                vfx(caster, "external:laser_fire", aim);
                cast(caster, then, aim, damage);
                return false;
            }
        });
        if (added) CHANTING.add(id);
        return added;
    }

    static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void vfx(LivingEntity caster, String event, Location at) {
        String spell = p.s("spell", "");
        if (!spell.isBlank() && caster instanceof Player pl) Links.vfx(pl, spell, event, at, null);
    }

    /** Cast a follow-up CHA_ skill with the original aim point and damage value. */
    static boolean cast(LivingEntity caster, String skill, Location at, double damage) {
        try {
            return MythicBukkit.inst().getAPIHelper().castSkill(caster, skill, caster, caster.getLocation(), List.<Entity>of(), List.of(at), 1f,
                data -> { data.getVariables().putDouble("damage", damage); data.getVariables().putDouble("pvp_scale", SpellTrace.current().pvpScale()); });
        } catch (RuntimeException | LinkageError e) { return false; }
    }
}
