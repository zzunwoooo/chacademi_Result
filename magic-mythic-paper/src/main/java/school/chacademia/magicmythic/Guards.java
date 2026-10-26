package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.FluidCollisionMode;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Personal guards on the caster.
 *  lethal  - the next fatal hit is cancelled once (phoenix_oath)
 *  reflect - nearby hostile projectiles flying at the caster are turned back (returning_wind)
 *  block   - hostile projectiles entering a wall in front of the caster vanish (ripple_guard)
 *  deflect - the next melee hit is reduced and the attacker is pushed aside (waterwheel_shield)
 *  thorns  - melee attackers take `damage` back (thorn_armor)
 *  anchor  - no knockback from attacks, explosions excluded; moving is still free (earth_anchor)
 *  counter - a shield block within the window stuns the attacker for `extra` ticks, once (counterweight)
 *  ward    - hostile projectiles within r are absorbed; each one gives `damage` absorption, capped at `reduce`,
 *            the absorption lasts `extra` ticks after the last absorb (protective_ward)
 *  invuln  - no damage at all except void / kill (flawless_moment)
 *  homing  - arrows the owner shoots steer to the enemy aimed at when shot, never through walls (guiding_fletching)
 *  immune  - harmful potion effects cannot be applied (dawn_ritual, put on each ally)
 *  oath    - put on an ally: `reduce` of each hit goes to the protector (`link`) while within r (guardian_oath)
 */
final class Guards implements Listener {
    enum Type { LETHAL, REFLECT, BLOCK, DEFLECT, THORNS, ANCHOR, COUNTER, WARD, INVULN, HOMING, IMMUNE, OATH }
    record Guard(Type type, long until, double r, double reduce, double heal, double push, double damage, int extra, UUID link, String spell) {
        Guard(Type type, long until, double r, double reduce, double heal, double push) { this(type, until, r, reduce, heal, push, 0, 0, null, ""); }
    }

    private final MagicMythicPlugin plugin;
    private final Map<UUID, EnumMap<Type, Guard>> guards = new HashMap<>();
    private long now = 0;

