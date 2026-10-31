package school.magiccodex.paper;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;

// [기능:boss-pet-skills 시작] 보스펫 스킬 (2026-10-31, 새 파일)
/**
 * 보스펫 전투 스킬 (오너 2026-10-31: 보스가 살아 있을 때 쓰던 스킬만, 새 스킬 없음).
 * <ul>
 *   <li><b>MCPets 보스펫</b> (엘더 가디언·엔더 드래곤·위더·워든): 동행 펫이 아니라 PetCompanions 의 자동 발동이 돌지 않는다.
 *       pet-companions.yml {@code mcpets-skills.<MCPets Id>: [스킬...]} 에 적힌 펫을 꺼낸 사람만, 동행 펫과 같은 규칙
 *       (3성 주기 · 전투 판정 · 사거리 · 경기)으로 스킬을 쓴다. 스킬 시작 위치(@origin)는 그 펫 몸.</li>
 *   <li><b>소환 스킬</b> {@code summon:<MythicMob>:<최대>} (모르바스 망자 병사): 주인에게 묶인 전투 하수인. 주인이 싸우는 상대만 노리고,
 *       최대 수가 차 있거나 그 몹이 이 서버에 없으면 소환하지 않는다(다른 스킬을 쓴다). 전투가 끝나거나 펫을 넣거나 30초가 지나면 사라진다.</li>
 * </ul>
 * MCPets 는 리플렉션 (컴파일 의존 없음, PetBridge 와 같은 API). 1초 작업은 전투 중인 보스펫 주인·하수인이 있을 때만 일한다. 메인 스레드 전용.
 */
final class BossPetSkills {
    static final String SUMMON = "summon:";
    private static final long MINION_LIFE_MS = 30_000L, LOOKUP_MS = 5_000L;

    private final MagicCodexBridge plugin;
    private final PetCompanions pets;
    private Map<String, List<String>> mcSkills = Map.of();
    /** MCPets 보스펫 주인의 전투 상태. */
    private static final class McOwner { String pet; long lookedAt, nextAt, combatAt = -1; LivingEntity opponent; }
    private final Map<UUID, McOwner> mcOwners = new HashMap<>();
    private record Minion(LivingEntity entity, long until) {}
    private final Map<UUID, List<Minion>> minions = new HashMap<>();
    private final Map<UUID, UUID> minionOwner = new HashMap<>();
    private Hook hook; private long hookRetryAt;

    BossPetSkills(MagicCodexBridge plugin, PetCompanions pets) { this.plugin = plugin; this.pets = pets; }

    /** pet-companions.yml 의 mcpets-skills. */
    void load(YamlConfiguration y) {
        var out = new HashMap<String, List<String>>();
        var s = y.getConfigurationSection("mcpets-skills");
        if (s != null) for (String id : s.getKeys(false)) {
            var list = new ArrayList<String>();
            for (String k : s.getStringList(id)) if (k.matches("[A-Za-z0-9_.:-]{1,128}")) list.add(k);
            if (!list.isEmpty()) out.put(id.toLowerCase(Locale.ROOT), List.copyOf(list));
        }
        mcSkills = Map.copyOf(out);
        if (mcSkills.isEmpty()) mcOwners.clear();
    }

    // ------------------------------------------------------------------ MCPets 보스펫

    /** 맞거나 때린 플레이어 (동행 펫을 꺼내지 않은 사람). */
    void mark(Player p, LivingEntity other) {
        if (mcSkills.isEmpty() || other instanceof ArmorStand || isMinion(other)) return;
        long now = System.currentTimeMillis();
        var o = mcOwners.get(p.getUniqueId());
        if (o == null || now - o.lookedAt > LOOKUP_MS) {
            String pet = activeBossPet(p);
            if (pet == null) { mcOwners.remove(p.getUniqueId()); return; }
            if (o == null) { o = new McOwner(); mcOwners.put(p.getUniqueId(), o); }
            if (!pet.equals(o.pet)) o.nextAt = Math.max(o.nextAt, pets.lastFire(p.getUniqueId()) + pets.intervalMs(3));
            o.pet = pet; o.lookedAt = now;
        }
        o.combatAt = now; o.opponent = other;
    }

