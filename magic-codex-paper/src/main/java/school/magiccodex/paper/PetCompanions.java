package school.magiccodex.paper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.PetCompanionProtocol;
import school.magiccodex.protocol.PetCompanionProtocol.Pet;
import school.magiccodex.protocol.PetCompanionProtocol.Pets;
import school.magiccodex.protocol.PetProtocol;

/**
 * 동행 펫 (오너 2026-10-26, 메모 #81). pet-companions.yml 에 있는 펫은 MCPets 몹을 소환하지 않는다.
 * 서버는 "누가 어떤 펫을 꺼냈는지"만 기억하고 같은 세계 사람들에게 {@link PetCompanionProtocol} 로 알린다 (클라가 그림 = 서버 엔티티 0).
 *
 * 한 번에 한 마리. 꺼낸 펫은 codex_player_state.artifact_runtime 앞의 "cpet=ID;" 토큰으로 학교·야생 이동에도 유지된다.
 * 장착 효과: 최대 체력(별 등급) + 2·3성 passive 능력(항상). 전투 중 자동 발동(2성 60초·3성 40초): 펫 스킬(CHA_PET_*) 또는 combat 능력.
 * 큐디치·결투(blocked-matches) 중에는 꺼낼 수 없고 경기가 시작되면 넣는다. 그 밖의 경기에서는 체력만 남긴다.
 *
 * 부담: 매 틱 일 없음. 1초 주기 작업은 펫을 꺼낸 사람만 본다. 전송은 꺼내기·넣기·접속·퇴장·세계 이동·리스폰·발동 때만.
 * 메인 스레드 전용.
 */
final class PetCompanions implements Listener, AutoCloseable {
    static final String FILE = "pet-companions.yml";
    /** ManaService 수정자 키. */
    private static final String MANA_KEY = "magiccodex:pet";
    /** 펫이 거는 무한 포션 (파티클 없음 + ambient = 펫 표시). */
    private static final List<PotionEffectType> PASSIVE_POTIONS = List.of(PotionEffectType.HASTE, PotionEffectType.JUMP_BOOST,
            PotionEffectType.DOLPHINS_GRACE, PotionEffectType.NIGHT_VISION, PotionEffectType.WATER_BREATHING, PotionEffectType.FIRE_RESISTANCE);
    /** cleanse 가 지우는 해로운 효과. */
    private static final List<PotionEffectType> HARMFUL = List.of(PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA,
            PotionEffectType.BLINDNESS, PotionEffectType.HUNGER, PotionEffectType.WEAKNESS, PotionEffectType.POISON, PotionEffectType.WITHER,
            PotionEffectType.LEVITATION, PotionEffectType.DARKNESS);

    record Def(String id, String name, int stars, int motion, float scale, double health, List<String> skills,
               boolean passive, boolean combat, Map<String, Double> effects, int seconds, String info,
               String permission, String mythic, byte[] icon) {}
    private record Settings(double[] points, double maxHealth, double curve, int[] interval, long combatMs, double range, Set<String> blocked) {
        long intervalMs(int stars) { return Math.max(5, interval[Math.clamp(stars, 1, 3)]) * 1000L; }
    }
    /** 꺼낸 펫 하나의 실행 상태 (저장하지 않음, 펫 ID만 저장). */
    private static final class Owner {
        final String pet; long nextAt; long combatAt = -1; LivingEntity opponent; boolean suppressed;
        long buffUntil; double intBonus; long absorbUntil;
        Owner(String pet) { this.pet = pet; }
    }
    /** 이 플레이어에게 지금 걸어 둔 값 (바뀔 때만 다시 건다). */
    private static final class Applied { double maxMana, regen = 1; boolean immune; }

    private final MagicCodexBridge plugin;
    private final PetBridge bridge;
    private final ManaService mana;
    private final NamespacedKey healthKey, speedKey, knockKey, fallKey, combatSpeedKey, combatKnockKey, absorbKey;
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final Map<UUID, Applied> applied = new HashMap<>();
    /** 마지막 자동 발동 시각: 넣었다 다시 꺼내 쿨을 건너뛰지 못하게 (퇴장 때 지움). */
    private final Map<UUID, Long> lastFire = new HashMap<>();
    private final Set<String> skillFailures = new HashSet<>();
    private final BukkitTask task;
    private Map<String, Def> defs = Map.of();
    private Settings settings;
    private final BossPetSkills boss;   // [기능:boss-pet-skills] MCPets 보스펫 스킬 · 소환 하수인