    Guards(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    void add(LivingEntity owner, Guard g) {
        guards.computeIfAbsent(owner.getUniqueId(), k -> new EnumMap<>(Type.class)).put(g.type(), g);
    }
    long now() { return now; }
    boolean has(Entity e, Type type) { return active(e, type) != null; }
    void remove(Entity e, Type type) { consume(e, type); }
    int count() { return guards.values().stream().mapToInt(Map::size).sum(); }

    private Guard active(Entity e, Type type) {
        // cheap map checks first: almost every damage/knockback/potion event has no guard at all,
        // so the WorldGuard query only runs when a live guard of this type exists
        var m = guards.get(e.getUniqueId());
        if (m == null) return null;
        Guard g = m.get(type);
        if (g == null) return null;
        if (g.until() < now) { m.remove(type); return null; }
        if(!plugin.protection().at(e.getLocation()))return null;
        return g;
    }
    private void consume(Entity e, Type type) {
        var m = guards.get(e.getUniqueId());
        if (m != null) { m.remove(type); if (m.isEmpty()) guards.remove(e.getUniqueId()); }
    }

    private void tick() {
        now++;
        if (!shields.isEmpty()) for (var it = shields.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > now) continue;
            it.remove();
            if (Bukkit.getEntity(e.getKey()) instanceof LivingEntity le) endShield(le);
        }
        if (!arrows.isEmpty()) arrows.removeIf(a -> !steer(a));
        if (guards.isEmpty()) return;
        var it = guards.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            entry.getValue().values().removeIf(g -> g.until() < now);
            if (entry.getValue().isEmpty()) { it.remove(); continue; }
            Entity owner = Bukkit.getEntity(entry.getKey());
            if (!(owner instanceof LivingEntity le) || !le.isValid()) { it.remove(); continue; }
            if(!plugin.protection().at(le.getLocation())){it.remove();continue;}
            Guard reflect = entry.getValue().get(Type.REFLECT), block = entry.getValue().get(Type.BLOCK), ward = entry.getValue().get(Type.WARD);
            if (reflect != null) reflect(le, reflect);
            if (block != null) block(le, block);
            if (ward != null) ward(le, ward);
        }
    }

    private boolean hostileShot(LivingEntity owner, Projectile pr) {
        if(!plugin.protection().pair(owner,pr.getLocation()))return false;
        ProjectileSource src = pr.getShooter();
        if (!(src instanceof Entity shooter)) return true;            // dispensers etc.
        if (shooter.getUniqueId().equals(owner.getUniqueId())) return false;
        if (shooter instanceof Player) return plugin.targets().enemy(owner, shooter); // allies' shots pass
        return true;
    }

    private void reflect(LivingEntity owner, Guard g) {
        Location c = owner.getLocation().add(0, 1, 0);
        for (Entity e : owner.getWorld().getNearbyEntities(c, g.r(), g.r(), g.r())) {
            if (!(e instanceof Projectile pr) || !hostileShot(owner, pr)) continue;
            Vector v = pr.getVelocity();
            Vector toOwner = c.toVector().subtract(pr.getLocation().toVector());
            if (v.lengthSquared() < 1e-3 || v.dot(toOwner) <= 0 || pr.getScoreboardTags().contains("chacademia_reflected")) continue;
            pr.addScoreboardTag("chacademia_reflected");
            pr.setVelocity(v.multiply(-1.1));
            pr.setShooter(owner instanceof ProjectileSource ps ? ps : null);
        }
    }

    private void block(LivingEntity owner, Guard g) {
        Location base = owner.getLocation();
        Vector facing = base.getDirection();
        Vector flat = facing.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) flat = new Vector(-Math.sin(Math.toRadians(base.getYaw())), 0, Math.cos(Math.toRadians(base.getYaw())));
        Vector wallCenter = base.toVector().add(flat.normalize().multiply(1.5));
        for (Entity e : owner.getWorld().getNearbyEntities(base, 4, 4, 4)) {
            if (!(e instanceof Projectile pr) || !hostileShot(owner, pr)) continue;
            if (Geometry.inWall(wallCenter, facing, pr.getLocation().toVector(), 4, 2, 3)) pr.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void lethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Guard g = active(le, Type.LETHAL);
        if (g == null || e.getFinalDamage() < le.getHealth()) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        e.setCancelled(true);
        consume(le, Type.LETHAL);
        le.setHealth(Math.min(Targets.maxHealth(le), Math.max(1, g.heal())));
        le.setFireTicks(0);
        plugin.status().clear(le, school.magiccodex.protocol.StatusProtocol.Status.BURN);
        le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 4));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void deflect(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof LivingEntity attacker)) return;
        Guard g = active(le, Type.DEFLECT);
        if (g == null) return;
        if(!plugin.targets().enemy(le,attacker)||!plugin.protection().harm(le,attacker))return;
        consume(le, Type.DEFLECT);
        e.setDamage(e.getDamage() * (1 - Math.clamp(g.reduce(), 0, 1)));
        Vector away = attacker.getLocation().toVector().subtract(le.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = le.getLocation().getDirection().setY(0);
        Vector side = new Vector(-away.getZ(), 0, away.getX()).normalize();
        attacker.setVelocity(side.multiply(g.push()).add(away.normalize().multiply(g.push() * 0.5)).setY(0.25));
    }

    // ------------------------------------------------------------------ ward (protective_ward)
    private final Map<UUID, Long> shields = new HashMap<>();
    private final Map<UUID, Double> wardShare = new HashMap<>();
    private static final org.bukkit.NamespacedKey WARD_KEY = new org.bukkit.NamespacedKey("chacademiamagicmythic", "ward_absorption");

    private void ward(LivingEntity owner, Guard g) {
        Location c = owner.getLocation().add(0, 1, 0);
        for (Entity e : owner.getWorld().getNearbyEntities(c, g.r(), g.r(), g.r())) {
            if (!(e instanceof Projectile pr) || !pr.isValid() || !hostileShot(owner, pr)) continue;
            if (pr.getLocation().distanceSquared(c) > g.r() * g.r()) continue;
            pr.remove();
            double cap = Math.max(1, g.reduce());
            var attr = owner.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION);
            if (attr != null) {
                attr.removeModifier(WARD_KEY);
                attr.addTransientModifier(new org.bukkit.attribute.AttributeModifier(WARD_KEY, cap, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
            }
            double mine = Math.min(owner.getAbsorptionAmount(), wardShare.getOrDefault(owner.getUniqueId(), 0d));
            double other = owner.getAbsorptionAmount() - mine, next = Math.min(cap, mine + Math.max(0, g.damage()));
            wardShare.put(owner.getUniqueId(), next);
            owner.setAbsorptionAmount(Math.min(attr != null ? attr.getValue() : other + next, other + next));
            shields.put(owner.getUniqueId(), now + Math.max(1, g.extra()));
            if (owner instanceof Player pl && !g.spell().isBlank()) {
                Links.vfx(pl, g.spell(), "external:absorb", pr.getLocation(), null);
                Links.vfx(pl, g.spell(), "external:shield_gain", pl.getLocation(), null);
            }
        }
    }
    private void endShield(LivingEntity le) {
        double mine = Math.min(le.getAbsorptionAmount(), wardShare.getOrDefault(le.getUniqueId(), 0d));
        wardShare.remove(le.getUniqueId());
        var attr = le.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION);
        if (attr != null && attr.getModifier(WARD_KEY) != null) attr.removeModifier(WARD_KEY);
        double left = le.getAbsorptionAmount() - mine;
        le.setAbsorptionAmount(Math.max(0, attr == null ? left : Math.min(left, attr.getValue())));
    }

    // ------------------------------------------------------------------ thorns / counter / invuln / oath
    private boolean sharing = false;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void invuln(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        Guard g = active(e.getEntity(), Type.INVULN);
        if (g == null) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player pl && !g.spell().isBlank()) Links.vfx(pl, g.spell(), "external:flawless_moment_hit", pl.getLocation().add(0, 1, 0), null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void oath(EntityDamageEvent e) {
        if (sharing || !(e.getEntity() instanceof Player ally)) return;
        Guard g = active(ally, Type.OATH);
        if (g == null || g.link() == null || e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        Player protector = Bukkit.getPlayer(g.link());
        if (protector == null || protector.isDead() || !protector.getWorld().equals(ally.getWorld())
            || protector.getLocation().distanceSquared(ally.getLocation()) > g.r() * g.r() || !plugin.protection().pair(protector, ally.getLocation())) return;
        double ratio = Math.clamp(g.reduce(), 0, 1), share = e.getDamage() * ratio;
        if (share <= 0) return;
        e.setDamage(e.getDamage() - share);
        // 전투 스펙 2026-10-26: PvP 마법 피해는 이미 마법 방어로 줄어든 값 → 대신 받는 쪽은 더 줄이지 않음 (이중 감소 방지).
        shares.put(e, new Object[]{protector, share, g.spell(), plugin.protection().reducedHitOn(ally)});
    }
    private final Map<EntityDamageEvent, Object[]> shares = new WeakHashMap<>();
    /** The protector takes its share only when the ally's hit really went through (not cancelled by invuln/lethal...). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void oathApplied(EntityDamageEvent e) {
        Object[] s = shares.remove(e);
        if (s == null || e.isCancelled()) return;
        Player protector = (Player) s[0];
        if (!protector.isValid() || protector.isDead()) return;
        sharing = true;
        try { if ((boolean) s[3]) plugin.protection().sourceless(protector, (double) s[1], false, true); else protector.damage((double) s[1]); } finally { sharing = false; }
        String spell = (String) s[2];
        if (!spell.isBlank()) Links.vfx(protector, spell, "external:relay", e.getEntity().getLocation(), e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void struck(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity owner) || !(e.getDamager() instanceof LivingEntity attacker)) return;
        Guard thorns = active(owner, Type.THORNS);
        if (thorns != null && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !plugin.protection().magicDamaging()
            && plugin.targets().enemy(owner, attacker)) {
            double dmg = thorns.damage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (owner.isValid() && attacker.isValid() && plugin.targets().hurt(owner, attacker, dmg) && owner instanceof Player pl && !thorns.spell().isBlank())
                    Links.vfx(pl, thorns.spell(), "external:thorn_armor_hit", attacker.getLocation(), attacker.getUniqueId());
            });
        }
        Guard counter = active(owner, Type.COUNTER);
        if (counter != null && owner instanceof Player pl && pl.isBlocking() && blocked(e) && plugin.targets().enemy(owner, attacker)) {
            consume(owner, Type.COUNTER);
            plugin.marks().stun(owner, attacker, Math.max(1, counter.extra()));
            if (!counter.spell().isBlank()) Links.vfx(pl, counter.spell(), "external:block", attacker.getLocation(), attacker.getUniqueId());
        }
    }
    @SuppressWarnings("deprecation")
    private static boolean blocked(EntityDamageEvent e) {
        try { return e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) && e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0; }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }

    // ------------------------------------------------------------------ anchor (earth_anchor)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void knockback(org.bukkit.event.entity.EntityKnockbackByEntityEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityKnockbackEvent.KnockbackCause.EXPLOSION) return;
        Guard g = active(e.getEntity(), Type.ANCHOR);
        if (g == null) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player pl && !g.spell().isBlank()) Links.vfx(pl, g.spell(), "external:earth_anchor_hit", pl.getLocation(), null);
    }

    // ------------------------------------------------------------------ immune (dawn_ritual)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void potion(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (e.getNewEffect() == null || active(e.getEntity(), Type.IMMUNE) == null) return;
        if (e.getAction() != org.bukkit.event.entity.EntityPotionEffectEvent.Action.ADDED && e.getAction() != org.bukkit.event.entity.EntityPotionEffectEvent.Action.CHANGED) return;
        if (e.getNewEffect().getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ homing arrows (guiding_fletching)
    private record Homing(AbstractArrow arrow, LivingEntity owner, LivingEntity target, int[] age, double speed) {}
    private final List<Homing> arrows = new ArrayList<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shot(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof LivingEntity owner)) return;
        if (active(owner, Type.HOMING) == null) return;
        LivingEntity target = aimed(owner, 48);
        if (target == null) return;
        arrows.add(new Homing(arrow, owner, target, new int[]{0}, Math.max(0.5, arrow.getVelocity().length())));
    }
    /** The enemy the owner looks at (thin ray, then a narrow cone). */
    LivingEntity aimed(LivingEntity owner, double range) {
        Location eye = owner.getEyeLocation();
        var hit = owner.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.6, x -> !x.equals(owner) && plugin.targets().enemy(owner, x));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity le) {
            var block = owner.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
            if (block == null || block.getHitPosition().distanceSquared(eye.toVector()) > hit.getHitPosition().distanceSquared(eye.toVector())) return le;
        }
        LivingEntity best = null; double bestCos = Math.cos(Math.toRadians(8));
        for (LivingEntity t : plugin.targets().enemiesNear(owner, eye, range, 32)) {
            Vector to = t.getEyeLocation().toVector().subtract(eye.toVector());
            double cos = to.clone().normalize().dot(eye.getDirection());
            if (cos > bestCos && owner.hasLineOfSight(t)) { bestCos = cos; best = t; }
        }
        return best;
    }
    private boolean steer(Homing h) {
        AbstractArrow a = h.arrow();
        if (!a.isValid() || a.isInBlock() || a.isOnGround() || ++h.age()[0] > 100 || !h.target().isValid() || h.target().isDead()) return false;
        if (!a.getWorld().equals(h.target().getWorld())) return false;
        Location from = a.getLocation();
        Vector to = h.target().getLocation().add(0, h.target().getHeight() * 0.6, 0).toVector().subtract(from.toVector());
        double dist = to.length();
        if (dist < 0.3) return true;
        var block = a.getWorld().rayTraceBlocks(from, to.clone().normalize(), dist, FluidCollisionMode.NEVER, true);
        if (block != null) return true; // no bending through walls: fly straight this tick, try again next tick
        Vector v = a.getVelocity();
        double speed = Math.max(h.speed() * 0.85, v.length());
        a.setVelocity(v.clone().normalize().multiply(0.45).add(to.normalize().multiply(0.55)).normalize().multiply(speed));
        return true;
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        guards.remove(e.getPlayer().getUniqueId());
        if (shields.remove(e.getPlayer().getUniqueId()) != null) endShield(e.getPlayer());
    }

    void closeAll() {
        for (UUID id : new ArrayList<>(shields.keySet())) if (Bukkit.getEntity(id) instanceof LivingEntity le) endShield(le);
        shields.clear(); arrows.clear(); guards.clear();
    }
}
