package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Marks that wait for something to happen.
 *  ash_brand - bursts after `ticks`, the burst brands the enemies it hits (each enemy rebrandable after `cooldown`)
 *  wedge     - bursts when the marked enemy takes a melee hit from a player, or never (no timed burst)
 *  prison    - 기절 status ({@link StatusEffects}) on normal mobs until `ticks` end or they take enough damage
 *              (prison-break-damage-ratio); bosses and players get 둔화 (45 %) instead. The mob's own awareness is
 *              saved on the mob (PDC) and put back on release, on plugin stop and, after a crash, when it loads again.
 *  swords    - on the caster: each melee hit on an enemy spends one sword for extra damage
 *  root      - 속박 status: movement only is held for `ticks`; bosses are immune (material_exchange)
 *  stun      - 기절 status for `ticks`; bosses get 둔화 instead (counterweight)
 *  (2026-10-19: these three are now status effects so they show as icons; this class only routes them.)
 */
final class Marks implements Listener {
    enum Type { ASH_BRAND, WEDGE, PRISON, SWORDS, ROOT, STUN }

    final class Mark {
        final Type type; final LivingEntity caster; final LivingEntity holder; final double damage; final Params p;
        final long until; int charges; double taken = 0; boolean done = false;
        /** 전투 스펙 2026-10-26: 표식을 건 시전의 추적값 (PvP 배율 pvp_scale 포함) — 나중에 터질 때 같은 배율을 쓴다. */
        final SpellTrace.Now trace;
        Mark(Type type, LivingEntity caster, LivingEntity holder, double damage, Params p, long ticks) {
            this.type = type; this.caster = caster; this.holder = holder; this.damage = damage; this.p = p;
            var named = SpellTrace.of(p); this.trace = new SpellTrace.Now(named.spell(), named.element(), SpellTrace.current().pvpScale());
            this.until = now + ticks; this.charges = Math.max(1, p.i("count", 3));
        }
    }

    private final MagicMythicPlugin plugin;
    private final NamespacedKey prisonKey;
    private final List<Mark> marks = new ArrayList<>();
    private final Map<UUID, Long> brandCooldown = new HashMap<>();
    private long now = 0;
    private boolean applyingSword = false;

    Marks(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        this.prisonKey = new NamespacedKey(plugin, "prison_was_aware");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        // Mobs left frozen by a crash in already-loaded chunks.
        for (var w : Bukkit.getWorlds()) for (var e : w.getEntitiesByClass(Mob.class)) recover(e);
    }
    int count() { return marks.size(); }

    boolean add(Type type, LivingEntity caster, LivingEntity holder, double damage, Params p) {
        if(plugin.targets().excluded(holder)||!plugin.protection().pair(caster,holder.getLocation()))return false;
        if (marks.size() >= plugin.getConfig().getInt("limits.marks", 512)) return false;
        if(type!=Type.SWORDS&&!plugin.targets().hurt(caster,holder,0))return false;
        long ticks = Math.clamp(p.i("ticks", 60), 1, 20 * 600);
        switch (type) {
            case ASH_BRAND -> {
                if (brandCooldown.getOrDefault(holder.getUniqueId(), -1L) > now) return false;
                if (marks.stream().anyMatch(m -> m.type == Type.ASH_BRAND && m.holder.equals(holder) && !m.done)) return false;
                brandCooldown.put(holder.getUniqueId(), now + Math.max(0, p.i("cooldown", 200)));
            }
            case PRISON -> {
                var st = plugin.status();
                if (st.has(holder, school.magiccodex.protocol.StatusProtocol.Status.STUN)) return false;
                // Bosses and players were only slowed by the prison (45 % = old Slowness III).
                if (plugin.targets().boss(holder) || !(holder instanceof Mob))
                    return st.apply(caster, holder, school.magiccodex.protocol.StatusProtocol.Status.SLOW, (int) ticks, 0.45);
                return st.apply(caster, holder, school.magiccodex.protocol.StatusProtocol.Status.STUN, (int) ticks,
                    Math.max(0.0001, plugin.getConfig().getDouble("prison-break-damage-ratio", 0.2)));
            }
            case SWORDS -> marks.removeIf(m -> m.type == Type.SWORDS && m.holder.equals(holder));
            case ROOT -> { return plugin.status().apply(caster, holder, school.magiccodex.protocol.StatusProtocol.Status.ROOT, (int) ticks, 0); }
            case STUN -> {
                if (plugin.status().has(holder, school.magiccodex.protocol.StatusProtocol.Status.STUN)) return false;
                return plugin.status().apply(caster, holder, school.magiccodex.protocol.StatusProtocol.Status.STUN, (int) ticks, 0);
            }
            default -> {}
        }
        marks.add(new Mark(type, caster, holder, damage, p, ticks));
        return true;
    }

    private void tick() {
        now++;
        if ((now & 255) == 0) brandCooldown.values().removeIf(t -> t < now);
        if (marks.isEmpty()) return;
        for (Mark m : new ArrayList<>(marks)) {
            if (m.done) continue;
            var traceBefore = SpellTrace.set(m.trace);
            try {
            boolean gone = !m.holder.isValid() || m.holder.isDead() || !plugin.protection().pair(m.caster,m.holder.getLocation());
            if (m.type == Type.ASH_BRAND && !gone && now >= m.until) { burstBrand(m); m.done = true; }
            else if (gone || now >= m.until) m.done = true;
            if (m.done && (m.type == Type.PRISON || m.type == Type.STUN)) release(m);
            } finally { SpellTrace.set(traceBefore); }
        }
        marks.removeIf(m -> m.done);
    }

