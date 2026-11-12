package school.chacademia.tower;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 층 구성: 몇 마리·정예 비율·수문장·몹 특성. 몹은 기존 미몹을 그대로 소환하고 체력·공격력만 층에 맞춰 바꾼다.
 * 공격력은 몹 PDC "tower_dmg" 배율로 남겨 두고, 피해 이벤트에서 그 몹이 준 모든 피해(평타·투사체·스킬)에 곱한다.
 */
final class Floors {
    /** 한 층에 나올 몹 한 마리. */
    record Pick(String mob, boolean elite, boolean guardian) {}

    final NamespacedKey dmgKey, affixKey, kindKey, toughKey, speedKey;
    /** 서버 최대 체력 상한(spigot maxHealth 2048) 아래로 두는 값. 넘는 몫은 "단단함"(받는 피해 나누기)으로 돌린다. */
    static final double HP_CAP = 2000;
    private final Logger log;

    Floors(Plugin plugin) {
        log = plugin.getLogger();
        dmgKey = new NamespacedKey(plugin, "tower_dmg");
        affixKey = new NamespacedKey(plugin, "tower_affix");
        kindKey = new NamespacedKey(plugin, "tower_kind");
        toughKey = new NamespacedKey(plugin, "tower_tough");
        speedKey = new NamespacedKey(plugin, "tower_speed0");
    }

    /** 체력: (기본 + 층당 더하기) × 층당 곱하기^(층-1) — 오를수록 점점 더 가파르게 (11/12 오너). 1·10·25·50층 = 1.0·2.3·5.7·18.1배. */
    static double healthMult(Settings s, int floor) { return (s.bal("health-base", 1) + s.bal("health-per-floor", 0.09) * (floor - 1)) * Math.pow(s.bal("health-growth", 1.025), floor - 1); }
    /** 공격력: 1·10·25·50층 = 1.0·1.4·2.2·4.0배. */
    static double damageMult(Settings s, int floor) { return (s.bal("damage-base", 1) + s.bal("damage-per-floor", 0.03) * (floor - 1)) * Math.pow(s.bal("damage-growth", 1.01), floor - 1); }
    /** 이동 속도: 층당 speed-per-floor 씩, 최대 speed-max 배 (피하기 어려워지되 못 피할 정도는 아니게). 1·10·25·46층~ = 1.0·1.09·1.24·1.45배. */
    static double speedMult(Settings s, int floor) { return Math.min(s.bal("speed-max", 1.45), 1 + s.bal("speed-per-floor", 0.01) * (floor - 1)); }

    /** 수문장 체력 배수: 첫 수문장(10층)은 guardian-health, 뒤로 갈수록 guardian-health-per-tier 씩 더 (10·20·30·40·50층 = 2.0·2.5·3.0·3.5·4.0). */
    static double guardianHealth(Settings s, int floor) {
        int every = Math.max(1, (int) s.bal("guardian-every", 10));
        return s.bal("guardian-health", 2.0) + s.bal("guardian-health-per-tier", 0.5) * Math.max(0, floor / every - 1);
    }

    /** 이 층 몹 체력의 기대 합계 (특성·도박 배율 제외). 생명 흡수가 "한 층을 다 잡으면 얼마" 로 고르게 차도록 나누는 값. */
    static double expectedTotal(Settings s, int floor) {
        int n = Math.max(1, count(s, floor));
        double share = s.eliteMobs.isEmpty() ? 0 : eliteShare(s, floor), hm = healthMult(s, floor);
        double normal = s.bal("normal-health", 26) * hm, elite = s.bal("elite-health", 100) * hm, total = 0;
        if (guardianFloor(s, floor) && !s.eliteMobs.isEmpty()) { total += elite * guardianHealth(s, floor); n = Math.max(1, n - 3); }
        return total + n * (share * elite + (1 - share) * normal);
    }

    static int count(Settings s, int floor) {
        return (int) Math.min(s.bal("count-max", 11), Math.floor(s.bal("count-base", 3) + floor * s.bal("count-per-floor", 0.18)));
    }

    static double eliteShare(Settings s, int floor) {
        int from = (int) s.bal("elite-from", 4);
        if (floor < from) return 0;
        return Math.min(s.bal("elite-max", 0.55), s.bal("elite-per-floor", 0.015) * (floor - from + 1) + 0.08);
    }

    static boolean guardianFloor(Settings s, int floor) {
        int every = (int) s.bal("guardian-every", 10);
        return every > 0 && floor % every == 0;
    }

