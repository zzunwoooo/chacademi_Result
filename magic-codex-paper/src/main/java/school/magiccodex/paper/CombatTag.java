package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 전투 표시 + 전투 중 이동 막기 (오픈 전 메모 #91, 오너 2026-10-26 2안). 다른 기능도 쓰는 공용 클래스
 * ({@link MagicCodexBridge#combatTag()}).
 *
 * <p>전투 = 살아있는 상대(야생 몹·플레이어 — NPC·펫·허수아비·갑옷 거치대 제외)를 때렸거나 그 상대에게 맞은 뒤
 * combat-lock.yml seconds 초 안. 근접·화살 같은 투사체·마법(MagicMythic 피해는 MagicProtection 이
 * target.damage(양, 시전자) 로 넣어서 같은 EntityDamageByEntityEvent 로 들어옴)·펫 스킬(동행 펫 스킬은 주인이 시전자)·
 * 길들인 동물의 공격(주인)을 모두 센다. 취소된 피해(보호 구역·같은 파티)는 세지 않는다. 죽으면 바로 풀린다.
 *
 * <p>부담: 매 틱 일 없음. 피해 이벤트에서 시각 하나만 적고, 이동을 시도할 때만 비교한다. 메인 스레드 전용.
 *
 * <p>막는 것: 귀환권 · 지도 이동(공개 지점·개인 지점·야생 스폰) · 설정한 명령(HuskHomes /home /spawn /warp /tpa /back …,
 * 한글 별칭) · 설정한 마법(동행 귀환). 관리자(OP 또는 magiccodex.combatlock.bypass)는 막지 않는다.
 * 막혀도 아이템·마나·쿨타임은 쓰지 않는다 (모두 쓰기 전에 검사).
 */
public final class CombatTag implements Listener {
    static final String FILE = "combat-lock.yml";
    static final String BYPASS = "magiccodex.combatlock.bypass";
    static final String NOTIFY_KEY = "combat-lock";
    private static final String DUMMY_TAG = "chacademia_training_dummy", PET_TAG = "chacademia_pet", NPC_TAG = "magiccodex_npc";

    private final MagicCodexBridge plugin;
    private final File file;
    private CombatLockRules.Settings settings = CombatLockRules.Settings.DEFAULT;
    /** 마지막으로 때리거나 맞은 시각 (ms). 오래된 것은 기록할 때 가끔 지운다. */
    private final Map<UUID, Long> lastAt = new HashMap<>();

    CombatTag(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (ChacaApi.ready()) ChacaApi.reload(plugin, "전투이동", FILE + " (전투 중 이동 막기: 초·막을 명령·서버)", this::reload);
    }

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        var d = CombatLockRules.Settings.DEFAULT;
        settings = CombatLockRules.settings(y.getBoolean("enabled", true), y.getDouble("seconds", d.windowMs() / 1000.0),
                y.isList("servers") ? y.getStringList("servers") : d.servers(),
                y.isList("blocked-commands") ? y.getStringList("blocked-commands") : d.commands(),
                y.isList("blocked-spells") ? y.getStringList("blocked-spells") : d.spells(),
                y.getLong("notify-cooldown-ms", d.notifyCooldownMs()));
        plugin.getLogger().info("전투 중 이동 막기: " + (settings.enabled() ? "켜짐" : "꺼짐") + " · " + settings.windowMs() / 1000 + "초 · 명령 "
                + settings.commands().size() + "개 · 마법 " + settings.spells().size() + "개 · 서버 " + (settings.servers().isEmpty() ? "전체" : settings.servers()));
    }

    // ------------------------------------------------------------------ 공용 API

    /** 마지막 전투 시각 (ms, 없으면 -1). */
    public long lastCombatAt(UUID player) { return lastAt.getOrDefault(player, -1L); }
    /** 전투가 끝나기까지 남은 밀리초 (전투 중 아니면 0). 켜기/끄기·서버 설정과 무관한 순수 전투 판정. */
    public long remainingMillis(UUID player) { return CombatLockRules.remaining(lastCombatAt(player), System.currentTimeMillis(), settings.windowMs()); }
    public boolean inCombat(UUID player) { return remainingMillis(player) > 0; }
    public boolean inCombat(Player player) { return inCombat(player.getUniqueId()); }

    /** 이 사람의 순간이동류 이동을 지금 막아야 하면 남은 밀리초, 아니면 0. */
    public long lockedMillis(Player p) {
        if (!settings.appliesTo(serverId()) || p.isOp() || p.hasPermission(BYPASS)) return 0;
        return remainingMillis(p.getUniqueId());
    }

    /** 막아야 하면 알림창에 알리고(같은 알림 도배 방지) 그 문구를 돌려준다. 막지 않으면 null. */
    public String refuse(Player p) {
        long left = lockedMillis(p);
        if (left <= 0) return null;
        String msg = CombatLockRules.message(left);
        CmdKit.notify(p, "warning", NOTIFY_KEY, msg, "", settings.notifyCooldownMs());
        return msg;
    }

    private String serverId() {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); }
        catch (RuntimeException e) { return ""; }
    }

    // ------------------------------------------------------------------ 전투 기록

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        Entity from = attacker(e.getDamager()), to = e.getEntity();
        if (from == null || from.equals(to)) return;
        if (to instanceof Player victim && opponent(from)) mark(victim);
        if (from instanceof Player attacker && opponent(to)) mark(attacker);
    }

    /** 실제로 때린 쪽: 투사체 → 쏜 것, 길들인 동물 → 주인, 잔류 물약 구름 → 던진 것, TNT → 불붙인 것. */
    static Entity attacker(Entity damager) {
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) return shooter;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof Entity source) return source;
        if (damager instanceof TNTPrimed t && t.getSource() != null) return t.getSource();
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player owner && owner.isOnline()) return owner;
        return damager;
    }

    /** 전투 상대로 치는지: 플레이어 또는 몹. NPC·펫(길들인 동물 포함)·허수아비·주민·갑옷 거치대는 아님. */
    static boolean opponent(Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof ArmorStand || e instanceof AbstractVillager) return false;
        if (!(e instanceof Player) && !(e instanceof Mob)) return false;
        if (e.hasMetadata("NPC")) return false;
        var tags = e.getScoreboardTags();
        if (tags.contains(DUMMY_TAG) || tags.contains(PET_TAG) || tags.contains(NPC_TAG)) return false;
        return !(e instanceof Tameable t && t.isTamed());
    }

    private void mark(Player p) {
        long now = System.currentTimeMillis();
        lastAt.put(p.getUniqueId(), now);
        if (lastAt.size() > 512) lastAt.values().removeIf(at -> CombatLockRules.remaining(at, now, settings.windowMs()) <= 0);
    }

    /** 죽으면 전투 끝 (리스폰·사망 귀환은 막지 않음). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) { lastAt.remove(e.getEntity().getUniqueId()); }

    /** 나가면 지난 기록만 정리 (전투 중에 나갔다 들어와 피하는 건 막으려고 남은 기록은 둔다). */
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!inCombat(id)) lastAt.remove(id);
    }

    // ------------------------------------------------------------------ 막기: 명령 · 마법

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        if (settings.commands().isEmpty()) return;
        String label = CombatLockRules.label(e.getMessage());
        if (label == null || !settings.commands().contains(label)) return;
        if (refuse(e.getPlayer()) != null) e.setCancelled(true);
    }

    /** 동행 귀환 같은 먼 거리 귀환 마법: 마나·쿨타임을 쓰기 전에 취소된다 (ManaBridge 가 시전 검사 전에 부름). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void cast(MagicCastAttemptEvent e) {
        String spell = e.getSpellId();
        if (spell == null || !settings.spells().contains(spell.toLowerCase(Locale.ROOT))) return;
        if (refuse(e.getPlayer()) != null) e.setCancelled(true);   // 문구는 위 알림창으로 (setMessage 없음 → 중복 알림 없음)
    }
}
