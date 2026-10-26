package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.StatusProtocol;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * 상태 이상(디버프) 16종. 플레이어·몹 모두 걸린다. 규칙은 전부 여기(서버)에 있고, MagicCodex 클라는
 * {@link StatusProtocol} 로 받은 목록을 머리 위·대상 HUD·내 HUD 에 그리기만 한다.
 *
 * 효과 (오너 결정 2026-10-19, 수치는 config status.*):
 *  기절    이동·점프·근접·활·마법·아티팩트 불가 (몹: AI 정지)          보스: 둔화로 바뀜
 *  수면    기절과 같음, 피해를 받으면 즉시 깸                            보스: 둔화로 바뀜
 *  속박    이동·점프만 불가                                              보스: 면역
 *  무장해제 근접·활·마법 불가
 *  둔화    이동 속도 power(0~0.9) 만큼 감소 (여러 번 걸리면 큰 값)
 *  화상    몸에 불은 붙지 않음. 지속 중 받은 모든 피해 합의 burn-ratio(10%)를 끝날 때 한 번 더
 *  회복봉인 체력 회복(자연·물약·마법 치유)·마나 회복(자연 회복, 마나 물약 등) 차단
 *  약화    내가 주는 마법 피해 감소 (마력 감소)
 *  중력    비행 불가: 빗자루·바닐라 비행·겉날개를 끊고, 비행 마법 시전 불가. 나는 몹은 아래로 끌어내림
 *  고정    이동 마법(status.movement-spells, 비행 포함) 시전 불가, 걸린 뒤 새로 시작한 빗자루 비행도 끊음
 *  독      초당 poison-damage 피해 (기본: 체력 1 아래로는 안 내려감)
 *  나약함  내가 주는 마법이 아닌 피해(근접·화살 등) 감소
 *  실명    플레이어: 바닐라 실명 / 몹: 가까운(blindness-mob-range) 대상만 쫓음
 *  물약봉인 물약 마시기·던지기, 마나 물약 사용 불가
 *  마나번  걸리는 순간 최대 마나의 mana-burn-ratio(10%)를 빼앗음. 아이콘이 떠 있는 동안(내부 쿨) 다시 빼앗기지 않음
 *          (몹 마나는 아직 없음: 몹은 아이콘만)
 *  공포    플레이어에게서 멀어짐: 몹은 건 플레이어(없으면 가장 가까운 플레이어)에게서 도망가고 공격하지 않음,
 *          플레이어는 건 대상에게서 밀려남. 근접·활 공격 불가                     보스: 둔화로 바뀜
 *
 * 바닐라 효과도 같은 아이콘으로 보여 준다 (표시만, 효과는 바닐라 그대로):
 *  구속→둔화, 독·위더→독, 실명·어둠→실명, 나약함→나약함, 몸에 붙은 불(용암 등)→화상
 *
 * 비용: 상태가 하나도 없으면 매 틱 isEmpty 검사뿐. 걸린 개체마다 틱당 몇 줄. 표시 패킷은 바뀔 때 + sync-ticks(2초)마다
 * 범위(icon-range) 안 플레이어에게만.
 */
final class StatusEffects implements Listener, AutoCloseable {
    private static final String NS = "chacademiamagicmythic";
    private static final NamespacedKey MOVE = new NamespacedKey(NS, "status_move");
    private static final NamespacedKey JUMP = new NamespacedKey(NS, "status_jump");
    private static final String SEAL_KEY = "status_recovery_seal";
    private static final EnumSet<Status> HOLD = EnumSet.of(Status.STUN, Status.SLEEP, Status.ROOT);
    private static final EnumSet<Status> NO_ACTION = EnumSet.of(Status.STUN, Status.SLEEP);
    private static final EnumSet<Status> BOSS_SLOWED = EnumSet.of(Status.STUN, Status.SLEEP, Status.FEAR);
    private static final EnumSet<Status> NO_ATTACK = EnumSet.of(Status.STUN, Status.SLEEP, Status.DISARM, Status.FEAR);

    static final class Entry {
        final Status status; long start, until; int total; UUID source; double power;
        double taken, breakAt; boolean addedBlindness; boolean ridingAtStart;
        Entry(Status status) { this.status = status; }
    }
    private static final class Holder {
        LivingEntity entity; final EnumMap<Status, Entry> entries = new EnumMap<>(Status.class);
        boolean frozen, sealed;
        Holder(LivingEntity entity) { this.entity = entity; }
    }
    /** What was last sent for an entity: entity id + signature. */
    private record Shown(int entityId, String signature, UUID world) {}

    private final MagicMythicPlugin plugin;
    private final NamespacedKey prisonKey;
    private final Map<UUID, Holder> holders = new HashMap<>();
    /** Entities with mapped vanilla effects or fire (display only). */
    private final Map<UUID, LivingEntity> vanilla = new HashMap<>();
    /** Vanilla effect first seen: entity -> status -> {firstTick, longestTicks}. */
    private final Map<UUID, EnumMap<Status, long[]>> vanillaSeen = new HashMap<>();
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final BukkitTask task;
    private boolean bursting, bridgeHooked;

