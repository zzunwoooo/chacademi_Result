package school.magiccodex.paper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Team;

/**
 * 그림자 군주의 가면 (shadow_lord_mask, 그룹 D 2026-10-26): 내가 처치한 몹이 검은 그림자로 일어나 10초간 함께 싸운다.
 * <ul>
 *   <li>MythicMobs 몹(CA_Wild_* 등)은 같은 Mythic 종류를, 바닐라 몹은 같은 타입을 그 자리에 다시 소환 → 생전의 능력·기술 그대로.</li>
 *   <li>그림자: 무적, 저장 안 됨, 검은 팀 글로우 + 검은 안개 VFX(PortableVFX follow, 클라가 따라 그림), 드롭·경험치 없음,
 *       교화 불가(TamingTargetPolicy), 마법 대상 제외(chacademia_npc 태그), 10초 뒤 흩어짐.</li>
 *   <li>내가 공격한 적 / 나를 공격한 적을 노린다. 플레이어·펫·다른 그림자는 노리지도 때리지도 못한다 (대상 + 피해 이중 차단).</li>
 *   <li>보스 제외 (MagicMythic isBoss: config boss-tags·boss-mythic-prefixes + 바닐라 보스), 주인당 최대 10, 서버 전체 최대 40.
 *       큐디치·결투 경기 중(일반 프리셋 잠금)에는 일어나지 않는다.</li>
 * </ul>
 * 서버 부담: 그림자 목록만 0.5초마다 훑는다 (최대 40마리, 타겟 지정·길찾기 1회). 매 틱 작업 없음.
 */
final class ArtifactShadows implements Listener {
    static final String ID = "shadow_lord_mask", TAG = "chacademi_shadow", NO_MAGIC_TAG = "chacademia_npc";
    static final int PER_OWNER = 10, SERVER_CAP = 40, LIFE_TICKS = 200, RISE_DELAY = 11;
    static final double LEASH = 20, FOCUS_RANGE = 24;
    private final ArtifactEffects fx;
    private final MagicCodexBridge plugin;
    private record Shadow(UUID owner, Mob body, long until, UUID aura) {}
    private final Map<UUID, Shadow> shadows = new LinkedHashMap<>();
    private final Map<UUID, UUID> focus = new HashMap<>();   // owner -> target entity
    private int pending;
    private final Vfx vfx;