    private void burstBrand(Mark m) {
        var targets = plugin.targets();
        var hit = targets.enemiesNear(m.caster, m.holder.getLocation(), m.p.d("r", 3), m.p.i("max", 12));
        for (LivingEntity t : hit) targets.hurt(m.caster, t, m.damage);
        if (!m.caster.isValid()) return;
        for (LivingEntity t : hit) if (!t.equals(m.holder) && t.isValid() && !t.isDead()) add(Type.ASH_BRAND, m.caster, t, m.damage, m.p);
    }

    private void burstWedge(Mark m) {
        var targets = plugin.targets();
        for (LivingEntity t : targets.enemiesNear(m.caster, m.holder.getLocation(), m.p.d("r", 2.5), m.p.i("max", 12))) {
            if(!targets.hurt(m.caster, t, m.damage))continue;
            targets.knock(t, m.holder.getLocation(), m.caster.getLocation().getDirection(), m.p.d("knock", 0.6), m.p.d("up", 0.3));
        }
    }

    private void release(Mark m) {
        if (!(m.holder instanceof Mob mob)) return;
        boolean other = marks.stream().anyMatch(x -> x != m && !x.done && (x.type == Type.PRISON || x.type == Type.STUN) && x.holder.equals(mob));
        if (!other) recover(mob);
    }

    /** Put back the awareness saved when the prison started, and forget it. */
    private void recover(Mob mob) { recoverMob(mob, prisonKey); }
    static void freezeMob(Mob mob, NamespacedKey prisonKey) {
        if (!mob.getPersistentDataContainer().has(prisonKey, PersistentDataType.BYTE))
            mob.getPersistentDataContainer().set(prisonKey, PersistentDataType.BYTE, (byte)(mob.isAware()?1:0));
        mob.setAware(false);
    }
    static void recoverMob(Mob mob, NamespacedKey prisonKey) {
        var pdc = mob.getPersistentDataContainer();
        Byte was = pdc.get(prisonKey, PersistentDataType.BYTE);
        if (was == null) return;
        mob.setAware(was != 0);
        pdc.remove(prisonKey);
    }

    @EventHandler public void entitiesLoad(EntitiesLoadEvent e) {
        for (var en : e.getEntities()) {
            if (!(en instanceof Mob mob)) continue;
            boolean stillHeld = marks.stream().anyMatch(m -> (m.type == Type.PRISON || m.type == Type.STUN) && !m.done && m.holder.getUniqueId().equals(mob.getUniqueId()));
            if (!stillHeld) recover(mob); // frozen by a crash or by a prison that ended while unloaded
        }
    }

    void releaseAll() { for (Mark m : marks) if (m.type == Type.PRISON || m.type == Type.STUN) release(m); marks.clear(); }

    /** counterweight: stop the attacker for `ticks` (no damage dealt). */
    boolean stun(LivingEntity caster, LivingEntity target, int ticks) {
        var p = new Params(Map.of("ticks", String.valueOf(ticks)));
        return add(Type.STUN, caster, target, 0, p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent e) {
        if (marks.isEmpty() || !(e.getEntity() instanceof LivingEntity victim)) return;
        boolean melee = e instanceof EntityDamageByEntityEvent by && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            && by.getDamager() instanceof Player;
        for (Mark m : new ArrayList<>(marks)) {
            if (m.done || !m.holder.equals(victim)) continue;
            if (m.type == Type.PRISON) {
                m.taken += e.getFinalDamage();
                if (m.taken >= Targets.maxHealth(victim) * plugin.getConfig().getDouble("prison-break-damage-ratio", 0.2)) {
                    m.done = true; release(m);
                }
            } else if (m.type == Type.WEDGE && melee) {
                m.done = true;
                // #79: the wedge VFX (30 s loop) only ends on its external:melee_hit branch; nothing sent it, so it stayed after the burst.
                if (m.caster instanceof Player pl && !m.p.s("spell", "").isBlank())
                    Links.vfx(pl, m.p.s("spell", ""), "external:melee_hit", victim.getLocation(), victim.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> { var before = SpellTrace.set(m.trace); try { burstWedge(m); } finally { SpellTrace.set(before); } });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swordHit(EntityDamageByEntityEvent e) {
        if (applyingSword || marks.isEmpty() || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof LivingEntity attacker) || !(e.getEntity() instanceof LivingEntity victim)) return;
        for (Mark m : marks) {
            if (m.done || m.type != Type.SWORDS || !m.holder.equals(attacker) || !plugin.targets().enemy(attacker, victim)) continue;
            if (--m.charges <= 0) m.done = true;
            double dmg = m.damage;
            Bukkit.getScheduler().runTask(plugin, () -> {
                applyingSword = true; var before = SpellTrace.set(m.trace);
                try { plugin.targets().hurt(attacker, victim, dmg); } finally { applyingSword = false; SpellTrace.set(before); }
            });
            return;
        }
    }
}
