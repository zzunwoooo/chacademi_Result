package school.magiccodex.paper;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * Artifact abilities (아티팩트 효과, 2026-10-15). Passive effects follow what is equipped (ArtifactBridge, refreshed
 * every second and right after equip/unequip); use-key effects are in {@link ArtifactActions}, everyday
 * (gathering / food / items) effects in {@link ArtifactLife}.
 *
 * Hooks used:
 *  - stats: vanilla attribute modifiers (max health, speed, knockback, jump, step) and ManaService keyed modifiers
 *    (max mana, regen multiplier, haste) under the key "artifact:effect" - recomputed, never stacked.
 *  - magic: ChAcademiaMagicMythic events MagicDamageEvent / MagicReactionEvent / MagicMarkEvent / MagicHealEvent,
 *    registered by reflection when that plugin enables (no compile dependency, same as BroomLink there).
 *  - casts: {@link #cast} is called by ManaBridge after a successful cast.
 *  - party: {@link PartyRoster} (Velocity pushes the party list).
 *  - combat (전투 중, owner 2026-10-15): dealt or took damage from an enemy (monster or player) in the last 5 s.
 * All state is in memory and per server. Everything runs on the server thread.
 */
final class ArtifactEffects implements Listener {
    static final String MOD = "artifact:effect";
    static final long COMBAT_MS = 5_000;
    /** Same tag as MagicMythic Reactions.ANCHOR_TAG: not pulled by 모래 회오리. */
    static final String ANCHOR_TAG = "chacademi_anchor";
    final MagicCodexBridge plugin;
    final ArtifactBridge artifacts;
    final ManaService mana;
    final PartyRoster party;
    final NpcSocialService social;
    final Random random = new Random();
    private final NamespacedKey hpFlatKey, hpPctKey, speedKey, knockKey, jumpKey, stepKey;
    private final Map<UUID, Set<String>> worn = new HashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    /** reaction_chain_orb: target -> who reacted on it and with which element, until when. */
    private final Map<UUID, Orb> orbs = new HashMap<>();
    private record Orb(UUID caster, String element, long until) {}
    private final MagicHooks hooks = new MagicHooks();
    final ArtifactActions actions;
    final ArtifactLife life;
    final ArtifactComfort comfort;
    final ArtifactBroom broom;
    /** 마법 규칙 아티팩트 5종 (순서의 목걸이·무지개 프리즘·혼돈의 주사위·단 하나의 서·거꾸로 된 마나병). */
    final ArtifactMagicRules rules;
    final ArtifactMotions motions;
    /** 그룹 D (2026-10-26): 화염 정령·거울 쌍둥이·수다 앵무새 / 그림자 군주의 가면 / 변신 가면 (null = ModelEngine 없음). */
    final ArtifactCompanions companions;
    final ArtifactShadows shadows;
    final ArtifactShapeshift shapeshift;

    /** Time-limited bonus from one source (refreshing the same source replaces it). */
    record Timed(long until, double speed, double haste) {}

    /** Per-player runtime state. */
    static final class Run {
        long combatAt = -1, hurtAt = -1;
        boolean inCombat;
        final Map<String, Timed> timed = new HashMap<>();
        final Map<String, Long> ready = new HashMap<>();
        // casts
        long castIndex;
        boolean castSneak, castSprint, castCalm, castCheer, cheerArmed, chantArmed;
        double manaSpent;
        final long[] castTimes = {-1, -1, -1};
        String curElement, prevElement, lastElement = "";
        long elementCast = -1, huntCast = -1;
        UUID huntTarget;
        int huntStacks;
        final Deque<long[]> reactions = new ArrayDeque<>(); // {reaction ordinal hash, time}
        int notes;
        long manaDropUntil, comebackUntil, deathAt = -1;
        // applied values (avoid re-publishing unchanged numbers)
        double ownMax, ownHaste, ownRegen = 1;
        boolean manaApplied;
        int lovers;
        double expCarry;
    }

    ArtifactEffects(MagicCodexBridge plugin, ArtifactBridge artifacts, ManaService mana, PartyRoster party, NpcSocialService social) {
        this.plugin = plugin; this.artifacts = artifacts; this.mana = mana; this.party = party; this.social = social;
        hpFlatKey = new NamespacedKey(plugin, "artifact_health"); hpPctKey = new NamespacedKey(plugin, "artifact_health_pct");
        speedKey = new NamespacedKey(plugin, "artifact_speed"); knockKey = new NamespacedKey(plugin, "artifact_knockback");
        jumpKey = new NamespacedKey(plugin, "artifact_jump"); stepKey = new NamespacedKey(plugin, "artifact_step");
        actions = new ArtifactActions(this);
        life = new ArtifactLife(this);
        comfort = new ArtifactComfort(this);
        broom = new ArtifactBroom(this);
        rules = new ArtifactMagicRules(this);
        motions = new ArtifactMotions(this); // 모션 아티팩트 9종 (2026-10-26), 자체 리스너·0.5초 타이머
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(rules, plugin);
        Bukkit.getPluginManager().registerEvents(actions, plugin);
        Bukkit.getPluginManager().registerEvents(life, plugin);
        Bukkit.getPluginManager().registerEvents(comfort, plugin);
        companions = new ArtifactCompanions(this);
        shadows = new ArtifactShadows(this);
        shapeshift = new ArtifactShapeshift(this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, this::fastTick, 2, 2);
        Bukkit.getScheduler().runTask(plugin, hooks::connect);
        if (social != null) social.affinityModifier(this::affinity);
        artifacts.effects(this);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    // ------------------------------------------------------------------ equipped / state

    /** 관리자 쿨타임초기화: 내부 재사용 대기(ready)를 지운다. */
    void resetCooldowns(Player p) { var r = runs.get(p.getUniqueId()); if (r != null) r.ready.clear(); }

    /** 효과 발동 알림 (액션바 대신, 같은 효과는 2초 안에 다시 띄우지 않음). */
    static void notice(Player p, String key, String title, String body) { CmdKit.notify(p, "system", "artifact:" + key, title, body, 2000); }

    boolean has(Player p, String id) { var s = worn.get(p.getUniqueId()); return s != null && s.contains(id); }
    boolean wearsAny(Player p) { var s = worn.get(p.getUniqueId()); return s != null && !s.isEmpty(); }
    Run run(Player p) { return runs.computeIfAbsent(p.getUniqueId(), k -> new Run()); }

    /** Called after equip/unequip and on join. */
    void refresh(Player p) {
        if (!p.isOnline()) return;
        worn.put(p.getUniqueId(), wornNow(p));
        apply(p);
    }

    /** Nothing counts as equipped until the shared state (school/wild) is loaded: the local PDC may be stale. */
    private Set<String> wornNow(Player p) {
        return plugin.playerStateReady(p) ? new HashSet<>(artifacts.equipped(p)) : Set.of();
    }

    /** After-arrival hook (PlayerStats): stats and current health/mana are already back; hand back a kept item. */
    void arrived(Player p) { life.returnKept(p); }

    // ------------------------------------------------------------------ shared runtime (school/wild)

    /**
     * Short-lived artifact state that must survive a server switch, stored in codex_player_state.artifact_runtime:
     * internal cooldowns (r:), timed buffs (t:), comeback / mana-drop windows, armed flags, counters.
     * (Current health/mana are restored by PlayerStats for every stat source, not here.)
     * Times are wall-clock milliseconds (school and wild share the host clock).
     */
    String runtimeText(Player p) {
        var r = runs.get(p.getUniqueId());
        var out = new StringBuilder();
        long now = System.currentTimeMillis();
        if (r != null) {
            r.ready.forEach((k, until) -> { if (until > now && k.matches("[a-z0-9_]{1,48}")) out.append("r:").append(k).append('=').append(until).append(';'); });
            r.timed.forEach((k, t) -> { if (t.until() > now && k.matches("[a-z0-9_]{1,48}"))
                out.append("t:").append(k).append('=').append(t.until()).append(',').append(t.speed()).append(',').append(t.haste()).append(';'); });
            if (r.comebackUntil > now) out.append("c=").append(r.comebackUntil).append(';');
            if (r.manaDropUntil > now) out.append("m=").append(r.manaDropUntil).append(';');
            if (r.chantArmed) out.append("chant=1;");
            if (r.cheerArmed) out.append("cheer=1;");
            if (r.notes > 0) out.append("notes=").append(r.notes).append(';');
            if (r.manaSpent > 0) out.append("spent=").append(Math.round(r.manaSpent * 100) / 100.0).append(';');
            if (r.expCarry > 0) out.append("exp=").append(Math.round(r.expCarry * 1000) / 1000.0).append(';');
        }
        out.append(rules.runtime(p));
        return out.length() > 4000 ? out.substring(0, out.lastIndexOf(";", 4000) + 1) : out.toString();
    }

    void restoreRuntime(Player p, String text) {
        var r = new Run();
        runs.put(p.getUniqueId(), r);
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        for (String part : text.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            try {
                String k = kv[0], v = kv[1];
                if (k.startsWith("r:")) { long until = Long.parseLong(v); if (until > now && until - now <= 86_400_000) r.ready.put(k.substring(2), until); }
                else if (k.startsWith("t:")) {
                    String[] t = v.split(",");
                    long until = Long.parseLong(t[0]);
                    double speed = Math.clamp(Double.parseDouble(t[1]), -1, 2), haste = Math.clamp(Double.parseDouble(t[2]), -1000, 1000);
                    if (until > now && until - now <= 3_600_000) r.timed.put(k.substring(2), new Timed(until, speed, haste));
                }
                else switch (k) {
                    case "c" -> r.comebackUntil = Math.min(Long.parseLong(v), now + 10_000);
                    case "m" -> r.manaDropUntil = Math.min(Long.parseLong(v), now + 3_000);
                    case "chant" -> r.chantArmed = true;
                    case "cheer" -> r.cheerArmed = true;
                    case "notes" -> r.notes = Math.clamp(Integer.parseInt(v), 0, 9);
                    case "spent" -> r.manaSpent = Math.clamp(Double.parseDouble(v), 0, 200);
                    case "exp" -> r.expCarry = Math.clamp(Double.parseDouble(v), 0, 1);
                    default -> rules.restore(p, k, v, now);
                }
            } catch (RuntimeException ignored) { /* one bad entry never blocks the rest */ }
        }
    }

    /** One internal cooldown per player and key. @return true when ready (and starts the cooldown) */
    boolean ready(Player p, String key, long ms) {
        var r = run(p); long now = System.currentTimeMillis();
        if (r.ready.getOrDefault(key, 0L) > now) return false;
        r.ready.put(key, now + ms); return true;
    }

    void timed(Player p, String source, long ms, double speed, double haste) {
        run(p).timed.put(source, new Timed(System.currentTimeMillis() + ms, speed, haste));
        apply(p);
    }

    boolean inCombat(Player p) { var r = runs.get(p.getUniqueId()); return r != null && inCombat(r, System.currentTimeMillis()); }
    private static boolean inCombat(Run r, long now) { return r.combatAt >= 0 && now - r.combatAt < COMBAT_MS; }

    // ------------------------------------------------------------------ stats

    void apply(Player p) {
        if (!p.isOnline() || !plugin.playerStateReady(p)) return;
        var r = run(p); long now = System.currentTimeMillis();
        r.timed.values().removeIf(t -> t.until() <= now);
        boolean combat = inCombat(r, now);
        double hpFlat = 0, hpPct = 0, speed = 0, knock = 0, jump = 0, step = 0, maxMana = 0, haste = 0, regen = 1;
        if (has(p, "sturdy_vest")) hpFlat += 2;
        if (has(p, "knight_breastplate_shard")) hpFlat += 4;
        if (has(p, "giant_heart")) { hpPct += 0.30; speed -= 0.05; }
        if (has(p, "glass_heart")) hpPct -= 0.20;
        if (has(p, "time_mortgage_deed")) { hpPct -= 0.50; haste += 30; }
        if (has(p, "light_shoes")) speed += 0.03;
        if (has(p, "sprint_charm") && !combat) speed += 0.20;
        if (has(p, "sturdy_belt")) knock += 0.10;
        if (has(p, "frog_crown")) jump += 0.08;          // ~ +0.5 block jump height
        if (has(p, "hiking_stick") && !combat) step += 0.4; // 0.6 -> 1.0: walks up one block
        if (has(p, "small_mana_stone")) maxMana += 20;
        if (has(p, "mana_necklace")) maxMana += 50;
        if (has(p, "practice_wand")) haste += 3;
        if (has(p, "bargain_contract")) regen *= 2;
        if (has(p, "mana_drop_earring") && r.manaDropUntil > now) regen *= 1.2;
        if (r.comebackUntil > now) speed += 0.30;
        for (Timed t : r.timed.values()) { speed += t.speed(); haste += t.haste(); }

        modifier(p.getAttribute(Attribute.MAX_HEALTH), hpFlatKey, hpFlat, AttributeModifier.Operation.ADD_NUMBER);
        modifier(p.getAttribute(Attribute.MAX_HEALTH), hpPctKey, hpPct, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        var health = p.getAttribute(Attribute.MAX_HEALTH);
        if (health != null && p.getHealth() > health.getValue()) p.setHealth(health.getValue());
        modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, speed, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        modifier(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE), knockKey, knock, AttributeModifier.Operation.ADD_NUMBER);
        modifier(p.getAttribute(Attribute.JUMP_STRENGTH), jumpKey, jump, AttributeModifier.Operation.ADD_NUMBER);
        modifier(p.getAttribute(Attribute.STEP_HEIGHT), stepKey, step, AttributeModifier.Operation.ADD_NUMBER);
        if (has(p, "highland_telescope")) p.addScoreboardTag(ANCHOR_TAG); else p.removeScoreboardTag(ANCHOR_TAG); // 닻 장식

        UUID id = p.getUniqueId();
        var snap = mana.snapshot(id).orElse(null);
        if (snap == null) return;
        if (has(p, "bargain_contract")) {                       // max mana -50% of everything else
            double others = snap.maximum() - (r.manaApplied ? r.ownMax : 0);
            maxMana -= (others + maxMana) * 0.5;
        }
        if (has(p, "leisure_clock")) {                          // haste does nothing: cancel all other haste
            double others = snap.haste() - (r.manaApplied ? r.ownHaste : 0);
            haste = -others;
        }
        maxMana = Math.round(maxMana * 100) / 100.0;
        if (!r.manaApplied || maxMana != r.ownMax) { if (maxMana == 0) mana.removeModifier(id, MOD); else mana.setModifier(id, MOD, maxMana, 0); r.ownMax = maxMana; }
        if (!r.manaApplied || haste != r.ownHaste) { mana.setHasteModifier(id, MOD, haste); r.ownHaste = haste; }
        if (!r.manaApplied || regen != r.ownRegen) { mana.setRegenerationMultiplier(id, MOD, regen); r.ownRegen = regen; }
        r.manaApplied = true;
    }

    private static void modifier(AttributeInstance a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        if (a == null) return;
        var old = a.getModifier(key);
        if (old != null && old.getAmount() == amount && old.getOperation() == op) return;
        if (old != null) a.removeModifier(key);
        if (amount != 0) a.addTransientModifier(new AttributeModifier(key, amount, op));
    }

    private void clear(Player p) {
        p.removeScoreboardTag(ANCHOR_TAG);
        for (var pair : List.of(Map.entry(Attribute.MAX_HEALTH, hpFlatKey), Map.entry(Attribute.MAX_HEALTH, hpPctKey), Map.entry(Attribute.MOVEMENT_SPEED, speedKey),
                Map.entry(Attribute.KNOCKBACK_RESISTANCE, knockKey), Map.entry(Attribute.JUMP_STRENGTH, jumpKey), Map.entry(Attribute.STEP_HEIGHT, stepKey))) {
            var a = p.getAttribute(pair.getKey());
            if (a != null && a.getModifier(pair.getValue()) != null) a.removeModifier(pair.getValue());
        }
    }

    // ------------------------------------------------------------------ ticks

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            worn.put(p.getUniqueId(), wornNow(p));
            var r = run(p);
            if (r.inCombat && !inCombat(r, now)) { r.inCombat = false; combatEnded(p); }
            if (has(p, "mana_drop_earring")) mana.snapshot(p.getUniqueId()).ifPresent(s -> {
                if (s.maximum() > 0 && s.current() <= s.maximum() * 0.10 && ready(p, "mana_drop_earring", 60_000)) r.manaDropUntil = now + 3_000;
            });
            if (has(p, "tactics_map")) {
                var mates = party.onlineMates(p);
                if (!mates.isEmpty()) {
                    double bonus = 20.0 * mates.size();
                    timedQuiet(p, "tactics_map", 1_600, 0, bonus);
                    for (Player m : mates) { timedQuiet(m, "tactics_map", 1_600, 0, bonus); apply(m); }
                }
            }
            if (has(p, "love_letter") && social != null) r.lovers = Math.min(20, social.maxedCount(p.getUniqueId()));
            life.second(p, now);
            broom.second(p, now);
            rules.second(p, now);
            apply(p);
        }
        orbs.values().removeIf(o -> o.until() < now);
    }

    private void timedQuiet(Player p, String source, long ms, double speed, double haste) {
        run(p).timed.put(source, new Timed(System.currentTimeMillis() + ms, speed, haste));
    }

    private void fastTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!wearsAny(p)) continue;
            life.fast(p);
            actions.fast(p);
            comfort.fast(p);
        }
    }

    // ------------------------------------------------------------------ combat

    private void combat(Player p) {
        var r = run(p); long now = System.currentTimeMillis();
        boolean was = inCombat(r, now);
        r.combatAt = now; r.inCombat = true;
        if (!was) {
            if (has(p, "war_drum")) timed(p, "war_drum", 10_000, 0, 10);
            if (has(p, "sprint_charm") || has(p, "hiking_stick")) apply(p);
        }
    }

    private void combatEnded(Player p) {
        if (has(p, "breather_whistle")) p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0, true, true));
        if (has(p, "sprint_charm") || has(p, "hiking_stick")) apply(p);
    }

    static Entity source(Entity damager) {
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) return shooter;
        return damager;
    }
    static boolean hostile(Entity e) { return e instanceof Enemy || e instanceof Player; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combatHit(EntityDamageByEntityEvent e) {
        Entity from = source(e.getDamager());
        if (e.getEntity() instanceof Player victim && hostile(from) && !from.equals(victim)) combat(victim);
        if (from instanceof Player attacker && hostile(e.getEntity()) && !e.getEntity().equals(attacker)) combat(attacker);
    }

    // ------------------------------------------------------------------ damage taken

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        run(p).hurtAt = System.currentTimeMillis();
        if (!wearsAny(p)) return;
        double mult = 1;
        var cause = e.getCause();
        if (has(p, "thick_gloves") && (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) mult -= 0.03;
        if (has(p, "leather_bracer") && cause == EntityDamageEvent.DamageCause.PROJECTILE) mult -= 0.04;
        if (has(p, "balance_scale")) mult += 0.10;               // 굳센 심장: CC -50%, 대신 받는 피해 +10%
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (has(p, "cushion_cotton") && max != null && p.getHealth() >= max.getValue() - 0.01) mult -= 0.30;
        if (has(p, "melee_bell") && p.getNearbyEntities(10, 10, 10).stream().filter(x -> x instanceof Enemy).limit(3).count() >= 3) mult -= 0.08;
        if (has(p, "party_handkerchief") && party.onlineMates(p).stream().anyMatch(m -> m.getWorld() == p.getWorld() && m.getLocation().distanceSquared(p.getLocation()) <= 25)) mult -= 0.02;
        if (has(p, "love_letter")) mult -= 0.01 * run(p).lovers;
        if (mult != 1) e.setDamage(e.getDamage() * Math.max(0, mult));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurtAfter(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !wearsAny(p)) return;
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue(), left = p.getHealth() - e.getFinalDamage();
        if (left <= 0) return;
        if (has(p, "emergency_herb_pouch") && left <= cap * 0.10 && ready(p, "emergency_herb_pouch", 120_000)) {
            timed(p, "emergency_herb_pouch", 3_000, 1.0, 0);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_GRASS_BREAK, 1f, 1.4f);
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 12, .4, .5, .4, 0);
        }
        if (has(p, "smoke_button") && left < cap * 0.05 && ready(p, "smoke_button", 60_000)) {
            p.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, p.getLocation().add(0, 1, 0), 30, .6, .6, .6, .02);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1f, 1.2f);
            bubble(p, "후퇴!", 50);
        }
        if (has(p, "bubblewrap_cape")) {
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1f, 1.6f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1f, 1.9f), 2);
        }
    }

    // ------------------------------------------------------------------ casts (ManaBridge after OK)

    void cast(Player p, String spell, double cost) {
        var r = run(p); long now = System.currentTimeMillis();
        r.castIndex++;
        r.castSneak = p.isSneaking(); r.castSprint = p.isSprinting();
        r.castCalm = has(p, "calm_lake_orb") && (r.hurtAt < 0 || now - r.hurtAt >= 5_000);
        r.castCheer = r.cheerArmed; r.cheerArmed = false;
        if (!wearsAny(p)) return;
        if (has(p, "mana_cycle_ring") && cost > 0) {
            r.manaSpent += cost;
            while (r.manaSpent >= 200) { r.manaSpent -= 200; heal(p, 4); p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 2, .3, .2, .3, 0); }
        }
        if (has(p, "metronome")) {
            r.castTimes[0] = r.castTimes[1]; r.castTimes[1] = r.castTimes[2]; r.castTimes[2] = now;
            if (r.castTimes[0] > 0) {
                long a = r.castTimes[1] - r.castTimes[0], b = r.castTimes[2] - r.castTimes[1];
                if (a >= 300 && Math.abs(a - b) <= 150) {
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.6f);
                    p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, .5f, 2f);
                    Arrays.fill(r.castTimes, -1);
                }
            }
        }
        rules.cast(p, spell, now);
        if (r.chantArmed && cost > 0) {
            r.chantArmed = false;
            if (mana.snapshot(p.getUniqueId()).isPresent()) mana.add(p.getUniqueId(), cost);
            notice(p, "chant", "주문 촛대", "마나를 쓰지 않았어요");
        }
    }

    static void heal(Player p, double amount) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (p.isDead() || amount <= 0) return;
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + amount));
    }

    // ------------------------------------------------------------------ magic (MagicMythic events)

    private double magicMultiplier(Player p, LivingEntity t, String element, String reaction, UUID markCaster) {
        var r = run(p); long now = System.currentTimeMillis();
        double add = 0;
        boolean spell = !element.isEmpty();
        if (spell) {
            if (r.elementCast != r.castIndex) { r.prevElement = r.curElement; r.curElement = element; r.elementCast = r.castIndex; }
            r.lastElement = element;
        }
        if (spell && has(p, "element_ring_" + element)) add += 0.04;
        double dist = p.getLocation().distance(t.getLocation());
        if (has(p, "close_combat_charm") && dist <= 5) add += 0.08;
        if (has(p, "sniper_monocle") && dist > 15) add += 0.08;
        if (has(p, "glass_heart")) add += 0.12;
        if (has(p, "leisure_clock")) add += 0.50;
        if (has(p, "calm_lake_orb") && r.castCalm) add += 0.20;
        var tm = t.getAttribute(Attribute.MAX_HEALTH);
        if (has(p, "final_blow_quill") && tm != null && t.getHealth() <= tm.getValue() * 0.20) add += 0.30;
        if (has(p, "hermit_lantern")) add += party.inParty(p.getUniqueId()) ? -0.20 : 0.20;
        var pm = p.getAttribute(Attribute.MAX_HEALTH);
        if (has(p, "tilted_scales") && pm != null) add += 0.60 * Math.clamp(1 - p.getHealth() / pm.getValue(), 0, 1);
        if (has(p, "philosopher_stone")) add += Math.min(0.40, p.getLevel() * 0.005);
        if (has(p, "focus_headband") && Boolean.TRUE.equals(hooks.api("isControlled", new Class<?>[]{LivingEntity.class}, t))) add += 0.08; // 제압의 머리띠
        if (has(p, "running_caster_badge") && r.castSprint) add += 0.06;
        if (has(p, "hunter_mark_ring")) {
            if (r.huntCast != r.castIndex) {
                if (t.getUniqueId().equals(r.huntTarget)) r.huntStacks = Math.min(5, r.huntStacks + 1);
                else { r.huntTarget = t.getUniqueId(); r.huntStacks = 0; }
                r.huntCast = r.castIndex;
            }
            if (t.getUniqueId().equals(r.huntTarget)) add += 0.03 * r.huntStacks;
        }
        if (has(p, "double_mark_lens") && p.getUniqueId().equals(markCaster)) add += 0.05;
        if (has(p, "element_cycle_bracelet") && spell && r.prevElement != null) add += r.prevElement.equals(element) ? -0.20 : 0.20;
        if (has(p, "cheer_flag") && r.castCheer) add += 0.10;
        double mult = Math.max(0, 1 + add);
        if (r.comebackUntil > now) mult *= 2;
        return mult;
    }

    private void reaction(Player p, LivingEntity target, String reaction, boolean control, String trigger, Event e) {
        var r = run(p); long now = System.currentTimeMillis();
        if (has(p, "element_chalk")) { hooks.call(e, "setEffectCancelled", true); return; }
        if (has(p, "resonance_earring")) heal(p, 1);
        if (has(p, "ember_pouch")) timed(p, "ember_pouch", 2_000, 0.05, 0);
        if (has(p, "chain_glove") && reaction.equals("shock")) hooks.call(e, "setBounceBonus", 1);
        if (has(p, "sand_pouch") && reaction.equals("sandstorm")) hooks.call(e, "setRadiusBonus", 1.0);
        if (has(p, "rainbow_watering_can") && reaction.equals("rainbow")) hooks.call(e, "setHealBonus", 1.0);
        if (has(p, "reaction_master_seal") && control) hooks.call(e, "multiplyControl", 1.2);   // 반응 CC +20%
        if (has(p, "slow_inkwell") && control) hooks.call(e, "multiplyControl", 0.7);           // 표식 2배, 대신 반응 CC -30%
        if (has(p, "reaction_catalyst") && control) hooks.call(e, "setNormalControlBonusTicks", 20); // 일반 CC(3초) +1초
        if (has(p, "cheer_megaphone"))
            for (Player m : party.onlineMates(p)) if (m.getWorld() == p.getWorld() && m.getLocation().distanceSquared(p.getLocation()) <= 64) timed(m, "cheer_megaphone", 5_000, 0, 3);
        if (has(p, "element_collector_note")) {
            r.reactions.addLast(new long[]{reaction.hashCode(), now});
            while (!r.reactions.isEmpty() && now - r.reactions.peekFirst()[1] > 30_000) r.reactions.removeFirst();
            if (r.reactions.stream().mapToLong(x -> x[0]).distinct().count() >= 5) {
                r.reactions.clear();
                timed(p, "element_collector_note", 10_000, 0, 50);
                notice(p, "element_note", "원소 수집 노트", "10초간 스킬 가속 +50");
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
            }
        }
        if (has(p, "reaction_chain_orb")) orbs.put(target.getUniqueId(), new Orb(p.getUniqueId(), trigger, now + 3_000));
        rules.reaction(p, control, e);
    }

    /**
     * Duration multiplier for a status landing on `p` (MagicStatusEvent, any source). 0 = immune.
     * Reductions multiply: 작은 방패 배지 all -15%, 굳센 심장 all -50%, 벌레 퇴치 향 독·화상 -30%,
     * 그을린 앞치마 둔화·중력 -30%, 폭풍우 피뢰침 기절·무장해제 -30%, 닻 장식 고정 -50%, 잠깨기 종 수면 면역.
     */
    double controlTaken(Player p, String status) {
        if (has(p, "twilight_cape") && status.equals("sleep")) return 0;
        double m = 1;
        if (has(p, "small_shield_badge")) m *= 0.85;
        if (has(p, "balance_scale")) m *= 0.5;
        switch (status) {
            case "poison", "burn" -> { if (has(p, "bug_repellent_incense")) m *= 0.7; }
            case "slow", "gravity" -> { if (has(p, "scorched_apron")) m *= 0.7; }
            case "stun", "disarm" -> { if (has(p, "storm_lightning_rod")) m *= 0.7; }
            case "movement_lock" -> { if (has(p, "highland_telescope")) m *= 0.5; }
            default -> { }
        }
        return m;
    }

    private int markTicks(Player p, int ticks) {
        if (has(p, "catalyst_powder")) ticks += 10;
        if (has(p, "sticky_ink")) ticks += 20;
        if (has(p, "slow_inkwell")) ticks *= 2;
        return ticks;
    }

    private double healAmount(Player caster, Player ally, double amount) {
        if (has(caster, "friendship_bracelet") && party.sameParty(caster.getUniqueId(), ally.getUniqueId())) amount *= 1.05;
        return amount;
    }

    /** Reflection bridge to ChAcademiaMagicMythic (soft dependency). */
    final class MagicHooks implements Listener {
        private Plugin mythic;
        private final Map<String, Method> methods = new HashMap<>();

        void connect() {
            var mm = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
            if (mm == null || !mm.isEnabled() || mm == mythic) return;
            ClassLoader cl = mm.getClass().getClassLoader();
            try {
                register(cl, "school.chacademia.magicmythic.MagicDamageEvent", this::damage);
                register(cl, "school.chacademia.magicmythic.MagicReactionEvent", this::reaction);
                register(cl, "school.chacademia.magicmythic.MagicMarkEvent", this::mark);
                register(cl, "school.chacademia.magicmythic.MagicHealEvent", this::heal);
                mythic = mm;
                plugin.getLogger().info("아티팩트 마법 효과 연결: " + mm.getName());
                try { register(cl, "school.chacademia.magicmythic.MagicStatusEvent", this::status); }
                catch (ClassNotFoundException | LinkageError old) { plugin.getLogger().warning("아티팩트 CC 감소 연결 안 됨 (MagicMythic에 MagicStatusEvent 없음): 이전 버전"); }
            } catch (ReflectiveOperationException | LinkageError err) {
                plugin.getLogger().warning("아티팩트 마법 효과 연결 실패 (MagicMythic 버전 확인): " + err);
            }
        }
        void disconnect(Plugin p) { if (p == mythic) { mythic = null; methods.clear(); } }

        @SuppressWarnings("unchecked")
        private void register(ClassLoader cl, String name, Consumer<Event> handler) throws ClassNotFoundException {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(name, true, cl);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (l, ev) -> {
                if (!type.isInstance(ev)) return;
                try { handler.accept(ev); }
                catch (RuntimeException err) { plugin.getLogger().warning("아티팩트 마법 효과 오류: " + err); }
            }, plugin, false);
        }

        Object get(Object o, String name) {
            try { return method(o.getClass(), name).invoke(o); }
            catch (ReflectiveOperationException err) { throw new IllegalStateException(name, err); }
        }
        void call(Object o, String name, Object arg) {
            try {
                for (Method m : o.getClass().getMethods())
                    if (m.getName().equals(name) && m.getParameterCount() == 1) { m.invoke(o, arg); return; }
            } catch (ReflectiveOperationException err) { throw new IllegalStateException(name, err); }
        }
        private Method method(Class<?> c, String name, Class<?>... args) throws NoSuchMethodException {
            String key = c.getName() + "#" + name + "/" + args.length;
            Method m = methods.get(key);
            if (m == null) { m = c.getMethod(name, args); methods.put(key, m); }
            return m;
        }
        /** Calls a public method of the MagicMythic plugin (artifact API). null when unavailable. */
        Object api(String name, Class<?>[] types, Object... args) {
            if (mythic == null || !mythic.isEnabled()) return null;
            try { return method(mythic.getClass(), name, types).invoke(mythic, args); }
            catch (ReflectiveOperationException err) { plugin.getLogger().warning("MagicMythic " + name + " 실패: " + err); return null; }
        }
        boolean connected() { return mythic != null && mythic.isEnabled(); }

        private void damage(Event e) {
            if (!(get(e, "getCaster") instanceof Player p) || !p.isOnline()) return;
            var r = runs.get(p.getUniqueId());
            if (!wearsAny(p) && (r == null || r.comebackUntil <= System.currentTimeMillis())) return;
            var t = (LivingEntity) get(e, "getTarget");
            String reaction = (String) get(e, "getReaction");
            double mult = magicMultiplier(p, t, (String) get(e, "getElement"), reaction, (UUID) get(e, "getMarkCaster")) * rules.damage(p, e, reaction);
            mult = CombatSpec.artifactMultiplier(mult, t instanceof Player); // 전투 스펙 2026-10-26: PvP 에서는 아티팩트 피해 보정 합 +20% 까지
            if (mult != 1) call(e, "setDamage", (double) get(e, "getDamage") * mult);
        }
        private void reaction(Event e) {
            if (!(get(e, "getCaster") instanceof Player p) || !wearsAny(p)) return;
            ArtifactEffects.this.reaction(p, (LivingEntity) get(e, "getTarget"), (String) get(e, "getReaction"), (boolean) get(e, "isControl"), (String) get(e, "getTriggerElement"), e);
        }
        private void mark(Event e) {
            if (!(get(e, "getCaster") instanceof Player p) || !wearsAny(p)) return;
            int ticks = (int) get(e, "getTicks"), next = markTicks(p, ticks);
            if (next != ticks) call(e, "setTicks", next);
        }
        private void status(Event e) {
            if (!(get(e, "getTarget") instanceof Player p) || !wearsAny(p)) return;
            int ticks = (int) get(e, "getTicks");
            double mult = controlTaken(p, (String) get(e, "getStatus"));
            if (mult <= 0) { call(e, "setCancelled", true); return; }
            if (mult != 1) call(e, "setTicks", Math.max(1, (int) Math.round(ticks * mult)));
        }
        private void heal(Event e) {
            if (!(get(e, "getCaster") instanceof Player p) || !wearsAny(p)) return;
            double amount = (double) get(e, "getAmount"), next = rules.heal(p, (String) get(e, "getReaction"), healAmount(p, (Player) get(e, "getTarget"), amount));
            if (next != amount) call(e, "setAmount", next);
        }
    }

    MagicHooks magic() { return hooks; }

    @EventHandler public void pluginEnabled(PluginEnableEvent e) { if (e.getPlugin().getName().equals("ChAcademiaMagicMythic")) hooks.connect(); }
    @EventHandler public void pluginDisabled(PluginDisableEvent e) { hooks.disconnect(e.getPlugin()); }

    // ------------------------------------------------------------------ deaths

    @EventHandler(priority = EventPriority.HIGH)
    public void death(EntityDeathEvent e) {
        var dead = e.getEntity();
        Player killer = dead.getKiller();
        long now = System.currentTimeMillis();
        var orb = orbs.remove(dead.getUniqueId());
        if (orb != null && orb.until() >= now) {
            var caster = Bukkit.getPlayer(orb.caster());
            if (caster != null && caster.getWorld() == dead.getWorld())
                hooks.api("spreadMark", new Class<?>[]{Player.class, Location.class, LivingEntity.class, String.class, double.class},
                        caster, dead.getLocation(), dead, orb.element(), 6.0);
        }
        if (killer == null || dead instanceof Player) return;
        // 음유시인 류트: 같은 서버의 파티원(나 포함)이 처치할 때마다 음표
        for (UUID id : party.members(killer.getUniqueId())) {
            Player bard = Bukkit.getPlayer(id);
            if (bard == null || !has(bard, "bard_lute")) continue;
            var r = run(bard);
            if (++r.notes < 10) { bard.sendActionBar(Component.text("♪ 음표 " + r.notes + " / 10", NamedTextColor.LIGHT_PURPLE)); continue; }
            r.notes = 0;
            var group = new ArrayList<Player>(party.onlineMates(bard)); group.add(bard);
            for (Player m : group) {
                timed(m, "bard_lute", 10_000, 0, 100);
                notice(m, "bard_lute", "음유시인의 연주", "10초간 스킬 가속 +100");
                m.playSound(m.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE, 1f, 1.5f);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerDied(PlayerDeathEvent e) {
        Player dead = e.getEntity(); long now = System.currentTimeMillis();
        run(dead).deathAt = now;
        for (Player m : party.onlineMates(dead)) {
            if (has(m, "cheer_flag")) { run(m).cheerArmed = true; notice(m, "cheer_flag", "응원 깃발", "다음 마법 피해 +10%"); }
            if (has(m, "comeback_medal") && !m.isDead() && lastStanding(m, now) && ready(m, "comeback_medal", 60_000)) {
                run(m).comebackUntil = now + 10_000;
                apply(m);
                Bukkit.getScheduler().runTaskLater(plugin, () -> apply(m), 201);
                notice(m, "comeback_medal", "역전의 메달", "10초간 피해 2배!");
                m.getWorld().playSound(m.getLocation(), Sound.ITEM_TOTEM_USE, .6f, 1.4f);
            }
        }
    }

    /** True when every other party member near `p` (same world, 64 blocks) died in the last 15 s. */
    private boolean lastStanding(Player p, long now) {
        int others = 0;
        for (Player m : party.onlineMates(p)) {
            if (m.getWorld() != p.getWorld() || m.getLocation().distanceSquared(p.getLocation()) > 64 * 64) continue;
            others++;
            var r = runs.get(m.getUniqueId());
            boolean down = m.isDead() || (r != null && r.deathAt >= 0 && now - r.deathAt <= 15_000);
            if (!down) return false;
        }
        return others > 0;
    }

    // ------------------------------------------------------------------ NPC affinity (NpcSocialService.add)

    int affinity(UUID player, int amount) {
        Player p = Bukkit.getPlayer(player);
        if (p == null) return amount;
        if (has(p, "village_friend")) amount = amount < 0 ? 0 : amount * 2;
        if (has(p, "chatty_feather") && amount > 0) {
            double v = amount * 1.05; int whole = (int) v;
            amount = whole + (random.nextDouble() < v - whole ? 1 : 0);
        }
        return amount;
    }

    // ------------------------------------------------------------------ helpers

    /** Short speech bubble above the head (TextDisplay riding the player). */
    void bubble(Player p, String text, int ticks) {
        var d = p.getWorld().spawn(p.getLocation().add(0, 2.2, 0), TextDisplay.class, t -> {
            t.text(Component.text(text, NamedTextColor.WHITE));
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setSeeThrough(false);
            t.setTransformation(new Transformation(new Vector3f(0, 0.45f, 0), new org.joml.Quaternionf(), new Vector3f(1, 1, 1), new org.joml.Quaternionf()));
        });
        p.addPassenger(d);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, ticks);
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(e.getPlayer()), 20);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clear(e.getPlayer());
        worn.remove(id); runs.remove(id);
        actions.quit(id); life.quit(id); comfort.quit(id); broom.quit(id); rules.quit(id);
    }

    void close() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
        rules.close(); motions.close();
        shapeshift.close(); shadows.close(); companions.close();   // 변신 원상 복구 · 그림자 제거
    }
}