    PetCompanions(MagicCodexBridge plugin, PetBridge bridge, ManaService mana) throws Exception {
        this.plugin = plugin; this.bridge = bridge; this.mana = mana;
        healthKey = key("pet_health"); speedKey = key("pet_speed"); knockKey = key("pet_knockback"); fallKey = key("pet_fall");
        combatSpeedKey = key("pet_combat_speed"); combatKnockKey = key("pet_combat_knockback"); absorbKey = key("pet_absorb");
        boss = new BossPetSkills(plugin, this);   // [기능:boss-pet-skills] reload 보다 먼저
        reload();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PetCompanionProtocol.CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var stats = plugin.stats();
        if (stats != null) { stats.register("pets", this::apply); stats.afterArrival("pets", this::arrived); }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
    }
    private NamespacedKey key(String name) { return new NamespacedKey(plugin, name); }
    private static long now() { return System.currentTimeMillis(); }

    // ------------------------------------------------------------------ 설정

    /** pet-companions.yml 을 다시 읽는다 (실패하면 예외, 기존 설정 유지). 꺼낸 펫이 설정에서 빠졌으면 넣는다. */
    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        var y = new YamlConfiguration();
        y.load(file);
        double[] health = new double[4], points = new double[4]; int[] interval = new int[4];
        for (int s = 1; s <= 3; s++) {
            points[s] = Math.clamp(y.getDouble("collection-health.points." + s, s == 1 ? 1 : s == 2 ? 3 : 6), 0, 1000);
            interval[s] = Math.clamp(y.getInt("interval-seconds." + s, s == 3 ? 40 : 60), 5, 3600);
        }
        var blocked = new HashSet<String>();
        for (String m : y.getStringList("blocked-matches")) blocked.add(m.toLowerCase(Locale.ROOT));
        var next = new Settings(points, Math.clamp(y.getDouble("collection-health.max", 20), 0, 200),
                Math.clamp(y.getDouble("collection-health.curve", 0.38), 0.05, 3), interval, Math.clamp(y.getLong("combat-seconds", 8), 1, 120) * 1000L,
                Math.clamp(y.getDouble("skill-range", 24), 2, 64), Set.copyOf(blocked));
        var pets = y.getConfigurationSection("pets");
        var out = new LinkedHashMap<String, Def>();
        if (pets != null) for (String id : pets.getKeys(false)) {
            var s = pets.getConfigurationSection(id);
            if (s == null || !PetProtocol.validId(id)) continue;
            int stars = Math.clamp(s.getInt("stars", 1), 1, 3);
            String name = s.getString("name", id); if (name.length() > 96) name = name.substring(0, 96);
            float scale = (float) Math.clamp(s.getDouble("size", 0.55), 0.05, 2);
            var skills = new ArrayList<String>();
            for (String k : s.getStringList("skills")) if (k.matches("[A-Za-z0-9_.-]{1,128}")) skills.add(k);
            var special = s.getConfigurationSection("special");
            String kind = special == null ? "" : special.getString("kind", "").toLowerCase(Locale.ROOT);
            var effects = new LinkedHashMap<String, Double>();
            var e = special == null ? null : special.getConfigurationSection("effects");
            if (e != null) for (String k : e.getKeys(false)) { double v = e.getDouble(k); if (Double.isFinite(v)) effects.put(k.toLowerCase(Locale.ROOT), v); }
            String info = s.getString("info", "").replace("\r", "");
            if (info.length() > PetProtocol.MAX_INFO) info = info.substring(0, PetProtocol.MAX_INFO);
            boolean special2 = stars >= 2;   // 1성은 체력만
            // 보유 권한·변신 가면 몹·펫 창 아이콘도 이 파일에서 (MCPets 에 의존하지 않음, 오너 2026-10-26)
            String permission = s.getString("permission", "chacademia.pet." + id.toLowerCase(Locale.ROOT)).toLowerCase(Locale.ROOT);
            if (!permission.matches("[a-z0-9_.-]{1,100}")) throw new IllegalArgumentException("pet-companions.yml " + id + ": permission 형식 오류");
            String mythic = s.getString("mythic", "");
            if (!mythic.matches("[A-Za-z0-9_.-]{1,128}")) mythic = null;
            out.put(id, new Def(id, name, stars, CompanionMath.motion(s.getString("motion")), scale,
                    0, special2 ? List.copyOf(skills) : List.of(),
                    special2 && kind.equals("passive"), special2 && kind.equals("combat"), Map.copyOf(effects),
                    Math.clamp(special == null ? 0 : special.getInt("seconds", 0), 0, 600), info, permission, mythic,
                    icon(s.getConfigurationSection("icon"), name)));
        }
        settings = next; defs = Collections.unmodifiableMap(out);
        boss.load(y);   // [기능:boss-pet-skills] mcpets-skills
        for (UUID id : List.copyOf(owners.keySet())) {
            var p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!defs.containsKey(owners.get(id).pet)) unequip(p, "펫 설정이 바뀌어 펫을 넣었어요");
            else { var o = owners.get(id); clearTimed(p, o); apply(p); }
        }
        for (var w : Bukkit.getWorlds()) if (!w.getPlayers().isEmpty()) broadcast(w, null, null);
    }

    /** icon: {material, name, lore:[...]} → 펫 창 아이콘 바이트 (너무 크면 빈 배열). */
    private static byte[] icon(org.bukkit.configuration.ConfigurationSection s, String fallbackName) {
        if (s == null) return new byte[0];
        var material = Material.matchMaterial(s.getString("material", ""));
        if (material == null || material.isAir() || !material.isItem()) return new byte[0];
        var item = new org.bukkit.inventory.ItemStack(material);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', s.getString("name", fallbackName)));
            var lore = new ArrayList<String>();
            for (String line : s.getStringList("lore")) lore.add(ChatColor.translateAlternateColorCodes('&', line));
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        byte[] raw = item.serializeAsBytes();
        return raw.length > PetProtocol.MAX_ICON ? new byte[0] : raw;
    }

    boolean companion(String id) { return defs.containsKey(id); }
    /** 펫 창·명령용: 설정 순서대로의 동행 펫 전부. */
    Collection<Def> all() { return defs.values(); }
    Def def(String id) { return defs.get(id); }
    String info(String id) { var d = defs.get(id); return d == null ? "" : d.info(); }
    int stars(String id) { var d = defs.get(id); return d == null ? 1 : d.stars(); }
    int count() { return defs.size(); }
    /** 꺼낸 동행 펫 ID (없으면 null). */
    String active(Player p) { var o = owners.get(p.getUniqueId()); return o == null ? null : o.pet; }

    // ------------------------------------------------------------------ 꺼내기 · 넣기

    /** 펫 창 소환 요청 (보유 권한은 PetBridge 가 이미 확인). 알림 문구를 돌려준다. */
    String equip(Player p, String id) {
        var d = defs.get(id);
        if (d == null) return "등록되지 않은 펫입니다.";
        if (!plugin.playerStateReady(p)) return "캐릭터 정보를 불러오는 중이에요. 잠시 후 다시 시도해 주세요.";
        if (match(p) == 2) return "경기 중에는 펫을 꺼낼 수 없어요";
        UUID uid = p.getUniqueId();
        var old = owners.get(uid);
        if (old != null && old.pet.equals(id)) return "이미 꺼낸 펫이에요.";
        if (old != null) clearTimed(p, old);
        boss.forget(uid);   // [기능:boss-pet-skills] 펫을 바꾸면 하수인은 사라짐
        var o = new Owner(id);
        // 꺼낸 직후엔 쿨이 찬 상태 (바로 발동 가능). 단, 넣었다 다시 꺼내 쿨을 건너뛰지는 못한다.
        Long last = lastFire.get(uid);
        o.nextAt = last == null ? 0 : last + settings.intervalMs(d.stars());
        owners.put(uid, o);
        bridge.despawnMcPets(p);   // 한 번에 한 마리 (바닐라 MCPets 펫이 나와 있으면 들여보냄)
        apply(p);
        plugin.markPlayerStateDirty(p);
        broadcast(p.getWorld(), null, null);
        return d.name() + " 펫을 꺼냈어요.";
    }

    /** 넣기. notice 가 있으면 알림 카드로 알린다. 꺼낸 펫이 없었으면 false. */
    boolean unequip(Player p, String notice) {
        var o = owners.remove(p.getUniqueId());
        boss.forget(p.getUniqueId());   // [기능:boss-pet-skills]
        if (o == null) return false;
        clearTimed(p, o);
        apply(p);
        plugin.markPlayerStateDirty(p);
        broadcast(p.getWorld(), null, null);
        if (notice != null) CodexCommands.notify(p, "system", "pet-companion", notice, 1500);
        return true;
    }

    // ------------------------------------------------------------------ 서버 이동 (공유 상태)

    /** artifact_runtime 앞에 붙일 토큰. */
    String runtimeToken(Player p) { var o = owners.get(p.getUniqueId()); return o == null ? "" : CompanionMath.token(o.pet); }
    /** 접속 상태 적용 (PlayerStateBridge.apply, ready 전). 효과·전송은 arrived 에서. */
    void restore(Player p, String pet) {
        UUID id = p.getUniqueId();
        var old = owners.remove(id);
        if (old != null) clearTimed(p, old);
        if (pet != null && defs.containsKey(pet)) { var o = new Owner(pet); o.nextAt = 0; owners.put(id, o); }
    }
    /** PlayerStats afterArrival: 보유·경기 확인 후 효과와 목록 전송. */
    private void arrived(Player p) {
        var o = owners.get(p.getUniqueId());
        if (o != null) {
            if (!bridge.mayUse(p, o.pet)) { unequip(p, "보유하지 않은 펫이라 넣었어요"); return; }
            if (match(p) == 2) { unequip(p, "경기 중이라 펫을 넣었어요"); return; }
        }
        cleanPotions(p, o == null ? null : defs.get(o.pet));
        apply(p);
        broadcast(p.getWorld(), null, null);
    }

    // ------------------------------------------------------------------ 장착 효과

    /** 펫 창 위쪽 한 줄 (오너 2026-10-26): "도감 체력 +8 · 점수 51 / 612". */
    String collectionSummary(Player p) {
        var cfg = settings;
        double[] score = bridge.collection(p, stars -> cfg.points()[Math.clamp(stars, 1, 3)]);
        int hp = CompanionMath.collectionHealth(score[0], score[1], cfg.maxHealth(), cfg.curve());
        return "도감 체력 +" + hp + " · 점수 " + Math.round(score[0]) + " / " + Math.round(score[1]);
    }

    /** PlayerStats 원천 "pets": 지금 상태대로 수정자를 맞춘다 (같은 값이면 아무것도 안 함). */
    void apply(Player p) {
        if (!p.isOnline()) return;
        UUID id = p.getUniqueId();
        var o = owners.get(id);
        var d = o == null ? null : defs.get(o.pet);
        // 도감 체력 (오너 2026-10-26): 꺼낸 펫과 무관하게, 교화해 보유한 펫 등급 점수가 많을수록 최대 체력 누적 증가
        var cfg = settings;
        double[] score = bridge.collection(p, stars -> cfg.points()[Math.clamp(stars, 1, 3)]);
        modifier(p.getAttribute(Attribute.MAX_HEALTH), healthKey, CompanionMath.collectionHealth(score[0], score[1], cfg.maxHealth(), cfg.curve()), AttributeModifier.Operation.ADD_NUMBER);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && p.getHealth() > max.getValue()) p.setHealth(max.getValue());
        if (d == null && !applied.containsKey(id)) return;   // 펫을 꺼낸 적 없는 사람: 나머지는 할 일 없음
        boolean special = d != null && d.passive() && !o.suppressed;
        Map<String, Double> e = special ? d.effects() : Map.of();
        modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, Math.clamp(e.getOrDefault("speed", 0d), 0, 2), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        modifier(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE), knockKey, Math.clamp(e.getOrDefault("knockback", 0d), 0, 1), AttributeModifier.Operation.ADD_NUMBER);
        modifier(p.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER), fallKey, CompanionMath.fall(e.getOrDefault("fall", 0d)), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        potions(p, e);
        var a = applied.computeIfAbsent(id, k -> new Applied());
        if (mana.snapshot(id).isPresent()) {
            double maxMana = Math.clamp(e.getOrDefault("max_mana", 0d), 0, 100_000), regen = CompanionMath.factor(e.getOrDefault("mana_regen", 0d));
            if (maxMana != a.maxMana) { if (maxMana == 0) mana.removeModifier(id, MANA_KEY); else mana.setModifier(id, MANA_KEY, maxMana, 0); a.maxMana = maxMana; }
            if (regen != a.regen) { mana.setRegenerationMultiplier(id, MANA_KEY, regen); a.regen = regen; }
        }
        boolean immune = e.getOrDefault("climate_immune", 0d) > 0;
        if (immune != a.immune) { a.immune = immune; var climate = plugin.climateService(); if (climate != null) climate.resample(p); }
        if (d == null && a.maxMana == 0 && a.regen == 1 && !a.immune) applied.remove(id);
    }

    /** ClimateService: 이 플레이어는 계절·온도 배율(좋은 것·나쁜 것 모두)을 받지 않는다. */
    boolean climateImmune(UUID id) { var a = applied.get(id); return a != null && a.immune; }
    /** 지능(마법 위력) 배율: combat 능력 intelligence 가 켜져 있는 동안. */
    double powerFactor(UUID id) {
        var o = owners.get(id);
        if (o == null || o.suppressed) return 1;
        double bonus = o.buffUntil > now() ? o.intBonus : 0;
        var d = defs.get(o.pet);
        if (d != null && d.passive()) bonus += d.effects().getOrDefault("intelligence", 0d);
        return CompanionMath.factor(bonus);
    }
    /** 낚시 희귀 등급 배율 (FishingBridge). */
    double fishingFactor(Player p) {
        var o = owners.get(p.getUniqueId());
        var d = o == null || o.suppressed ? null : defs.get(o.pet);
        return d == null || !d.passive() ? 1 : CompanionMath.factor(d.effects().getOrDefault("fishing", 0d));
    }

    private static void modifier(AttributeInstance a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        if (a == null) return;
        var old = a.getModifier(key);
        if (old != null && old.getAmount() == amount && old.getOperation() == op) return;
        if (old != null) a.removeModifier(key);
        if (amount != 0) a.addTransientModifier(new AttributeModifier(key, amount, op));
    }

    private static Map<PotionEffectType, Integer> wanted(Map<String, Double> e) {
        var out = new HashMap<PotionEffectType, Integer>();
        put(out, PotionEffectType.HASTE, e.get("haste"));
        put(out, PotionEffectType.JUMP_BOOST, e.get("jump"));
        put(out, PotionEffectType.DOLPHINS_GRACE, e.get("dolphin"));
        put(out, PotionEffectType.NIGHT_VISION, e.get("night_vision"));
        put(out, PotionEffectType.WATER_BREATHING, e.get("water_breathing"));
        put(out, PotionEffectType.FIRE_RESISTANCE, e.get("fire_resist"));
        return out;
    }
    private static void put(Map<PotionEffectType, Integer> out, PotionEffectType type, Double level) {
        if (level == null) return;
        int amp = CompanionMath.amplifier(level);
        if (amp >= 0) out.put(type, amp);
    }
    /** 펫이 건 포션 표시: 무한 + ambient + 파티클 없음. */
    private static boolean ours(PotionEffect e) { return e != null && e.isInfinite() && e.isAmbient() && !e.hasParticles(); }

    /** passive 포션을 맞춘다: 필요한 것은 (없으면) 걸고, 펫 표시가 있는데 필요 없는 것은 지운다. */
    private void potions(Player p, Map<String, Double> effects) {
        var want = wanted(effects);
        for (var type : PASSIVE_POTIONS) {
            var cur = p.getPotionEffect(type);
            Integer amp = want.get(type);
            if (amp != null) {
                if (p.isDead()) continue;
                if (cur == null || (ours(cur) && cur.getAmplifier() != amp)) {
                    if (cur != null) p.removePotionEffect(type);
                    p.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, true, false, true));
                }
            } else if (ours(cur)) p.removePotionEffect(type);
        }
    }
    /** 접속 직후 한 번: 지난 세션에 남은 펫 포션 정리 (포션은 플레이어 데이터에 저장되므로). */
    private void cleanPotions(Player p, Def d) {
        var want = d == null || !d.passive() ? Map.<PotionEffectType, Integer>of() : wanted(d.effects());
        for (var type : PASSIVE_POTIONS) if (!want.containsKey(type) && ours(p.getPotionEffect(type))) p.removePotionEffect(type);
    }

    // ------------------------------------------------------------------ 전투 중 자동 발동

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        Entity from = ArtifactEffects.source(e.getDamager()), to = e.getEntity();
        // [기능:boss-pet-skills 시작] MCPets 보스펫 주인도 전투 판정 (동행 펫을 꺼내지 않은 사람)
        if (to instanceof Player victim && from instanceof LivingEntity f && !f.equals(victim) && !owners.containsKey(victim.getUniqueId())) boss.mark(victim, f);
        if (from instanceof Player attacker && to instanceof LivingEntity t && !t.equals(attacker) && !owners.containsKey(attacker.getUniqueId())) boss.mark(attacker, t);
        // [기능:boss-pet-skills 끝]
        if (owners.isEmpty()) return;
        if (to instanceof Player victim && from instanceof LivingEntity f && !f.equals(victim)) mark(victim, f);
        if (from instanceof Player attacker && to instanceof LivingEntity t && !t.equals(attacker)) mark(attacker, t);
    }
    private void mark(Player p, LivingEntity other) {
        var o = owners.get(p.getUniqueId());
        if (o == null || other instanceof ArmorStand || boss.isMinion(other)) return;   // [기능:boss-pet-skills] 내 하수인은 상대가 아님
        o.combatAt = now(); o.opponent = other;
    }
    // [기능:boss-pet-skills 시작] 소환 하수인 ↔ 주인은 서로 때리지 않는다
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void minionFriendly(EntityDamageByEntityEvent e) {
        if (boss.friendly(ArtifactEffects.source(e.getDamager()), e.getEntity())) e.setCancelled(true);
    }
    // [기능:boss-pet-skills 끝]
    private boolean target(Player p, LivingEntity t) {
        if (t == null || !t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) return false;
        if (t instanceof Player q && (!q.isOnline() || q.getGameMode() == GameMode.SPECTATOR)) return false;
        double r = settings.range();
        return t.getLocation().distanceSquared(p.getLocation()) <= r * r;
    }

    /** 1초마다: 펫을 꺼낸 사람만. 경기 확인 · 시간 지난 버프 정리 · 포션 유지(우유 등) · 자동 발동. */
    private void second() {
        boss.second(now());   // [기능:boss-pet-skills] MCPets 보스펫 · 하수인
        if (owners.isEmpty()) return;
        long now = now();
        for (UUID id : List.copyOf(owners.keySet())) {
            var p = Bukkit.getPlayer(id); var o = owners.get(id);
            if (p == null || o == null || !plugin.playerStateReady(p)) continue;
            var d = defs.get(o.pet);
            if (d == null) continue;
            int m = match(p);
            if (m == 2) { unequip(p, "경기가 시작되어 펫을 넣었어요"); continue; }
            boolean sup = m == 1;
            if (sup != o.suppressed) { o.suppressed = sup; if (sup) clearTimed(p, o); apply(p); }
            if (o.buffUntil > 0 && now >= o.buffUntil) endBuff(p, o);
            if (o.absorbUntil > 0 && now >= o.absorbUntil) endAbsorb(p, o);
            if (sup || p.isDead()) continue;
            if (d.passive()) potions(p, d.effects());
            if (d.skills().isEmpty() && !d.combat()) continue;
            if (o.opponent != null && !CompanionMath.inCombat(o.combatAt, now, settings.combatMs())) o.opponent = null;
            if (!CompanionMath.ready(o.nextAt, o.combatAt, now, settings.combatMs()) || !target(p, o.opponent)) continue;
            o.nextAt = now + settings.intervalMs(d.stars());
            lastFire.put(id, now);
            // [기능:boss-pet-skills 시작] 스킬과 combat 능력이 둘 다 있으면 (모르바스) 그중 하나
            int pick = d.skills().isEmpty() ? -1 : ThreadLocalRandom.current().nextInt(d.skills().size() + (d.combat() ? 1 : 0));
            if (pick >= 0 && pick < d.skills().size()) cast(p, d.skills().get(pick), o.opponent, p.getLocation(), d.skills());
            else combat(p, o, d, now);
            // [기능:boss-pet-skills 끝]
            broadcast(p.getWorld(), null, id);   // 발동 표시 (FLAG_CAST) 한 번
        }
    }

    private void castSkill(Player p, String skill, LivingEntity target) { castSkill(p, skill, target, p.getLocation()); }
    private void castSkill(Player p, String skill, LivingEntity target, Location origin) {   // [기능:boss-pet-skills] origin (보스펫 몸)
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return;
        try { if (!Mythic.cast(p, skill, target, origin) && skillFailures.add(skill)) plugin.getLogger().warning("펫 스킬 시전 실패 (MythicMobs Skills/chacademi_pet_skills.yml 배포 확인): " + skill); }
        catch (RuntimeException | LinkageError e) { if (skillFailures.add(skill)) plugin.getLogger().warning("펫 스킬 시전 오류: " + skill + " / " + e); }
    }
    /** MythicMobs 는 compileOnly: 이 클래스는 MythicMobs 가 켜져 있을 때만 불린다. */
    private static final class Mythic {
        static boolean cast(Player p, String skill, LivingEntity target, Location origin) {
            // 피해는 스킬 안 flat 고정값 (지능 무관). 스킬 줄은 @trigger = 이 상대.
            return io.lumine.mythic.bukkit.MythicBukkit.inst().getAPIHelper().castSkill(p, skill, target, origin,
                    List.<Entity>of(target), List.of(target.getLocation()), 1f, data -> {});
        }
    }

    // [기능:boss-pet-skills 시작] BossPetSkills 가 쓰는 것
    /** 스킬 하나 (summon:<몹>:<최대> 이면 하수인 소환, 못 하면 같은 펫의 다른 스킬). */
    void cast(Player p, String skill, LivingEntity target, Location origin, List<String> all) {
        if (!skill.startsWith(BossPetSkills.SUMMON)) { castSkill(p, skill, target, origin); return; }
        if (boss.summon(p, skill, target)) return;
        for (String other : all) if (!other.startsWith(BossPetSkills.SUMMON)) { castSkill(p, other, target, origin); return; }
    }
    long intervalMs(int stars) { return settings.intervalMs(stars); }
    long combatMs() { return settings.combatMs(); }
    boolean inRange(Player p, LivingEntity t) { return target(p, t); }
    int matchState(Player p) { return match(p); }
    long lastFire(UUID id) { Long t = lastFire.get(id); return t == null ? Long.MIN_VALUE / 2 : t; }
    void fired(UUID id, long now) { lastFire.put(id, now); }
    LivingEntity opponent(Player p) { var o = owners.get(p.getUniqueId()); return o == null ? null : o.opponent; }
    boolean inCombatNow(Player p, long now) { var o = owners.get(p.getUniqueId()); return o != null && !o.suppressed && CompanionMath.inCombat(o.combatAt, now, settings.combatMs()); }
    boolean hasSummon(Player p) {
        var o = owners.get(p.getUniqueId()); var d = o == null ? null : defs.get(o.pet);
        if (d != null) for (String s : d.skills()) if (s.startsWith(BossPetSkills.SUMMON)) return true;
        return false;
    }
    // [기능:boss-pet-skills 끝]

    /** special.kind=combat: effects 를 seconds 동안 (seconds 가 없으면 즉시형). */
    private void combat(Player p, Owner o, Def d, long now) {
        var e = d.effects();
        int seconds = d.seconds();
        int ticks = Math.max(1, seconds > 0 ? seconds : 5) * 20;
        if (seconds > 0) o.buffUntil = now + seconds * 1000L;
        if (seconds > 0 && e.containsKey("intelligence")) o.intBonus = Math.clamp(e.get("intelligence"), 0, 10);
        if (seconds > 0 && e.containsKey("speed")) modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), combatSpeedKey, Math.clamp(e.get("speed"), 0, 2), AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        if (seconds > 0 && e.containsKey("knockback")) modifier(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE), combatKnockKey, Math.clamp(e.get("knockback"), 0, 1), AttributeModifier.Operation.ADD_NUMBER);
        timedPotion(p, PotionEffectType.RESISTANCE, e.get("resistance"), ticks);
        timedPotion(p, PotionEffectType.REGENERATION, e.get("regen"), ticks);
        if (e.getOrDefault("cleanse", 0d) > 0) for (var type : HARMFUL) if (p.hasPotionEffect(type)) p.removePotionEffect(type);
        if (e.containsKey("absorb")) absorb(p, o, e.get("absorb"), seconds > 0 ? seconds * 1000L : settings.intervalMs(d.stars()), now);
        double heal = e.getOrDefault("heal", 0d);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (heal > 0 && max != null && !p.isDead()) p.setHealth(Math.min(max.getValue(), p.getHealth() + heal));
        double add = e.getOrDefault("mana", 0d);
        if (add > 0 && mana.snapshot(p.getUniqueId()).isPresent()) mana.add(p.getUniqueId(), add);
    }
    private static void timedPotion(Player p, PotionEffectType type, Double level, int ticks) {
        if (level == null) return;
        int amp = CompanionMath.amplifier(level);
        if (amp >= 0) p.addPotionEffect(new PotionEffect(type, ticks, amp, false, true, true));
    }
    /** 흡수 하트 N칸 (= 체력 2N). 1.20.5+ 는 MAX_ABSORPTION 이 상한이라 그동안 상한을 같이 올린다. 이미 더 많으면 그대로. */
    private void absorb(Player p, Owner o, double hearts, long durationMs, long now) {
        var attr = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (attr == null || hearts <= 0) return;
        double amount = Math.min(hearts, 20) * 2;
        var old = attr.getModifier(absorbKey);
        if (old != null) attr.removeModifier(absorbKey);
        attr.addTransientModifier(new AttributeModifier(absorbKey, amount, AttributeModifier.Operation.ADD_NUMBER));
        p.setAbsorptionAmount(Math.min(attr.getValue(), CompanionMath.absorb(p.getAbsorptionAmount(), hearts)));
        o.absorbUntil = now + durationMs;
    }
    private void endAbsorb(Player p, Owner o) {
        o.absorbUntil = 0;
        var attr = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (attr == null || attr.getModifier(absorbKey) == null) return;
        attr.removeModifier(absorbKey);
        if (p.getAbsorptionAmount() > attr.getValue()) p.setAbsorptionAmount(Math.max(0, attr.getValue()));
    }
    private void endBuff(Player p, Owner o) {
        o.buffUntil = 0; o.intBonus = 0;
        modifier(p.getAttribute(Attribute.MOVEMENT_SPEED), combatSpeedKey, 0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        modifier(p.getAttribute(Attribute.KNOCKBACK_RESISTANCE), combatKnockKey, 0, AttributeModifier.Operation.ADD_NUMBER);
    }
    /** 시간제 전투 버프를 지금 끝낸다 (넣기·바꾸기·경기). 남은 포션(저항·재생)은 원래 시간대로. */
    private void clearTimed(Player p, Owner o) { endBuff(p, o); endAbsorb(p, o); }

    // ------------------------------------------------------------------ 경기

    private Constructor<?> matchNew; private Method matchKind; private ClassLoader matchLoader;
    /** 0 경기 밖 · 1 그 밖의 경기(체력만) · 2 큐디치·결투(blocked-matches: 펫 불가). */
    private int match(Player p) {
        if (!GeneralPresetBridge.combat(p.getUniqueId())) return 0;
        return settings.blocked().contains(matchKind(p).toLowerCase(Locale.ROOT)) ? 2 : 1;
    }
    /** MagicMythic 의 MagicMatchEvent 로 경기 종류를 묻는다 (컴파일 의존 없음, QuidditchCombat 와 같은 질문). */
    private String matchKind(Player p) {
        var m = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (m == null || !m.isEnabled()) return "";
        try {
            var loader = m.getClass().getClassLoader();
            if (loader != matchLoader) {
                var type = Class.forName("school.chacademia.magicmythic.MagicMatchEvent", true, loader);
                matchNew = type.getConstructor(Player.class); matchKind = type.getMethod("getKind"); matchLoader = loader;
            }
            var event = (Event) matchNew.newInstance(p);
            Bukkit.getPluginManager().callEvent(event);
            return matchKind.invoke(event) instanceof String s ? s : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return ""; }
    }

    // ------------------------------------------------------------------ 전송

    /** 그 세계 전원에게 그 세계의 꺼낸 펫 목록. exclude: 나가는 사람 (아직 세계에 남아 있음). cast: 방금 발동한 주인. */
    private void broadcast(World w, Player exclude, UUID cast) {
        if (w == null) return;
        var players = w.getPlayers();
        if (players.isEmpty()) return;
        byte[] bytes = encode(w, players, exclude, cast);
        for (var q : players) if (q != exclude && q.getListeningPluginChannels().contains(PetCompanionProtocol.CHANNEL))
            q.sendPluginMessage(plugin, PetCompanionProtocol.CHANNEL, bytes);
    }
    private void sendTo(Player q) {
        if (!q.isOnline() || !q.getListeningPluginChannels().contains(PetCompanionProtocol.CHANNEL)) return;
        q.sendPluginMessage(plugin, PetCompanionProtocol.CHANNEL, encode(q.getWorld(), q.getWorld().getPlayers(), null, null));
    }
    private byte[] encode(World w, List<Player> players, Player exclude, UUID cast) {
        var list = new ArrayList<Pet>();
        for (var q : players) {
            if (q == exclude) continue;
            var o = owners.get(q.getUniqueId());
            var d = o == null ? null : defs.get(o.pet);
            if (d == null) continue;
            list.add(new Pet(q.getEntityId(), d.id(), d.stars(), d.motion(), d.scale(), q.getUniqueId().equals(cast) ? PetCompanionProtocol.FLAG_CAST : 0));
            if (list.size() >= PetCompanionProtocol.MAX_PETS) break;
        }
        String name = w.getName();
        return PetCompanionProtocol.encode(new Pets(name.length() > 128 ? name.substring(0, 128) : name, list));
    }

    @EventHandler public void join(PlayerJoinEvent e) { sendTo(e.getPlayer()); }
    /** 클라 채널 등록은 접속보다 늦을 수 있다 (다른 스레드일 수 있어 다음 틱에). */
    @EventHandler public void channel(PlayerRegisterChannelEvent e) {
        if (!PetCompanionProtocol.CHANNEL.equals(e.getChannel())) return;
        var p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> sendTo(p));
    }
    @EventHandler public void world(PlayerChangedWorldEvent e) {
        var p = e.getPlayer();
        broadcast(e.getFrom(), null, null);
        if (owners.containsKey(p.getUniqueId())) broadcast(p.getWorld(), null, null); else sendTo(p);
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        var p = e.getPlayer();
        if (!owners.containsKey(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && owners.containsKey(p.getUniqueId())) { apply(p); broadcast(p.getWorld(), null, null); } });
    }
    /** MONITOR: PlayerStateBridge(LOWEST)가 펫 토큰을 저장한 뒤. */
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        var p = e.getPlayer(); UUID id = p.getUniqueId();
        boolean had = owners.remove(id) != null;
        applied.remove(id); lastFire.remove(id);
        boss.forget(id);   // [기능:boss-pet-skills]
        if (had) broadcast(p.getWorld(), p, null);
    }

    @Override public void close() {
        task.cancel();
        HandlerList.unregisterAll(this);
        boss.close();   // [기능:boss-pet-skills] 하수인 정리
        for (UUID id : List.copyOf(owners.keySet())) {
            var p = Bukkit.getPlayer(id); var o = owners.remove(id);
            if (p != null && o != null) { clearTimed(p, o); apply(p); }
        }
        for (UUID id : List.copyOf(applied.keySet())) { var p = Bukkit.getPlayer(id); if (p != null) apply(p); }
        owners.clear(); applied.clear(); lastFire.clear();
    }
}