    /** 이 층의 무리들 (두 번째 무리는 second-wave-from 층부터, 마릿수를 반씩 나눔). */
    static List<List<Pick>> plan(Settings s, int floor) {
        var rng = ThreadLocalRandom.current();
        int total = Math.max(1, count(s, floor));
        double share = eliteShare(s, floor);
        var all = new ArrayList<Pick>();
        if (guardianFloor(s, floor) && !s.eliteMobs.isEmpty()) {
            all.add(new Pick(s.eliteMobs.get(rng.nextInt(s.eliteMobs.size())), true, true));
            total = Math.max(1, total - 3);
        }
        // [기능:탑유도몹] 따라오는 투사체 몹은 homing-from 층부터, 한 층에 1 + (층 / homing-per-floors) 마리까지 (초반부터 몰려나오면 너무 어려움, 오너 11/12)
        int homingLeft = floor < s.homingFrom ? 0 : 1 + floor / Math.max(1, s.homingPerFloors);
        for (int i = 0; i < total; i++) {
            boolean elite = !s.eliteMobs.isEmpty() && rng.nextDouble() < share;
            var list = elite ? s.eliteMobs : s.normalMobs;
            if (list.isEmpty()) list = s.eliteMobs.isEmpty() ? s.normalMobs : s.eliteMobs;
            if (list.isEmpty()) break;
            String mob = list.get(rng.nextInt(list.size()));
            if (s.homingMobs.contains(mob)) {
                if (homingLeft > 0) homingLeft--;
                else {
                    var calm = list.stream().filter(m -> !s.homingMobs.contains(m)).toList();
                    if (calm.isEmpty()) continue;
                    mob = calm.get(rng.nextInt(calm.size()));
                }
            }
            all.add(new Pick(mob, list == s.eliteMobs, false));
        }
        var waves = new ArrayList<List<Pick>>();
        if (floor >= (int) s.bal("second-wave-from", 15) && all.size() >= 4) {
            // 수문장은 두 번째 무리에
            var first = new ArrayList<Pick>();
            var second = new ArrayList<Pick>();
            for (var p : all) if (p.guardian()) second.add(p);
            var rest = all.stream().filter(p -> !p.guardian()).toList();
            for (int i = 0; i < rest.size(); i++) (i < (rest.size() + 1) / 2 ? first : second).add(rest.get(i));
            waves.add(first);
            waves.add(second);
        } else waves.add(all);
        return waves;
    }

    /** 몹 특성 뽑기 (수문장은 하나 더). */
    static List<Settings.Affix> rollAffixes(Settings s, int floor, boolean guardian) {
        var rng = ThreadLocalRandom.current();
        var out = new ArrayList<Settings.Affix>();
        int max = s.maxAffixes + (guardian ? 1 : 0);
        var pool = new ArrayList<>(s.affixes);
        java.util.Collections.shuffle(pool);
        for (var a : pool) {
            if (out.size() >= max) break;
            double c = a.chanceAt(floor) * (guardian ? 2 : 1);
            if (c > 0 && rng.nextDouble() < c) out.add(a);
        }
        return out;
    }