    ArtifactShadows(ArtifactEffects fx) {
        this.fx = fx; this.plugin = fx.plugin; this.vfx = new Vfx(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    }

    static boolean shadow(Entity e) {
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) e = shooter;
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    /** 그림자가 노리거나 때리면 안 되는 대상 (플레이어·펫·NPC·다른 그림자). */
    private boolean protectedTarget(Entity e) {
        if (e instanceof Player || e instanceof ArmorStand || e.hasMetadata("NPC") || shadow(e)) return true;
        var tags = e.getScoreboardTags();
        if (tags.contains("chacademia_pet") || tags.contains(NO_MAGIC_TAG)) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        var pets = plugin.pets();
        return pets != null && pets.activePet(e);
    }

    // ------------------------------------------------------------------ 일어남

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (shadow(dead)) { e.getDrops().clear(); e.setDroppedExp(0); return; }
        Player owner = dead.getKiller();
        if (owner == null || dead instanceof Player || !(dead instanceof Mob) || !fx.has(owner, ID)) return;
        if (owner.getWorld() != dead.getWorld() || protectedTarget(dead) || boss(dead) || GeneralPresetBridge.combat(owner.getUniqueId())) return;
        if (shadows.size() + pending >= SERVER_CAP) return;
        String mythic = mythicType(dead);
        EntityType type = dead.getType();
        if (mythic == null && (type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass()))) return;
        Location at = dead.getLocation().clone();
        double scale = attr(dead, Attribute.SCALE);
        boolean baby = dead instanceof Ageable a && !a.isAdult();
        vfx.play("shadow_rise", at, 40, (float) Math.clamp(dead.getHeight() / 1.8, 0.6, 2.5));
        pending++;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending--;
            if (!owner.isOnline() || owner.isDead() || owner.getWorld() != at.getWorld() || !fx.has(owner, ID)) return;
            if (!at.isChunkLoaded() || shadows.size() >= SERVER_CAP) return;
            Mob body = mythic != null ? spawnMythic(mythic, at) : spawnVanilla(type, at);
            if (body == null) return;
            dress(body, scale, baby);
            UUID aura = vfx.follow("shadow_aura", body, LIFE_TICKS, (float) Math.clamp(body.getHeight() / 1.8, 0.6, 2.5));
            var mine = shadows.values().stream().filter(s -> s.owner().equals(owner.getUniqueId())).toList();
            if (mine.size() >= PER_OWNER) dismiss(mine.getFirst(), true);       // 가장 오래된 그림자부터 흩어짐
            shadows.put(body.getUniqueId(), new Shadow(owner.getUniqueId(), body, System.currentTimeMillis() + LIFE_TICKS * 50L, aura));
            var target = focusOf(owner);
            if (target != null) body.setTarget(target);
        }, RISE_DELAY);
    }

    private boolean boss(LivingEntity e) {
        if (e instanceof EnderDragon || e instanceof Wither || e instanceof Warden || e instanceof ElderGuardian) return true;
        return Boolean.TRUE.equals(fx.magic().api("isBoss", new Class<?>[]{LivingEntity.class}, e));
    }

    private static double attr(LivingEntity e, Attribute a) { var i = e.getAttribute(a); return i == null ? -1 : i.getBaseValue(); }

    private void dress(Mob m, double scale, boolean baby) {
        m.addScoreboardTag(TAG); m.addScoreboardTag(NO_MAGIC_TAG);
        m.setPersistent(false); m.setInvulnerable(true); m.setCanPickupItems(false);
        m.setRemoveWhenFarAway(false);
        if (scale > 0) { var s = m.getAttribute(Attribute.SCALE); if (s != null) s.setBaseValue(scale); }
        if (baby && m instanceof Ageable a) a.setBaby();
        var eq = m.getEquipment();
        if (eq != null) for (EquipmentSlot slot : EquipmentSlot.values()) try { eq.setDropChance(slot, 0f); } catch (IllegalArgumentException ignored) { }
        m.setGlowing(true);
        var team = team();
        if (team != null) team.addEntry(m.getUniqueId().toString());
        m.getWorld().playSound(m.getLocation(), Sound.ENTITY_WITHER_SKELETON_AMBIENT, .7f, .6f);
    }

    private Team team() {
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = board.getTeam("chacademi_shadow");
            if (t == null) { t = board.registerNewTeam("chacademi_shadow"); t.color(net.kyori.adventure.text.format.NamedTextColor.BLACK); t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER); }
            return t;
        } catch (RuntimeException e) { return null; }
    }

    private Mob spawnVanilla(EntityType type, Location at) {
        var cls = type.getEntityClass();
        if (cls == null || !Mob.class.isAssignableFrom(cls) || at.getWorld() == null) return null;
        try { return (Mob) at.getWorld().spawn(at, cls, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> e.setPersistent(false)); }
        catch (RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------------ MythicMobs (리플렉션)

    private Object mmApi; private Method mmSpawn, mmActive, mmTypeName; private Plugin mmPlugin;
    private boolean mythic() {
        var mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mm == null || !mm.isEnabled()) return false;
        if (mm == mmPlugin) return mmApi != null;
        mmPlugin = mm; mmApi = null;
        try {
            Class<?> cls = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, mm.getClass().getClassLoader());
            Object inst = cls.getMethod("inst").invoke(null);
            Object helper = cls.getMethod("getAPIHelper").invoke(inst);
            mmSpawn = helper.getClass().getMethod("spawnMythicMob", String.class, Location.class);
            mmActive = helper.getClass().getMethod("getMythicMobInstance", Entity.class);
            mmApi = helper;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { plugin.getLogger().warning("그림자 군주의 가면: MythicMobs API 연결 실패 " + e); }
        return mmApi != null;
    }
    private String mythicType(Entity e) {
        if (!mythic()) return null;
        try {
            Object active = mmActive.invoke(mmApi, e);
            if (active == null) return null;
            Object type = active.getClass().getMethod("getType").invoke(active);
            if (mmTypeName == null) mmTypeName = type.getClass().getMethod("getInternalName");
            return (String) mmTypeName.invoke(type);
        } catch (ReflectiveOperationException | RuntimeException e2) { return null; }
    }
    private Mob spawnMythic(String type, Location at) {
        if (!mythic()) return null;
        try { return mmSpawn.invoke(mmApi, type, at) instanceof Mob m ? m : null; }
        catch (ReflectiveOperationException | RuntimeException e) { plugin.getLogger().warning("그림자 소환 실패 " + type + ": " + e); return null; }
    }

    // ------------------------------------------------------------------ 행동 (0.5초마다)

    private LivingEntity focusOf(Player owner) {
        UUID id = focus.get(owner.getUniqueId());
        if (id == null) return null;
        if (!(Bukkit.getEntity(id) instanceof LivingEntity t) || !t.isValid() || t.isDead() || t.getWorld() != owner.getWorld()
                || t.getLocation().distanceSquared(owner.getLocation()) > FOCUS_RANGE * FOCUS_RANGE) { focus.remove(owner.getUniqueId()); return null; }
        return t;
    }

    private void tick() {
        if (shadows.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var s : List.copyOf(shadows.values())) {
            Mob m = s.body();
            Player owner = Bukkit.getPlayer(s.owner());
            if (!m.isValid() || m.isDead()) { forget(s); continue; }
            if (now >= s.until() || owner == null || owner.isDead() || owner.getWorld() != m.getWorld()) { dismiss(s, true); continue; }
            LivingEntity target = focusOf(owner);
            var current = m.getTarget();
            if (target != null) { if (!target.equals(current)) m.setTarget(target); }
            else if (current != null && (protectedTarget(current) || !current.isValid())) m.setTarget(null);
            double d2 = m.getLocation().distanceSquared(owner.getLocation());
            if (d2 > LEASH * LEASH) m.teleport(owner.getLocation());
            else if (target == null && m.getTarget() == null && d2 > 16) m.getPathfinder().moveTo(owner.getLocation(), 1.2);
        }
    }

    private void forget(Shadow s) {
        shadows.remove(s.body().getUniqueId());
        vfx.stop(s.aura());
        var team = team();
        if (team != null) team.removeEntry(s.body().getUniqueId().toString());
    }

    private void dismiss(Shadow s, boolean effect) {
        forget(s);
        Mob m = s.body();
        if (effect && m.isValid()) {
            vfx.play("shadow_fade", m.getLocation(), 30, (float) Math.clamp(m.getHeight() / 1.8, 0.6, 2.5));
            m.getWorld().playSound(m.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1f, .7f);
        }
        m.remove();
    }

    // ------------------------------------------------------------------ 보호 (무적·아군 공격 금지)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (shadows.containsKey(e.getEntity().getUniqueId()) && e.getCause() != EntityDamageEvent.DamageCause.VOID) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent e) {
        if (shadow(e.getDamager()) && protectedTarget(e.getEntity())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent e) {
        if (shadow(e.getEntity()) && e.getTarget() != null && protectedTarget(e.getTarget())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transform(EntityTransformEvent e) { if (shadow(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) { if (shadow(e.getEntity())) e.blockList().clear(); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void block(EntityChangeBlockEvent e) { if (shadow(e.getEntity())) e.setCancelled(true); }

    /** 내가 공격한 적 / 나를 공격한 적 → 그림자들의 목표. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void focus(EntityDamageByEntityEvent e) {
        if (shadows.isEmpty()) return;
        Entity from = ArtifactEffects.source(e.getDamager()), victim = e.getEntity();
        if (from instanceof Player p && victim instanceof LivingEntity t && owns(p) && !protectedTarget(t)) focus.put(p.getUniqueId(), t.getUniqueId());
        else if (victim instanceof Player p && from instanceof LivingEntity t && owns(p) && !protectedTarget(t)) focus.put(p.getUniqueId(), t.getUniqueId());
    }
    private boolean owns(Player p) { for (var s : shadows.values()) if (s.owner().equals(p.getUniqueId())) return true; return false; }

    private void dismissOwner(UUID owner) { for (var s : List.copyOf(shadows.values())) if (s.owner().equals(owner)) dismiss(s, false); focus.remove(owner); }
    @EventHandler public void quit(PlayerQuitEvent e) { dismissOwner(e.getPlayer().getUniqueId()); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { dismissOwner(e.getPlayer().getUniqueId()); }
    @EventHandler public void died(PlayerDeathEvent e) { dismissOwner(e.getEntity().getUniqueId()); }

    void close() {
        for (var s : List.copyOf(shadows.values())) dismiss(s, false);
        shadows.clear(); focus.clear();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------ PortableVFX (서버 API, 리플렉션: kyudichi Hooks 와 같은 계약)

    static final class Vfx {
        private final MagicCodexBridge plugin;
        private Plugin owner; private Object service; private Method play, follow, stop, handle; private Constructor<?> request;
        Vfx(MagicCodexBridge plugin) { this.plugin = plugin; }
        private boolean ready() {
            Plugin p = Bukkit.getPluginManager().getPlugin("PortableVFX");
            if (p == null || !p.isEnabled()) { service = null; owner = null; return false; }
            if (p == owner) return service != null;
            owner = p; service = null;
            try {
                ClassLoader cl = p.getClass().getClassLoader();
                Class<?> svc = Class.forName("dev.portablevfx.paper.api.PortableVfxService", true, cl);
                Class<?> req = Class.forName("dev.portablevfx.paper.api.EffectRequest", true, cl);
                var reg = Bukkit.getServicesManager().getRegistration(svc);
                if (reg == null) return false;
                request = req.getConstructor(String.class, String.class, double.class, double.class, double.class,
                        float.class, int.class, float.class, float.class, float.class, int.class, float.class, double.class);
                play = svc.getMethod("play", req);
                follow = svc.getMethod("follow", req, UUID.class);
                stop = svc.getMethod("stop", UUID.class);
                handle = play.getReturnType().getMethod("handle");
                service = reg.getProvider();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { plugin.getLogger().warning("그림자 VFX: PortableVFX 연결 실패 " + e); }
            return service != null;
        }
        private Object req(String system, Location at, int ticks, float scale) throws ReflectiveOperationException {
            return request.newInstance("claude:artifactcompanions/" + system, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                    Math.clamp(scale, 0.1f, 4f), Math.clamp(ticks, 1, 12000), at.getYaw(), 0f, 0f, 0xFFFFFF, 1f, 64.0);
        }
        UUID play(String system, Location at, int ticks, float scale) {
            if (at.getWorld() == null || !ready()) return null;
            try { return (UUID) handle.invoke(play.invoke(service, req(system, at, ticks, scale))); }
            catch (ReflectiveOperationException | RuntimeException e) { return null; }
        }
        UUID follow(String system, Entity target, int ticks, float scale) {
            if (!ready()) return null;
            try { return (UUID) handle.invoke(follow.invoke(service, req(system, target.getLocation(), ticks, scale), target.getUniqueId())); }
            catch (ReflectiveOperationException | RuntimeException e) { return null; }
        }
        void stop(UUID h) {
            if (h == null || !ready()) return;
            try { stop.invoke(service, h); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}
