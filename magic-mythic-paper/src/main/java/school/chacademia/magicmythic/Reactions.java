package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import school.magiccodex.protocol.ElementProtocol;
import school.magiccodex.protocol.ElementProtocol.Element;
import school.magiccodex.protocol.ElementProtocol.Reaction;
import school.magiccodex.protocol.StatusProtocol;

/**
 * Element reactions (regular system). Called by Hit/Bolt/Zone right after a spell hit was accepted.
 *
 * Owner rules (2026-10-20, PvP CC rework, replaces 2026-10-14): every reaction gives crowd control through the status
 * engine (normal CC 3 s, hard CC 1 s; reactions.cc-ticks / hard-cc-ticks). Only 무지개 helps allies. Bosses follow the
 * status engine (기절·수면·공포 -> 둔화, 속박 면역); pulls and lifts skip bosses.
 *
 * Cost model: work happens only on accepted spell hits (one map lookup, plus a small radius search for the area
 * reactions). No per-tick task: marks expire lazily and a sweep runs every 30 s. Delayed tasks are visual only
 * (chain-lightning bolts 0.15 s apart, sandstorm pin effect after the pull).
 * Icons are drawn by the MagicCodex client from a 6-8 byte packet; the server spawns no particles or entities.
 * Reaction VFX: PortableVFX pack "ElementReactions" v3 (2026-10-20), played through {@link ReactionVfx}, sized to the
 * target (ModelEngine hitbox when modelled). Visual only.
 */
final class Reactions implements Listener, AutoCloseable {
    private final MagicMythicPlugin plugin;
    private final ReactionBook book = new ReactionBook(60, 60);
    private final BukkitTask sweeper;
    private final ReactionVfx vfx;
    /** PortableVFX player shared with mob skills (chatelegraph vfx=...). */
    ReactionVfx vfx() { return vfx; }
    /** 지금 반응 효과(반응 피해)를 적용하는 중인가 — 허수아비 피해 숫자 색 구분용 (#107). */
    boolean applying() { return applying; }
    private boolean enabled = true, applying;
    private double floor = 2, scale = 1, sendRange = 48;
    private int ccTicks = 60, hardTicks = 20, manaBurnTicks = 100;
    private double steamRadius = 3, wildfireRadius = 2, sandRadius = 4, mudSlow = 0.4;

