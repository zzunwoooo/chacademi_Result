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

    final NamespacedKey dmgKey, affixKey, kindKey;
    private final Logger log;

    Floors(Plugin plugin) {
        log = plugin.getLogger();
        dmgKey = new NamespacedKey(plugin, "tower_dmg");
        affixKey = new NamespacedKey(plugin, "tower_affix");
        kindKey = new NamespacedKey(plugin, "tower_kind");
    }

    static double healthMult(Settings s, int floor) { return s.bal("health-base", 1) + s.bal("health-per-floor", 0.09) * (floor - 1); }
    static double damageMult(Settings s, int floor) { return s.bal("damage-base", 1) + s.bal("damage-per-floor", 0.045) * (floor - 1); }

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
        for (int i = 0; i < total; i++) {
            boolean elite = !s.eliteMobs.isEmpty() && rng.nextDouble() < share;
            var list = elite ? s.eliteMobs : s.normalMobs;
            if (list.isEmpty()) list = s.eliteMobs.isEmpty() ? s.normalMobs : s.eliteMobs;
            if (list.isEmpty()) break;
            all.add(new Pick(list.get(rng.nextInt(list.size())), list == s.eliteMobs, false));
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
        double hp = (pick.elite() ? s.bal("elite-health", 150) : s.bal("normal-health", 40)) * healthMult(s, floor);
        double dmg = (pick.elite() ? s.bal("elite-damage", 9) : s.bal("normal-damage", 4)) * damageMult(s, floor);
        if (pick.guardian()) { hp *= s.bal("guardian-health", 5); dmg *= s.bal("guardian-damage", 1.5); }
        var maxHp = le.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(Math.min(2048, Math.max(1, hp)));
            le.setHealth(maxHp.getValue());
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
            if (has(le, "swift")) {
                var sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
                if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.35);
            }
            if (has(le, "shield")) {
                double shield = (maxHp == null ? 20 : maxHp.getValue()) * 0.35;
                var cap = le.getAttribute(Attribute.MAX_ABSORPTION);
                if (cap != null) { cap.setBaseValue(Math.max(cap.getBaseValue(), shield)); le.setAbsorptionAmount(shield); }
            }
        }
        if (pick.guardian()) le.setGlowing(true);
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