    /** 소환 직후: 체력·공격력 배율·특성 적용. */
    void prepare(Settings s, int floor, Entity e, Pick pick, List<Settings.Affix> affixes) {
        e.setPersistent(false);
        e.addScoreboardTag("chaca_tower");
        if (!(e instanceof LivingEntity le)) return;
        le.setRemoveWhenFarAway(false);
        double hp = (pick.elite() ? s.bal("elite-health", 100) : s.bal("normal-health", 26)) * healthMult(s, floor);
        double dmg = (pick.elite() ? s.bal("elite-damage", 9) : s.bal("normal-damage", 4)) * damageMult(s, floor);
        if (pick.guardian()) { hp *= guardianHealth(s, floor); dmg *= s.bal("guardian-damage", 1.5); }
        var maxHp = le.getAttribute(Attribute.MAX_HEALTH);
        setHealth(le, hp);
        // 이동 속도: 층이 오를수록 빨라짐. 원래 속도를 적어 두고 신속·광폭까지 합쳐 speed-max-total 배를 넘지 않게 한다.
        var kb = le.getAttribute(Attribute.KNOCKBACK_RESISTANCE);   // 오너 11/12: 탑 몹은 밀리지 않게
        if (kb != null) kb.setBaseValue(1.0);
        var speed = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            le.getPersistentDataContainer().set(speedKey, PersistentDataType.DOUBLE, speed.getBaseValue());
            speedUp(s, le, speedMult(s, floor));
        }
        // 원래 공격력 대비 배율 (원거리 몹처럼 0 이면 등급 기본값 기준)
        var atk = le.getAttribute(Attribute.ATTACK_DAMAGE);
        double orig = atk == null ? 0 : atk.getBaseValue();
        if (orig <= 0.01) orig = pick.elite() ? 12.15 : 5;
        var pdc = le.getPersistentDataContainer();
        pdc.set(dmgKey, PersistentDataType.DOUBLE, dmg / orig);
        pdc.set(kindKey, PersistentDataType.STRING, pick.guardian() ? "guardian" : pick.elite() ? "elite" : "normal");
        if (!affixes.isEmpty()) {
            var ids = new StringBuilder();
            for (var a : affixes) ids.append(ids.isEmpty() ? "" : ",").append(a.id());
            pdc.set(affixKey, PersistentDataType.STRING, ids.toString());
            if (has(le, "swift")) speedUp(s, le, 1.35);
            if (has(le, "shield")) {
                double shield = (maxHp == null ? 20 : maxHp.getValue()) * 0.35;
                var cap = le.getAttribute(Attribute.MAX_ABSORPTION);
                if (cap != null) { cap.setBaseValue(Math.max(cap.getBaseValue(), shield)); le.setAbsorptionAmount(shield); }
            }
        }
        if (pick.guardian()) le.setGlowing(true);
    }

    /**
     * 체력 정하기. 서버 상한(2048) 때문에 HP_CAP 을 넘는 몫은 "단단함" 으로: 최대 체력은 HP_CAP, 받는 피해를 hp / HP_CAP 로 나눈다
     * (수문장 체력이 30층부터 2048 에 막혀 더 안 오르던 것, 11/12 점검).
     */
    void setHealth(LivingEntity le, double hp) {
        var maxHp = le.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp == null) return;
        hp = Math.max(1, hp);
        double shown = Math.min(HP_CAP, hp);
        maxHp.setBaseValue(shown);
        le.setHealth(maxHp.getValue());
        if (hp > HP_CAP) le.getPersistentDataContainer().set(toughKey, PersistentDataType.DOUBLE, hp / HP_CAP);
        else le.getPersistentDataContainer().remove(toughKey);
    }

    /** 지금 실제 체력 총량 (최대 체력 × 단단함). */
    double effectiveHealth(LivingEntity le) {
        var maxHp = le.getAttribute(Attribute.MAX_HEALTH);
        return (maxHp == null ? 20 : maxHp.getBaseValue()) * tough(le);
    }

    /** 플레이어가 준 피해를 이 값으로 나눈다 (1 = 그대로). */
    double tough(Entity mob) {
        Double d = mob.getPersistentDataContainer().get(toughKey, PersistentDataType.DOUBLE);
        return d == null || d < 1 ? 1 : d;
    }

    /** 이동 속도를 mult 배로, 다만 원래 속도의 speed-max-total 배(기본 1.8)를 넘지 않게. */
    void speedUp(Settings s, LivingEntity le, double mult) {
        var sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp == null) return;
        Double orig = le.getPersistentDataContainer().get(speedKey, PersistentDataType.DOUBLE);
        double cap = (orig == null ? sp.getBaseValue() : orig) * s.bal("speed-max-total", 1.8);
        sp.setBaseValue(Math.min(cap, sp.getBaseValue() * mult));
    }

    boolean has(Entity e, String affix) {
        String v = e.getPersistentDataContainer().get(affixKey, PersistentDataType.STRING);
        if (v == null) return false;
        for (String s : v.split(",")) if (s.equals(affix)) return true;
        return false;
    }

    String affixText(Settings s, Entity e) {
        String v = e.getPersistentDataContainer().get(affixKey, PersistentDataType.STRING);
        if (v == null || v.isEmpty()) return "";
        var names = new ArrayList<String>();
        for (String id : v.split(",")) for (var a : s.affixes) if (a.id().equals(id)) names.add(a.name());
        return String.join("·", names);
    }

    double damageMult(Entity mob) {
        Double d = mob.getPersistentDataContainer().get(dmgKey, PersistentDataType.DOUBLE);
        return d == null ? 1 : d;
    }

    String kind(Entity mob) {
        String k = mob.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return k == null ? "normal" : k;
    }

    // [기능:탑몹스킬] MythicMobs 스킬을 그 몹이 쓰게 (대상은 몹이 노리는 사람)
    boolean castSkill(Entity mob, String skill) {
        try { return io.lumine.mythic.bukkit.MythicBukkit.inst().getAPIHelper().castSkill(mob, skill); }
        catch (RuntimeException | LinkageError e) { log.warning("몹 스킬 실패 " + skill + ": " + e); return false; }
    }

    /** 이 엔티티의 MythicMobs 이름 (아니면 null). */
    String mythicId(Entity e) {
        try {
            var am = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().getActiveMob(e.getUniqueId());
            return am.isPresent() ? am.get().getMobType() : null;
        } catch (RuntimeException | LinkageError ex) { return null; }
    }

    Entity spawn(String mob, Location at) {
        if (mob.startsWith("minecraft:")) {
            try { return at.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.valueOf(mob.substring(10).toUpperCase(java.util.Locale.ROOT))); }
            catch (IllegalArgumentException e) { log.warning("모르는 바닐라 몹: " + mob); return null; }
        }
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("MythicMobs") == null) { log.warning("MythicMobs 가 없어 소환 못 함: " + mob); return null; }
        try {
            var active = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().spawnMob(mob, at);
            return active == null ? null : active.getEntity().getBukkitEntity();
        } catch (RuntimeException | LinkageError e) { log.warning("MythicMobs 소환 실패 " + mob + ": " + e); return null; }
    }

    /**
     * 상자 안에서 발밑이 단단하고 둘레 3×3·3칸 높이가 빈 자리를 무작위로 count 곳 (ChacaDungeon 안전 소환과 같은 규칙).
     * avoid 에서 4칸 안은 피한다 (플레이어 바로 옆 소환 방지).
     */
    static List<Location> safeSpots(World w, Settings.Box box, int count, Location avoid) {
        var out = new ArrayList<Location>();
        var rng = ThreadLocalRandom.current();
        var used = new HashSet<Long>();
        int tries = count * 60;
        while (out.size() < count && tries-- > 0) {
            int x = rng.nextInt(box.minX(), box.maxX() + 1), z = rng.nextInt(box.minZ(), box.maxZ() + 1);
            if (avoid != null && tries > count * 20 && Math.hypot(x + .5 - avoid.getX(), z + .5 - avoid.getZ()) < 4) continue;
            for (int y = box.minY(); y <= box.maxY(); y++) {
                var feet = w.getBlockAt(x, y, z);
                if (!feet.isPassable() || !feet.getRelative(BlockFace.UP).isPassable() || feet.isLiquid()) continue;
                if (!feet.getRelative(BlockFace.DOWN).getType().isSolid()) continue;
                if (!roomy(w, x, y, z)) continue;
                long k = ((long) x << 40) ^ ((long) y << 20) ^ z;
                if (used.add(k)) out.add(new Location(w, x + .5, y, z + .5, rng.nextFloat() * 360, 0));
                break;
            }
        }
        return out;
    }

    // [기능:탑소환자리 시작]
    /** 찍어 둔 소환 자리에서 count 곳: 자리마다 돌아가며, 같은 자리를 또 쓰면 1~2칸 옆 빈 곳 (몹이 한 칸에 겹쳐 끼지 않게). */
    static List<Location> spawnSpots(World w, List<Settings.Loc> points, int count) {
        var out = new ArrayList<Location>();
        var rng = ThreadLocalRandom.current();
        var order = new ArrayList<>(points);
        java.util.Collections.shuffle(order);
        var used = new HashSet<Long>();
        for (int i = 0; i < count && !order.isEmpty(); i++) {
            var base = order.get(i % order.size());
            var at = base.location();
            if (at == null || !at.getWorld().equals(w)) continue;
            Location pick = null;
            for (int t = 0; t < 12 && pick == null; t++) {
                int dx = t == 0 ? 0 : rng.nextInt(-2, 3), dz = t == 0 ? 0 : rng.nextInt(-2, 3);
                int x = at.getBlockX() + dx, y = at.getBlockY(), z = at.getBlockZ() + dz;
                long k = ((long) x << 40) ^ ((long) y << 20) ^ z;
                if (used.contains(k)) continue;
                var feet = w.getBlockAt(x, y, z);
                if (t > 0 && (!feet.isPassable() || !feet.getRelative(BlockFace.UP).isPassable() || !feet.getRelative(BlockFace.DOWN).getType().isSolid())) continue;
                used.add(k);
                pick = new Location(w, x + .5, y, z + .5, rng.nextFloat() * 360, 0);
            }
            if (pick == null) pick = at.clone();
            out.add(pick);
        }
        return out;
    }
    // [기능:탑소환자리 끝]

    private static boolean roomy(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    var b = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
                if (!w.getBlockAt(x + dx, y - 1, z + dz).getType().isSolid() && !w.getBlockAt(x + dx, y - 2, z + dz).getType().isSolid()) return false;
            }
        return true;
    }
}