    Reactions(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        this.vfx = new ReactionVfx(plugin);
        reload();
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, ElementProtocol.CHANNEL);
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 600, 600);
    }

    void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("reactions.enabled", true);
        book.configure(c.getInt("reactions.mark-ticks", 60), c.getInt("reactions.cooldown-ticks", 60));
        floor = Math.max(0, c.getDouble("reactions.damage-floor", 2));
        scale = Math.clamp(c.getDouble("reactions.damage-scale", 1), 0, 5);
        sendRange = Math.clamp(c.getDouble("reactions.icon-range", 48), 8, 128);
        ccTicks = Math.clamp(c.getInt("reactions.cc-ticks", 60), 1, 400);
        hardTicks = Math.clamp(c.getInt("reactions.hard-cc-ticks", 20), 1, 200);
        manaBurnTicks = Math.clamp(c.getInt("status.mana-burn-ticks", 100), 1, 20 * 600);
        steamRadius = Math.clamp(c.getDouble("reactions.steam-radius", 3), 0.5, 10);
        wildfireRadius = Math.clamp(c.getDouble("reactions.wildfire-radius", 2), 0.5, 10);
        sandRadius = Math.clamp(c.getDouble("reactions.sandstorm-radius", 4), 0.5, 10);
        mudSlow = Math.clamp(c.getDouble("reactions.mud-slow", 0.4), 0.05, 0.9);
        vfx.reload();
        if (!enabled) clearAll();
    }

    /** Entry point, after the spell's damage to `target` was accepted. `damage` = what the spell dealt (pre-armour). */
    void hit(LivingEntity caster, LivingEntity target, String elementId, double damage) {
        if (!enabled || applying || caster == null || target == null || !target.isValid() || target.isDead()) return;
        Element element = Element.of(elementId);
        if (element == null) return;
        if (!plugin.targets().enemy(caster, target)) return; // C03: never mark (or react on) a party member / teammate
        element = plugin.chaos().element(caster, element); // 혼돈의 주사위 (아티팩트): 이번 시전의 무작위 원소
        long now = Bukkit.getCurrentTick();
        int ticks = book.markTicks();
        if (caster instanceof Player && MagicMarkEvent.listened() && !book.coolingDown(target.getUniqueId(), now)) {
            var e = new MagicMarkEvent(caster, target, element.id, ticks);
            Bukkit.getPluginManager().callEvent(e);
            ticks = e.getTicks();
        }
        switch (book.hit(target.getUniqueId(), caster.getUniqueId(), element, now, ticks)) {
            case ReactionBook.Immune ignored -> {}
            case ReactionBook.Marked m -> send(target, new ElementProtocol.Mark(target.getEntityId(), m.element(), m.ticks()));
            case ReactionBook.Reacted r -> {
                send(target, new ElementProtocol.Clear(target.getEntityId()));
                send(target, new ElementProtocol.Pop(target.getEntityId(), r.reaction()));
                Bonus bonus = Bonus.NONE;
                if (caster instanceof Player && MagicReactionEvent.listened()) {
                    Element markElement = r.reaction().a == element ? r.reaction().b : r.reaction().a;
                    var e = new MagicReactionEvent(caster, target, id(r.reaction()), markElement.id, element.id);
                    Bukkit.getPluginManager().callEvent(e);
                    if (e.isEffectCancelled()) return; // name pop only (artifact: element chalk)
                    bonus = new Bonus(e.getRadiusBonus(), e.getBounceBonus(), e.getControlBonusTicks(), e.getNormalControlBonusTicks(), e.getControlMultiplier(), e.getHealBonus(), e.getRadiusMultiplier());
                }
                applying = true;
                try { apply(r.reaction(), caster, target, Math.max(damage, floor) * scale, now, bonus); }
                catch (RuntimeException e) { plugin.getLogger().warning("원소 반응 오류: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
                finally { applying = false; }
            }
        }
    }

    /** Artifact bonuses for one reaction ({@link MagicReactionEvent}). */
    private record Bonus(double radius, int bounces, int control, int normalControl, double controlMul, double heal, double radiusMul) {
        static final Bonus NONE = new Bonus(0, 0, 0, 0, 1, 0, 1);
    }

    static String id(Reaction r) { return r.name().toLowerCase(java.util.Locale.ROOT); }

    private static final StatusProtocol.Status
            BURN = StatusProtocol.Status.BURN, SEAL = StatusProtocol.Status.RECOVERY_SEAL, WEAK = StatusProtocol.Status.MAGIC_WEAKNESS,
            MANA = StatusProtocol.Status.MANA_BURN, DISARM = StatusProtocol.Status.DISARM, SLOW = StatusProtocol.Status.SLOW,
            POISON = StatusProtocol.Status.POISON, LOCK = StatusProtocol.Status.MOVEMENT_LOCK, FEAR = StatusProtocol.Status.FEAR,
            STUN = StatusProtocol.Status.STUN, ROOT = StatusProtocol.Status.ROOT, SLEEP = StatusProtocol.Status.SLEEP;

    /**
     * Crowd control through the status engine (icons, boss rules, immunity, the target's own artifact reductions).
     * Artifacts: duration x multiplier, + bonus ticks, + normal-CC-only bonus ticks when this is the 3 s kind.
     */
    private void cc(LivingEntity caster, LivingEntity target, StatusProtocol.Status status, int ticks, double power, Bonus bonus) {
        boolean normal = ticks == ccTicks && ticks != hardTicks;
        int t = (int) Math.round(ticks * bonus.controlMul()) + bonus.control() + (normal ? bonus.normalControl() : 0);
        plugin.status().apply(caster, target, status, Math.max(1, t), power);
    }

    /** 닻 장식 (artifact): players wearing it are not pulled by 모래 회오리. Tag kept by MagicCodexBridge. */
    static final String ANCHOR_TAG = "chacademi_anchor";

    /*
     * Owner rework 2026-10-20 (PvP): every reaction gives crowd control. Normal CC 3 s, hard CC 1 s (config).
     *  증기 폭발 r3 작은 피해 + 띄움 · 불씨 확산 r2 화상 · 용암 분출 회복봉인 · 태양 플레어 발광 + 약화 · 잿불 낙인 마나번
     *  연쇄 번개 연쇄 피해 + 맞은 적 무장해제 · 진흙 둔화 · 무지개 아군 회복 · 심연 독 · 모래 회오리 r4 끌어모으기 + 고정
     *  섬광 (미정: 기존 어그로 해제 유지) · 공허 균열 공포 · 수정화 기절 · 매장 속박 · 일식 수면
     */
    private void apply(Reaction reaction, LivingEntity caster, LivingEntity target, double ref, long now, Bonus bonus) {
        var targets = plugin.targets();
        String rid = id(reaction);
        Location at = target.getLocation();
        switch (reaction) {
            case STEAM -> {
                double r = steamRadius * bonus.radiusMul();
                vfx.area(reaction, at, r, target, 16);
                for (LivingEntity t : targets.enemiesNear(caster, at, r, 12)) {
                    if (!targets.hurt(caster, t, ref * 0.15, "", rid)) continue;
                    if (!targets.boss(t)) t.setVelocity(t.getVelocity().setY(0.35));
                }
            }
            case WILDFIRE -> {
                double r = wildfireRadius * bonus.radiusMul();
                vfx.area(reaction, at, r, target, 16);
                for (LivingEntity t : targets.enemiesNear(caster, at, r, 12)) {
                    if (!targets.hurt(caster, t, 0)) continue; // C03: same protection probe as other CC (match teams, outsiders)
                    cc(caster, t, BURN, ccTicks, 0, bonus);
                    vfx.body("wildfire_burn", t, 12);
                }
            }
            case MAGMA -> { vfx.body(reaction, target, 20); cc(caster, target, SEAL, ccTicks, 0, bonus); }
            case FLARE -> {
                vfx.body(reaction, target, 16);
                target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) Math.round(ccTicks * bonus.controlMul()) + bonus.control() + bonus.normalControl(), 0));
                cc(caster, target, WEAK, ccTicks, 0, bonus);
            }
            case EMBER_CURSE -> { vfx.body(reaction, target, 20); cc(caster, target, MANA, manaBurnTicks, 0, Bonus.NONE); }
            // 연쇄 번개: 대상에서 가까운 적으로 최대 3번 튐 (튈 때마다 30% 감소), 맞은 적은 무장해제
            case SHOCK -> {
                cc(caster, target, DISARM, hardTicks, 0, bonus);
                vfx.body("shock_disarm", target, 22);
                var done = new HashSet<UUID>(List.of(target.getUniqueId()));
                LivingEntity from = target;
                double amount = ref * 0.15;
                for (int i = 0; i < 3 + bonus.bounces(); i++) {
                    LivingEntity next = null;
                    for (LivingEntity t : targets.enemiesNear(caster, from.getLocation(), 4 * bonus.radiusMul(), 8))
                        if (!done.contains(t.getUniqueId())) { next = t; break; }
                    if (next == null) break;
                    done.add(next.getUniqueId());
                    if (targets.hurt(caster, next, amount, "", rid)) cc(caster, next, DISARM, hardTicks, 0, bonus);
                    LivingEntity a = from, b = next;
                    Runnable show = () -> { if (a.isValid() && b.isValid() && a.getWorld() == b.getWorld()) { vfx.bolt(a, b); vfx.body("shock_disarm", b, 22); } };
                    if (i == 0) show.run(); else later(3L * i, show);
                    amount *= 0.7;
                    from = next;
                }
            }
            case MUD -> { vfx.body(reaction, target, 20); cc(caster, target, SLOW, ccTicks, mudSlow, bonus); }
            // 무지개: 시전자와 주변 4블록 아군 회복 (유일한 아군 효과)
            case RAINBOW -> {
                vfx.area(reaction, caster.getLocation(), 4 * bonus.radiusMul(), caster, 20);
                for (Player ally : targets.alliesNear(caster, caster.getLocation(), 4 * bonus.radiusMul(), 8)) {
                    targets.heal(caster, ally, 1 + bonus.heal(), rid);
                    vfx.body("rainbow_heal", ally, 16);
                }
            }
            case ABYSS -> { vfx.body(reaction, target, 20); cc(caster, target, POISON, ccTicks, 0, bonus); }
            // 모래 회오리: 주변 적을 대상 쪽으로 빨아들이고 대상 포함 고정
            case SANDSTORM -> {
                double r = sandRadius * bonus.radiusMul() + bonus.radius();
                vfx.area(reaction, at, r, target, 16);
                for (LivingEntity t : targets.enemiesNear(caster, at, r, 12)) {
                    if (!targets.hurt(caster, t, 0)) continue; // C03: no pull/lock on teammates or protected players
                    if (!t.equals(target) && !targets.boss(t) && !t.getScoreboardTags().contains(ANCHOR_TAG)) {
                        Vector v = at.toVector().subtract(t.getLocation().toVector()).setY(0);
                        if (v.lengthSquared() > 0.36) t.setVelocity(v.normalize().multiply(Math.min(0.7, v.length() * 0.3)).setY(0.08));
                    }
                    cc(caster, t, LOCK, hardTicks, 0, bonus);
                    LivingEntity pinned = t;
                    later(t.equals(target) ? 1 : 8, () -> { if (pinned.isValid()) vfx.body("sandstorm_pin", pinned, 22); });
                }
            }
            // 섬광: 기획 미정 — 기존 효과 유지 (주변 6블록 몬스터가 노리던 플레이어를 놓침)
            case FLASH -> {
                vfx.body(reaction, target, 10);
                for (LivingEntity t : targets.enemiesNear(caster, at, 6 * bonus.radiusMul(), 12))
                    if (t instanceof Mob mob && !targets.boss(t) && mob.getTarget() instanceof Player) mob.setTarget(null);
            }
            case VOID_GUST -> { vfx.body(reaction, target, 22); cc(caster, target, FEAR, hardTicks, 0, bonus); }
            case CRYSTAL -> { vfx.body(reaction, target, 22); cc(caster, target, STUN, hardTicks, 0, bonus); }
            case BIND -> { vfx.body(reaction, target, 22); cc(caster, target, ROOT, hardTicks, 0, bonus); }
            case ECLIPSE -> { vfx.body(reaction, target, 22); cc(caster, target, SLEEP, hardTicks, 0, bonus); }
        }
    }

    // ---------------------------------------------------------------- artifact API (through MagicMythicPlugin)

    /** Live mark on the target, or null. */
    ReactionBook.MarkInfo markOf(LivingEntity target) {
        return enabled ? book.mark(target.getUniqueId(), Bukkit.getCurrentTick()) : null;
    }

    /** Marks `target` with `element` even during the post-reaction immunity. @return false when disabled / bad element */
    boolean forceMark(LivingEntity caster, LivingEntity target, String elementId) {
        Element element = Element.of(elementId);
        if (!enabled || element == null || !target.isValid() || target.isDead()) return false;
        book.force(target.getUniqueId(), caster.getUniqueId(), element, book.markTicks(), Bukkit.getCurrentTick());
        send(target, new ElementProtocol.Mark(target.getEntityId(), element, book.markTicks()));
        return true;
    }

    /** Removes the target's mark and deals `damage` (counts as reaction damage "detonate"). @return the element id or null */
    String detonate(LivingEntity caster, LivingEntity target, double damage) {
        if (!enabled) return null;
        var m = book.take(target.getUniqueId(), Bukkit.getCurrentTick());
        if (m == null) return null;
        send(target, new ElementProtocol.Clear(target.getEntityId()));
        applying = true;
        try { plugin.targets().hurt(caster, target, damage, "", "detonate"); } finally { applying = false; }
        return m.element().id;
    }

    /** Spreads `element` to the nearest unmarked enemy around `center` (not `exclude`). @return true when placed */
    boolean spread(LivingEntity caster, Location center, LivingEntity exclude, String elementId, double radius) {
        Element element = Element.of(elementId);
        if (!enabled || element == null || center.getWorld() == null) return false;
        long now = Bukkit.getCurrentTick();
        for (LivingEntity t : plugin.targets().enemiesNear(caster, center, radius, 8)) {
            if (t.equals(exclude) || !book.spread(t.getUniqueId(), caster.getUniqueId(), element, now)) continue;
            send(t, new ElementProtocol.Mark(t.getEntityId(), element, book.markTicks()));
            vfx.body("wildfire_burn", t, 12);
            return true;
        }
        return false;
    }

    private void later(long ticks, Runnable task) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!enabled) return;
            try { task.run(); }
            catch (RuntimeException e) { plugin.getLogger().warning("원소 반응 오류: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
        }, ticks);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        book.forget(id);
    }

    // ---------------------------------------------------------------- icons

    private void send(LivingEntity target, ElementProtocol.Frame frame) {
        byte[] bytes;
        try { bytes = ElementProtocol.encode(frame); } catch (IllegalArgumentException e) { return; }
        Location at = target.getLocation();
        double r2 = sendRange * sendRange;
        for (Player p : target.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > r2) continue;
            if (!p.getListeningPluginChannels().contains(ElementProtocol.CHANNEL)) continue;
            p.sendPluginMessage(plugin, ElementProtocol.CHANNEL, bytes);
        }
    }

    // ---------------------------------------------------------------- housekeeping

    private void sweep() {
        long now = Bukkit.getCurrentTick();
        book.sweep(now);
    }

    private void clearAll() { book.clear(); }

    String summary() { return "원소 표식 " + book.size() + ", " + vfx.summary() + (enabled ? "" : " (꺼짐)"); }

    @Override public void close() {
        sweeper.cancel();
        clearAll();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, ElementProtocol.CHANNEL);
    }
}