    /** 1초마다 (PetCompanions.second). */
    void second(long now) {
        if (!minions.isEmpty()) tickMinions(now);
        if (mcOwners.isEmpty()) return;
        for (UUID id : List.copyOf(mcOwners.keySet())) {
            var p = Bukkit.getPlayer(id); var o = mcOwners.get(id);
            if (p == null || o == null) { mcOwners.remove(id); continue; }
            if (!CompanionMath.inCombat(o.combatAt, now, pets.combatMs())) { mcOwners.remove(id); continue; }
            if (p.isDead() || !CompanionMath.ready(o.nextAt, o.combatAt, now, pets.combatMs()) || !pets.inRange(p, o.opponent)) continue;
            if (pets.matchState(p) != 0) continue;   // 경기 중에는 동행 펫처럼 스킬 없음
            var body = petBody(p, o.pet);
            if (body == null) { mcOwners.remove(id); continue; }   // 펫을 넣었음
            var skills = mcSkills.get(o.pet);
            if (skills == null) { mcOwners.remove(id); continue; }
            o.nextAt = now + pets.intervalMs(3);
            pets.fired(id, now);
            pets.cast(p, skills.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(skills.size())), o.opponent,
                    body.getLocation().add(0, body.getHeight() * 0.6, 0), skills);
        }
    }

    /** 이 플레이어가 꺼낸 MCPets 펫 중 mcpets-skills 에 있는 것 (없으면 null). */
    private String activeBossPet(Player p) {
        var h = hook();
        if (h == null) return null;
        try {
            for (Object pet : (List<?>) h.active.invoke(null, p.getUniqueId())) {
                String id = ((String) h.id.invoke(pet)).toLowerCase(Locale.ROOT);
                if (mcSkills.containsKey(id)) return id;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
        return null;
    }
    /** 꺼낸 그 펫의 몸 (없으면 null). */
    private LivingEntity petBody(Player p, String petId) {
        var h = hook();
        if (h == null) return null;
        try {
            for (Object pet : (List<?>) h.active.invoke(null, p.getUniqueId())) {
                if (!petId.equalsIgnoreCase((String) h.id.invoke(pet))) continue;
                Object mob = h.activeMob.invoke(pet);
                if (mob == null) return null;
                Object entity = mob.getClass().getMethod("getEntity").invoke(mob);
                Object bukkit = entity == null ? null : entity.getClass().getMethod("getBukkitEntity").invoke(entity);
                return bukkit instanceof LivingEntity le && le.isValid() && le.getWorld() == p.getWorld() ? le : null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
        return null;
    }
    private Hook hook() {
        if (hook != null) return hook;
        long now = System.currentTimeMillis();
        if (now < hookRetryAt) return null;
        var mc = Bukkit.getPluginManager().getPlugin("MCPets");
        if (mc == null || !mc.isEnabled()) { hookRetryAt = now + 30_000L; return null; }
        try { hook = new Hook(mc.getClass().getClassLoader()); }
        catch (ReflectiveOperationException | LinkageError e) { hookRetryAt = now + 30_000L; plugin.getLogger().warning("보스펫 스킬: MCPets 연결 실패 " + e); }
        return hook;
    }
    private static final class Hook {
        final Method active, id, activeMob;
        Hook(ClassLoader loader) throws ReflectiveOperationException {
            var api = Class.forName("fr.nocsy.mcpets.api.MCPetsAPI", true, loader);
            var pet = Class.forName("fr.nocsy.mcpets.data.Pet", true, loader);
            active = api.getMethod("getActivePetsForPlayer", UUID.class);
            id = pet.getMethod("getId"); activeMob = pet.getMethod("getActiveMob");
        }
    }

    // ------------------------------------------------------------------ 소환 하수인

    /** summon:<몹>:<최대>. 소환했으면 true (최대 수가 찼거나 몹이 없으면 false → 다른 스킬). */
    boolean summon(Player p, String spec, LivingEntity target) {
        String[] part = spec.substring(SUMMON.length()).split(":");
        String type = part[0];
        int max = part.length > 1 ? Math.clamp(parse(part[1]), 1, 4) : 1;
        var list = minions.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(m -> !m.entity().isValid() || m.entity().isDead());
        if (list.size() >= max || !Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return false;
        LivingEntity e;
        try { e = MythicSpawn.spawn(type, spot(p, target)); }
        catch (RuntimeException | LinkageError ex) { plugin.getLogger().warning("보스펫 하수인 소환 실패: " + type + " / " + ex); return false; }
        if (e == null) return false;   // 이 서버에 그 몹(모델)이 없음
        e.setPersistent(false);
        e.setRemoveWhenFarAway(true);
        list.add(new Minion(e, System.currentTimeMillis() + MINION_LIFE_MS));
        minionOwner.put(e.getUniqueId(), p.getUniqueId());
        if (e instanceof Mob mob && target != null && target.isValid()) mob.setTarget(target);
        return true;
    }
    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }
    /** 주인 옆 (상대 쪽으로 1.5블록). */
    private static Location spot(Player p, LivingEntity target) {
        var at = p.getLocation().clone();
        if (target != null && target.getWorld() == p.getWorld()) {
            var dir = target.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (dir.lengthSquared() > 0.01) at.add(dir.normalize().multiply(1.5));
        }
        return at;
    }
    private static final class MythicSpawn {
        static LivingEntity spawn(String type, Location at) {
            var mm = io.lumine.mythic.bukkit.MythicBukkit.inst();
            if (mm.getMobManager().getMythicMob(type).isEmpty()) return null;
            var active = mm.getMobManager().spawnMob(type, at);
            return active == null ? null : active.getEntity().getBukkitEntity() instanceof LivingEntity le ? le : null;
        }
    }

    boolean isMinion(Entity e) { return e != null && minionOwner.containsKey(e.getUniqueId()); }
    /** 하수인 ↔ 주인은 서로 때리지 않는다. */
    boolean friendly(Entity from, Entity to) {
        UUID a = from == null ? null : minionOwner.get(from.getUniqueId()), b = to == null ? null : minionOwner.get(to.getUniqueId());
        return (a != null && to instanceof Player p && a.equals(p.getUniqueId())) || (b != null && from instanceof Player q && b.equals(q.getUniqueId()))
                || (a != null && a.equals(b));
    }

    private void tickMinions(long now) {
        for (UUID id : List.copyOf(minions.keySet())) {
            var p = Bukkit.getPlayer(id); var list = minions.get(id);
            LivingEntity opponent = p == null ? null : pets.opponent(p);
            boolean keep = p != null && !p.isDead() && pets.hasSummon(p) && pets.inCombatNow(p, now);
            for (var it = list.iterator(); it.hasNext(); ) {
                var m = it.next(); var e = m.entity();
                if (!e.isValid() || e.isDead()) { it.remove(); minionOwner.remove(e.getUniqueId()); continue; }
                if (!keep || now >= m.until() || e.getWorld() != p.getWorld() || e.getLocation().distanceSquared(p.getLocation()) > 32 * 32) {
                    e.remove(); it.remove(); minionOwner.remove(e.getUniqueId()); continue;
                }
                if (e instanceof Mob mob && opponent != null && opponent.isValid() && !opponent.isDead() && mob.getTarget() != opponent) mob.setTarget(opponent);
            }
            if (list.isEmpty()) minions.remove(id);
        }
    }

    /** 주인이 나가거나 펫을 넣을 때. */
    void forget(UUID id) {
        mcOwners.remove(id);
        var list = minions.remove(id);
        if (list != null) for (var m : list) { minionOwner.remove(m.entity().getUniqueId()); if (m.entity().isValid()) m.entity().remove(); }
    }

    void close() {
        for (UUID id : List.copyOf(minions.keySet())) forget(id);
        mcOwners.clear(); minionOwner.clear();
    }
}
// [기능:boss-pet-skills 끝]