    // config
    private double iconRange = 48, bossSlow = 0.45, slowMax = 0.9, burnRatio = 0.1, poisonDamage = 1, magicWeakness = 0.3,
            physicalWeakness = 0.3, manaBurnRatio = 0.1, blindRange = 3;
    private int syncTicks = 40, manaBurnTicks = 100;
    private boolean poisonLethal = false, vanillaIcons = true;
    private Set<String> movementSpells = Set.of(), flightSpells = Set.of();

    StatusEffects(MagicMythicPlugin plugin) {
        this.plugin = plugin;
        this.prisonKey = new NamespacedKey(plugin, "prison_was_aware");
        reload();
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, StatusProtocol.CHANNEL);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        hookBridge();
    }

    void reload() {
        var c = plugin.getConfig();
        iconRange = Math.clamp(c.getDouble("status.icon-range", 48), 8, 128);
        syncTicks = Math.clamp(c.getInt("status.sync-ticks", 40), 10, 400);
        bossSlow = Math.clamp(c.getDouble("status.boss-slow", 0.45), 0, 0.9);
        slowMax = Math.clamp(c.getDouble("status.slow-max", 0.9), 0, 1);
        burnRatio = Math.clamp(c.getDouble("status.burn-ratio", 0.1), 0, 5);
        poisonDamage = Math.clamp(c.getDouble("status.poison-damage", 1), 0, 1000);
        poisonLethal = c.getBoolean("status.poison-lethal", false);
        magicWeakness = Math.clamp(c.getDouble("status.magic-weakness", 0.3), 0, 1);
        physicalWeakness = Math.clamp(c.getDouble("status.physical-weakness", 0.3), 0, 1);
        manaBurnRatio = Math.clamp(c.getDouble("status.mana-burn-ratio", 0.1), 0, 1);
        manaBurnTicks = Math.clamp(c.getInt("status.mana-burn-ticks", 100), 1, 20 * 600);
        blindRange = Math.clamp(c.getDouble("status.blindness-mob-range", 3), 0, 64);
        fearRange = Math.clamp(c.getDouble("status.fear-range", 16), 2, 64);
        fearPush = Math.clamp(c.getDouble("status.fear-push", 0.35), 0, 2);
        vanillaIcons = c.getBoolean("status.vanilla-icons", true);
        movementSpells = c.isList("status.movement-spells") ? lower(c.getStringList("status.movement-spells")) : DEFAULT_MOVEMENT;
        flightSpells = c.isList("status.flight-spells") ? lower(c.getStringList("status.flight-spells")) : Set.of("flight");
    }
    /** 이동 마법 (고정 상태에서 시전 불가): 상승기류, 바람 걸음, 비행, 용수철 지반, 동행 귀환. */
    static final Set<String> DEFAULT_MOVEMENT = Set.of("light_leap", "gale_dash", "flight", "spring_ground", "companion_return");
    private static Set<String> lower(List<String> list) {
        var out = new HashSet<String>();
        for (String s : list) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    private static long now() { return Bukkit.getCurrentTick(); }

    // ================================================================== API (main thread)

    /**
     * Applies (or refreshes) a status. power: 둔화 = speed loss 0..0.9 (0 -> 0.3); other statuses ignore it
     * except 기절 where power > 0 = damage ratio of max health that breaks it early (만년빙옥).
     * @return false when refused (immune, invalid, mana-burn cooldown, boss immunity)
     */
    boolean apply(LivingEntity source, LivingEntity target, Status status, int ticks, double power) {
        if (status == null || target == null || !target.isValid() || target.isDead()) return false;
        if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) return false;
        if (plugin.guards() != null && plugin.guards().has(target, Guards.Type.IMMUNE)) return false;
        if (status == Status.BURN && target instanceof Player p && plugin.passives() != null && plugin.passives().fireImmune(p)) return false; // 화염의 정복자
        ticks = Math.clamp(ticks, 1, 20 * 600);
        boolean boss = plugin.targets().boss(target);
        if (boss && status == Status.ROOT) return false;
        if (boss && BOSS_SLOWED.contains(status)) { status = Status.SLOW; power = bossSlow; }
        if (target instanceof Player tp && status != Status.MANA_BURN && MagicStatusEvent.listened()) {
            var ev = new MagicStatusEvent(source, tp, status.id, ticks);
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) return false;
            ticks = Math.clamp(ev.getTicks(), 1, 20 * 600);
        }
        long now = now();
        Holder h = holders.computeIfAbsent(target.getUniqueId(), k -> new Holder(target));
        h.entity = target;
        Entry e = h.entries.get(status);
        if (status == Status.MANA_BURN) {
            if (e != null) return false; // internal cooldown still running
            manaBurn(target);
        }
        boolean fresh = e == null;
        if (fresh) { e = new Entry(status); h.entries.put(status, e); e.start = now; }
        long until = now + ticks;
        if (until > e.until) {
            if (!fresh) e.start = now;
            e.until = until;
            e.total = (int) (until - e.start);
        }
        if (source != null) e.source = source.getUniqueId();
        switch (status) {
            case SLOW -> e.power = Math.max(e.power, Math.clamp(power <= 0 ? 0.3 : power, 0, slowMax));
            case STUN -> { if (power > 0) e.breakAt = Math.max(e.breakAt, Targets.maxHealth(target) * power); }
            default -> e.power = Math.max(e.power, power);
        }
        if (fresh) start(h, e);
        body(h);
        dirty.add(target.getUniqueId());
        return true;
    }

    /**
     * Custom statuses on the entity right now -> {ticks left, power x 1000} (vanilla look-alike potions are left out:
     * 178 저주의 전가 copies those as potions). Used by 178 to copy them onto other enemies.
     */
    Map<Status, int[]> active(LivingEntity e) {
        var out = new EnumMap<Status, int[]>(Status.class);
        if (e == null) return out;
        Holder h = holders.get(e.getUniqueId());
        for (Line l : lines(e)) {
            if (l.left() == StatusProtocol.INFINITE || l.left() <= 0) continue;
            Entry x = h == null ? null : h.entries.get(l.status());
            if (x == null) continue;
            out.put(l.status(), new int[]{l.left(), (int) Math.round(x.power * 1000)});
        }
        return out;
    }

    /** Crowd control that limits movement or actions (제압의 머리띠 etc.). */
    static final java.util.Set<Status> CONTROL = java.util.EnumSet.of(Status.STUN, Status.SLEEP, Status.ROOT, Status.FEAR, Status.DISARM,
            Status.SLOW, Status.GRAVITY, Status.MOVEMENT_LOCK, Status.BLINDNESS);

    boolean controlled(Entity e) {
        if (e == null) return false;
        Holder h = holders.get(e.getUniqueId());
        if (h != null) for (Status s : h.entries.keySet()) if (CONTROL.contains(s)) return true;
        return false;
    }

    boolean has(Entity e, Status s) {
        if (e == null) return false;
        Holder h = holders.get(e.getUniqueId());
        if (h != null && h.entries.containsKey(s)) return true;
        return false;
    }
    /** Status active on the entity, including the vanilla look-alike (for "불타는 대상" spell targets). */
    boolean shows(LivingEntity e, Status s) {
        if (has(e, s)) return true;
        return s == Status.BURN && e.getFireTicks() > 0;
    }

    /** Removes these statuses (no burn burst). Empty = all. */
    void clear(LivingEntity e, Status... which) {
        Holder h = holders.get(e.getUniqueId());
        if (h == null) return;
        var list = EnumSet.noneOf(Status.class);
        if (which.length == 0) list.addAll(h.entries.keySet()); else list.addAll(Arrays.asList(which));
        for (Status s : list) { Entry x = h.entries.remove(s); if (x != null) end(h, x, false); }
        body(h);
        if (h.entries.isEmpty()) forget(h);
        dirty.add(e.getUniqueId());
    }

    /** 약화: multiplier for spell damage dealt by this caster. */
    double spellMultiplier(LivingEntity caster) { return caster != null && has(caster, Status.MAGIC_WEAKNESS) ? 1 - magicWeakness : 1; }
    /** 회복봉인 */
    boolean recoverySealed(Entity e) { return has(e, Status.RECOVERY_SEAL); }
    /** 물약봉인 */
    boolean potionSealed(Entity e) { return has(e, Status.POTION_SEAL); }

    /** Why this player may not cast `spell` now, or null. */
    String castBlock(Player p, String spell) {
        Holder h = holders.get(p.getUniqueId());
        if (h == null) return null;
        String id = spell == null ? "" : spell.toLowerCase(Locale.ROOT);
        if (h.entries.containsKey(Status.STUN)) return "기절 상태에서는 마법을 쓸 수 없어요";
        if (h.entries.containsKey(Status.SLEEP)) return "수면 상태에서는 마법을 쓸 수 없어요";
        if (h.entries.containsKey(Status.DISARM)) return "무장해제 상태에서는 마법을 쓸 수 없어요";
        if (h.entries.containsKey(Status.GRAVITY) && flightSpells.contains(id)) return "중력 상태에서는 비행할 수 없어요";
        if (h.entries.containsKey(Status.MOVEMENT_LOCK) && (movementSpells.contains(id) || flightSpells.contains(id)))
            return "고정 상태에서는 이동 마법을 쓸 수 없어요";
        return null;
    }
    String artifactBlock(Player p) {
        Holder h = holders.get(p.getUniqueId());
        if (h == null) return null;
        if (h.entries.containsKey(Status.STUN)) return "기절 상태에서는 아티팩트를 쓸 수 없어요";
        if (h.entries.containsKey(Status.SLEEP)) return "수면 상태에서는 아티팩트를 쓸 수 없어요";
        return null;
    }

    int count() { return holders.size(); }
    String summary() { return "상태이상 " + holders.size() + "체 (바닐라 표시 " + vanilla.size() + ")"; }

    // ================================================================== start / end / body

    private void start(Holder h, Entry e) {
        LivingEntity t = h.entity;
        switch (e.status) {
            case GRAVITY -> grounded(t, true);
            case MOVEMENT_LOCK -> e.ridingAtStart = t instanceof Player p && BroomLink.riding(p);
            case BLINDNESS -> {
                if (t instanceof Player p) {
                    var old = p.getPotionEffect(PotionEffectType.BLINDNESS);
                    if (old == null || (old.getDuration() >= 0 && old.getDuration() < e.total)) {
                        e.addedBlindness = true;
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, e.total, 0, false, false, false));
                    }
                }
            }
            case SLEEP, STUN, FEAR -> { if (t instanceof Mob m) m.setTarget(null); }
            default -> {}
        }
    }

    /** `burst`: false when removed early (cleanse, death) - 화상 then deals nothing. */
    private void end(Holder h, Entry e, boolean burst) {
        LivingEntity t = h.entity;
        switch (e.status) {
            case BURN -> { if (burst) burnBurst(t, e); }
            case BLINDNESS -> { if (e.addedBlindness && t instanceof Player p) p.removePotionEffect(PotionEffectType.BLINDNESS); }
            default -> {}
        }
    }

    /** Re-applies every attribute / AI / mana effect from the current entries. */
    private void body(Holder h) {
        LivingEntity t = h.entity;
        if (t == null || !t.isValid()) return;
        boolean hold = false; double slow = 0;
        for (Entry e : h.entries.values()) {
            if (HOLD.contains(e.status)) hold = true;
            if (e.status == Status.SLOW) slow = Math.max(slow, e.power);
        }
        double move = hold ? -1 : -slow;
        modifier(t, Attribute.MOVEMENT_SPEED, MOVE, move);
        modifier(t, Attribute.FLYING_SPEED, MOVE, move);
        modifier(t, Attribute.JUMP_STRENGTH, JUMP, hold ? -1 : 0);
        boolean freeze = t instanceof Mob && (h.entries.containsKey(Status.STUN) || h.entries.containsKey(Status.SLEEP));
        if (freeze && !h.frozen && t instanceof Mob m) { Marks.freezeMob(m, prisonKey); h.frozen = true; }
        if (!freeze && h.frozen && t instanceof Mob m) { Marks.recoverMob(m, prisonKey); h.frozen = false; }
        boolean seal = h.entries.containsKey(Status.RECOVERY_SEAL);
        if (seal != h.sealed && t instanceof Player p) { Links.regen(p.getUniqueId(), SEAL_KEY, seal ? 0 : 1); }
        h.sealed = seal;
    }

    private static void modifier(LivingEntity t, Attribute attribute, NamespacedKey key, double amount) {
        var a = t.getAttribute(attribute);
        if (a == null) return;
        AttributeModifier old = null;
        for (var mod : a.getModifiers()) if (key.equals(mod.getKey())) { old = mod; break; }
        if (amount == 0) { if (old != null) a.removeModifier(key); return; }
        if (old != null && old.getAmount() == amount) return;
        if (old != null) a.removeModifier(key);
        a.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void forget(Holder h) {
        holders.remove(h.entity.getUniqueId());
    }

    private void releaseAll(Holder h) {
        for (Entry e : new ArrayList<>(h.entries.values())) end(h, e, false);
        h.entries.clear();
        body(h);
        forget(h);
    }

    private void manaBurn(LivingEntity t) {
        if (!(t instanceof Player p)) return; // mob mana: not yet (owner 2026-10-19)
        Double cur = Links.mana(p), max = Links.maxMana(p);
        if (cur == null || max == null) return;
        double take = Math.min(cur, max * manaBurnRatio);
        if (take > 0) Links.consume(p, take);
    }

    private void burnBurst(LivingEntity t, Entry e) {
        if (t == null || !t.isValid() || t.isDead() || e.taken <= 0 || burnRatio <= 0) return;
        double amount = e.taken * burnRatio;
        Entity src = e.source == null ? null : Bukkit.getEntity(e.source);
        bursting = true;
        try {
            // 전투 스펙 2026-10-26: 화상 폭발 = 화상 중 실제로 받은 피해(이미 마법 방어로 줄어든 값) × 비율 → 플레이어끼리는 다시 줄이지 않음 (FLAT, 이중 감소 방지).
            if (src instanceof LivingEntity caster && caster.isValid() && !caster.equals(t) && plugin.targets().enemy(caster, t))
                plugin.protection().damage(caster, t, amount, MagicProtection.pvp(caster, t) ? MagicProtection.Mode.FLAT : MagicProtection.Mode.VANILLA);
            else if (!friendlySource(src, t)) plugin.protection().sourceless(t, amount, false, src instanceof Player && t instanceof Player && !src.equals(t)); // #23: 그사이 같은 파티가 된 시전자의 화상은 터지지 않음
        } finally { bursting = false; }
    }

    /** 중력: knocks the entity out of the air. */
    private void grounded(LivingEntity t, boolean first) {
        if (t instanceof Player p) {
            if (BroomLink.riding(p)) BroomLink.stop(p);
            if (p.isFlying() && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) p.setFlying(false);
            if (p.isGliding()) p.setGliding(false);
        } else if (!t.isOnGround() && flyer(t)) {
            var v = t.getVelocity();
            if (v.getY() > -0.6) t.setVelocity(v.setY(-0.6));
        }
    }
    private static boolean flyer(Entity e) {
        return e instanceof Flying || e instanceof Bat || e instanceof Bee || e instanceof Vex || e instanceof Blaze || e instanceof Parrot
            || e instanceof Allay || e instanceof Wither || e instanceof EnderDragon || e instanceof Breeze;
    }

    // ================================================================== tick

    private void tick() {
        long now = now();
        if (!holders.isEmpty()) {
            for (Holder h : new ArrayList<>(holders.values())) {
                LivingEntity t = h.entity;
                if (t == null || !t.isValid() || t.isDead()) {
                    if (t != null && t.isDead()) dirty.add(t.getUniqueId());
                    releaseAll(h);
                    continue;
                }
                boolean changed = false;
                for (var it = h.entries.values().iterator(); it.hasNext(); ) {
                    Entry e = it.next();
                    if (now >= e.until) { it.remove(); end(h, e, true); changed = true; continue; }
                    long age = now - e.start;
                    switch (e.status) {
                        case POISON -> { if (age > 0 && age % 20 == 0) poison(t, e); }
                        case GRAVITY -> { if (age % 5 == 0 || !(t instanceof Player)) grounded(t, false); }
                        case MOVEMENT_LOCK -> {
                            if (age % 5 == 0 && t instanceof Player p && !e.ridingAtStart && BroomLink.riding(p)) BroomLink.stop(p);
                            if (age % 5 == 0 && t instanceof Player p && e.ridingAtStart && !BroomLink.riding(p)) e.ridingAtStart = false;
                        }
                        case FEAR -> { if (age % 5 == 0) flee(t, e); }
                        case BLINDNESS -> {
                            if (age % 10 == 0 && t instanceof Mob m && m.getTarget() != null
                                && (m.getTarget().getWorld() != m.getWorld() || m.getTarget().getLocation().distanceSquared(m.getLocation()) > blindRange * blindRange))
                                m.setTarget(null);
                        }
                        default -> {}
                    }
                }
                if (h.entries.isEmpty()) { body(h); forget(h); dirty.add(t.getUniqueId()); continue; }
                if (changed) { body(h); dirty.add(t.getUniqueId()); }
                boolean hold = false;
                for (Status s : HOLD) if (h.entries.containsKey(s)) { hold = true; break; }
                if (hold) {
                    if (t instanceof Mob m) m.getPathfinder().stopPathfinding();
                    var v = t.getVelocity();
                    if (v.getX() != 0 || v.getZ() != 0 || v.getY() > 0) t.setVelocity(new org.bukkit.util.Vector(0, Math.min(0, v.getY()), 0));
                }
            }
        }
        if (vanillaIcons && !vanilla.isEmpty() && now % 10 == 0) {
            for (var it = vanilla.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                LivingEntity e = en.getValue();
                if (!e.isValid() || e.isDead() || !hasVanilla(e)) { it.remove(); vanillaSeen.remove(en.getKey()); }
                dirty.add(en.getKey());
            }
        }
        if (now % syncTicks == 0) for (var id : shown.keySet()) dirty.add(id);
        if (!dirty.isEmpty()) flush();
    }

    private double fearRange = 16, fearPush = 0.35;

    /** 공포: mobs run from the player who feared them (else the nearest player); players are pushed off the source. */
    private void flee(LivingEntity t, Entry e) {
        Entity src = e.source == null ? null : Bukkit.getEntity(e.source);
        Location at = t.getLocation();
        if (t instanceof Mob m) {
            LivingEntity from = src instanceof Player p && p.isValid() && p.getWorld() == t.getWorld()
                && p.getLocation().distanceSquared(at) <= fearRange * fearRange * 4 ? p : null;
            if (from == null) {
                double best = fearRange * fearRange;
                for (Player p : t.getWorld().getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
                    double d = p.getLocation().distanceSquared(at);
                    if (d < best) { best = d; from = p; }
                }
            }
            m.setTarget(null);
            if (from == null) return;
            var away = at.toVector().subtract(from.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new org.bukkit.util.Vector(Math.random() - .5, 0, Math.random() - .5);
            var goal = at.clone().add(away.normalize().multiply(8));
            m.getPathfinder().moveTo(goal, 1.3);
        } else if (src instanceof LivingEntity from && from.isValid() && from.getWorld() == t.getWorld()
            && from.getLocation().distanceSquared(at) <= fearRange * fearRange) {
            var away = at.toVector().subtract(from.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) return;
            var v = t.getVelocity();
            t.setVelocity(away.normalize().multiply(fearPush).setY(Math.min(v.getY(), 0.1)));
        }
    }

    private void poison(LivingEntity t, Entry e) {
        if (poisonDamage <= 0) return;
        double amount = poisonLethal ? poisonDamage : Math.min(poisonDamage, t.getHealth() - 1);
        if (amount <= 0) return;
        Entity src = e.source == null ? null : Bukkit.getEntity(e.source);
        if (friendlySource(src, t)) return; // #23 독 틱: 아군이 건 독은 피해 없음
        // 전투 스펙 2026-10-26: 플레이어가 플레이어에게 건 독은 마법 방어(MagicArmor)로 — 바닐라 보호 인첸트로 80%까지 막히지 않게. 나머지는 예전 그대로.
        plugin.protection().sourceless(t, amount, src instanceof Player && t instanceof Player && !src.equals(t), false);
    }

    /** #23: the status came from another player who is now on the same side (party / match team). */
    private boolean friendlySource(Entity src, LivingEntity t) {
        return src instanceof Player a && t instanceof Player b && !a.equals(b) && plugin.allies() != null && plugin.allies().allied(a, b);
    }

    // ================================================================== display

    private static Status vanillaStatus(PotionEffectType type) {
        if (type == null) return null;
        if (type.equals(PotionEffectType.SLOWNESS)) return Status.SLOW;
        if (type.equals(PotionEffectType.POISON) || type.equals(PotionEffectType.WITHER)) return Status.POISON;
        if (type.equals(PotionEffectType.BLINDNESS) || type.equals(PotionEffectType.DARKNESS)) return Status.BLINDNESS;
        if (type.equals(PotionEffectType.WEAKNESS)) return Status.PHYSICAL_WEAKNESS;
        return null;
    }
    private static boolean hasVanilla(LivingEntity e) {
        if (e.getFireTicks() > 0) return true;
        for (PotionEffect fx : e.getActivePotionEffects()) if (vanillaStatus(fx.getType()) != null) return true;
        return false;
    }
    private void watch(Entity e) {
        if (!vanillaIcons || !(e instanceof LivingEntity le) || !le.isValid()) return;
        vanilla.put(le.getUniqueId(), le);
        dirty.add(le.getUniqueId());
    }

    private record Line(Status status, long start, int left, int total) {}

    /** Custom + vanilla, one per status (longest), in application order (oldest first). */
    private List<Line> lines(LivingEntity e) {
        long now = now();
        var best = new EnumMap<Status, Line>(Status.class);
        Holder h = holders.get(e.getUniqueId());
        if (h != null) for (Entry x : h.entries.values()) {
            int left = StatusProtocol.clampTicks(x.until - now);
            if (left > 0) best.put(x.status, new Line(x.status, x.start, left, Math.max(left, StatusProtocol.clampTicks(x.total))));
        }
        if (vanillaIcons && vanilla.containsKey(e.getUniqueId())) {
            var seen = vanillaSeen.computeIfAbsent(e.getUniqueId(), k -> new EnumMap<>(Status.class));
            var present = EnumSet.noneOf(Status.class);
            for (PotionEffect fx : e.getActivePotionEffects()) {
                Status s = vanillaStatus(fx.getType());
                if (s == null || (s == Status.BLINDNESS && h != null && h.entries.containsKey(Status.BLINDNESS))) continue;
                present.add(s);
                boolean infinite = fx.isInfinite() || fx.getDuration() < 0;
                int left = infinite ? StatusProtocol.INFINITE : StatusProtocol.clampTicks(fx.getDuration());
                long[] first = seen.computeIfAbsent(s, k -> new long[]{now, left});
                if (left != StatusProtocol.INFINITE && left > first[1]) { first[0] = now; first[1] = left; }
                offer(best, new Line(s, first[0], left, infinite ? StatusProtocol.INFINITE : (int) Math.max(left, first[1])));
            }
            if (e.getFireTicks() > 0 && !(e instanceof Player p && p.getGameMode() == GameMode.CREATIVE)) {
                present.add(Status.BURN);
                int left = StatusProtocol.clampTicks(e.getFireTicks());
                long[] first = seen.computeIfAbsent(Status.BURN, k -> new long[]{now, left});
                if (left > first[1]) { first[0] = now; first[1] = left; }
                offer(best, new Line(Status.BURN, first[0], left, (int) Math.max(left, first[1])));
            }
            seen.keySet().retainAll(present);
        }
        var out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingLong(Line::start).thenComparing(l -> l.status().ordinal()));
        return out.size() > StatusProtocol.MAX_ENTRIES ? out.subList(0, StatusProtocol.MAX_ENTRIES) : out;
    }
    private static void offer(EnumMap<Status, Line> best, Line l) {
        Line old = best.get(l.status());
        if (old == null) { best.put(l.status(), l); return; }
        if (old.left() == StatusProtocol.INFINITE) return;
        if (l.left() == StatusProtocol.INFINITE || l.left() > old.left()) best.put(l.status(), new Line(l.status(), Math.min(old.start(), l.start()), l.left(), l.total()));
    }

    private void flush() {
        long now = now();
        boolean periodic = now % syncTicks == 0;
        for (UUID id : dirty) {
            Entity raw = Bukkit.getEntity(id);
            LivingEntity e = raw instanceof LivingEntity le && le.isValid() && !le.isDead() ? le : null;
            Shown before = shown.get(id);
            List<Line> list = e == null ? List.of() : lines(e);
            if (list.isEmpty()) {
                if (before != null) {
                    shown.remove(id);
                    send(raw, before.entityId(), new StatusProtocol.Clear(before.entityId()));
                }
                continue;
            }
            var sb = new StringBuilder();
            for (Line l : list) sb.append(l.status().ordinal()).append(':').append(l.left() == StatusProtocol.INFINITE ? -1 : (now + l.left()) / 4).append(',');
            String sig = sb.toString();
            if (!periodic && before != null && before.signature().equals(sig) && before.entityId() == e.getEntityId()) continue;
            shown.put(id, new Shown(e.getEntityId(), sig, e.getWorld().getUID()));
            var entries = new ArrayList<StatusProtocol.Entry>(list.size());
            for (Line l : list) entries.add(new StatusProtocol.Entry(l.status(), l.left(), l.total()));
            send(e, e.getEntityId(), new StatusProtocol.Set(e.getEntityId(), entries));
        }
        dirty.clear();
    }

    private void send(Entity at, int entityId, StatusProtocol.Frame frame) {
        byte[] bytes;
        try { bytes = StatusProtocol.encode(frame); } catch (IllegalArgumentException ex) { return; }
        if (at == null) {
            // Entity gone: tell everyone who could have seen it.
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getListeningPluginChannels().contains(StatusProtocol.CHANNEL)) p.sendPluginMessage(plugin, StatusProtocol.CHANNEL, bytes);
            return;
        }
        Location loc = at.getLocation();
        double r2 = iconRange * iconRange;
        for (Player p : at.getWorld().getPlayers()) {
            if (!p.equals(at) && p.getLocation().distanceSquared(loc) > r2) continue;
            if (!p.getListeningPluginChannels().contains(StatusProtocol.CHANNEL)) continue;
            p.sendPluginMessage(plugin, StatusProtocol.CHANNEL, bytes);
        }
    }

    // ================================================================== events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent e) {
        if (holders.isEmpty() || !(e.getEntity() instanceof LivingEntity victim)) return;
        Holder h = holders.get(victim.getUniqueId());
        if (h == null) return;
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        Entry burn = h.entries.get(Status.BURN);
        if (burn != null && !bursting) burn.taken += dmg;
        boolean changed = false;
        if (h.entries.remove(Status.SLEEP) != null) changed = true;
        Entry stun = h.entries.get(Status.STUN);
        if (stun != null && stun.breakAt > 0 && (stun.taken += dmg) >= stun.breakAt) { h.entries.remove(Status.STUN); changed = true; }
        if (changed) {
            Bukkit.getScheduler().runTask(plugin, () -> { body(h); if (h.entries.isEmpty()) forget(h); });
            dirty.add(victim.getUniqueId());
        }
    }

    /** Attacker side: 기절·수면·무장해제 = no melee; 나약함 = less non-magic damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent e) {
        if (holders.isEmpty()) return;
        LivingEntity attacker = attacker(e.getDamager());
        if (attacker == null) return;
        Holder h = holders.get(attacker.getUniqueId());
        if (h == null) return;
        boolean magic = plugin.protection().magicDamaging();
        var cause = e.getCause();
        if (!magic && (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            for (Status s : NO_ATTACK) if (h.entries.containsKey(s)) { e.setCancelled(true); return; }
        }
        if (!magic && h.entries.containsKey(Status.PHYSICAL_WEAKNESS)) e.setDamage(e.getDamage() * (1 - physicalWeakness));
    }
    private static LivingEntity attacker(Entity damager) {
        if (damager instanceof LivingEntity le) return le;
        if (damager instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof LivingEntity le) return le; }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent e) {
        Holder h = holders.get(e.getEntity().getUniqueId());
        if (h == null) return;
        for (Status s : NO_ATTACK) if (h.entries.containsKey(s)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void regain(EntityRegainHealthEvent e) {
        if (recoverySealed(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void drink(PlayerItemConsumeEvent e) {
        if (!potionSealed(e.getPlayer()) || !potion(e.getItem())) return;
        e.setCancelled(true);
        ChacaLink.notify(e.getPlayer(), "warning", "magic-mythic:potion-seal", "물약봉인 상태에서는 물약을 마실 수 없어요", "", 2000);
    }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void thrown(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof ThrownPotion pot && pot.getShooter() instanceof Player p && potionSealed(p)) {
            e.setCancelled(true);
            ChacaLink.notify(p, "warning", "magic-mythic:potion-seal", "물약봉인 상태에서는 물약을 던질 수 없어요", "", 2000);
        }
    }
    static boolean potion(ItemStack it) {
        if (it == null) return false;
        var t = it.getType();
        return t == Material.POTION || t == Material.SPLASH_POTION || t == Material.LINGERING_POTION || t == Material.OMINOUS_BOTTLE
            || UtilMech.isManaPotion(it) || Potions.isHealthPotion(it);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void potionChanged(EntityPotionEffectEvent e) {
        PotionEffectType type = e.getModifiedType();
        if (vanillaStatus(type) != null) watch(e.getEntity());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combust(EntityCombustEvent e) {
        Entity en = e.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> { if (en.isValid() && en.getFireTicks() > 0) watch(en); });
    }

    @EventHandler public void death(EntityDeathEvent e) {
        Holder h = holders.get(e.getEntity().getUniqueId());
        if (h != null) releaseAll(h);
        vanilla.remove(e.getEntity().getUniqueId());
        vanillaSeen.remove(e.getEntity().getUniqueId());
        dirty.add(e.getEntity().getUniqueId());
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        Holder h = holders.get(e.getPlayer().getUniqueId());
        if (h != null) releaseAll(h);
        vanilla.remove(e.getPlayer().getUniqueId());
        vanillaSeen.remove(e.getPlayer().getUniqueId());
        Shown s = shown.remove(e.getPlayer().getUniqueId());
        if (s != null) send(null, s.entityId(), new StatusProtocol.Clear(s.entityId()));
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && hasVanilla(p)) watch(p); }, 20);
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) { dirty.add(e.getPlayer().getUniqueId()); }
    @EventHandler public void unload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            Holder h = holders.get(en.getUniqueId());
            if (h != null) releaseAll(h);
            vanilla.remove(en.getUniqueId());
            vanillaSeen.remove(en.getUniqueId());
            Shown s = shown.remove(en.getUniqueId());
            if (s != null) send(null, s.entityId(), new StatusProtocol.Clear(s.entityId()));
        }
    }

    // ================================================================== MagicCodexBridge (by name, no compile dependency)

    @EventHandler public void pluginEnabled(PluginEnableEvent e) { if (e.getPlugin().getName().equals("MagicCodexBridge")) hookBridge(); }

    private void hookBridge() {
        if (bridgeHooked) return;
        var bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) return;
        ClassLoader cl = bridge.getClass().getClassLoader();
        boolean cast = register(cl, "school.magiccodex.paper.MagicCastAttemptEvent", "getSpellId", this::castBlock);
        register(cl, "school.magiccodex.paper.ArtifactUseAttemptEvent", "getArtifactId", (p, id) -> artifactBlock(p));
        bridgeHooked = cast;
        if (!cast) plugin.getLogger().warning("MagicCastAttemptEvent 없음: 기절·무장해제 중 마법 차단이 동작하지 않음 (MagicCodexBridge 업데이트 필요)");
    }

    private interface Block { String reason(Player p, String id); }

    private boolean register(ClassLoader cl, String className, String idGetter, Block block) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(className, true, cl);
            Method player = type.getMethod("getPlayer"), id = type.getMethod(idGetter), setCancelled = type.getMethod("setCancelled", boolean.class),
                setMessage = type.getMethod("setMessage", String.class);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGH, (l, ev) -> {
                if (!type.isInstance(ev) || holders.isEmpty()) return;
                try {
                    String why = block.reason((Player) player.invoke(ev), (String) id.invoke(ev));
                    if (why != null) { setCancelled.invoke(ev, true); setMessage.invoke(ev, why); }
                } catch (ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().warning(className + " 처리 실패: " + ex); }
            }, plugin, true);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    // ================================================================== shutdown

    @Override public void close() {
        task.cancel();
        for (Holder h : new ArrayList<>(holders.values())) releaseAll(h);
        holders.clear();
        for (var s : shown.values()) {
            byte[] bytes = StatusProtocol.encode(new StatusProtocol.Clear(s.entityId()));
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getListeningPluginChannels().contains(StatusProtocol.CHANNEL)) p.sendPluginMessage(plugin, StatusProtocol.CHANNEL, bytes);
        }
        shown.clear();
        vanilla.clear();
        vanillaSeen.clear();
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, StatusProtocol.CHANNEL);
    }
}
