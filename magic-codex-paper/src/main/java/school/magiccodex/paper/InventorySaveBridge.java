package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 인벤세이브 + 사망 시 아공간 배낭 다른 쪽 떨어뜨리기 (오너 2026-10-26).
 *
 * <p><b>인벤세이브</b> (item:inventory_save): 인벤토리(핫바·지금 쪽 27칸·갑옷·왼손)에 있으면 죽어도 인벤토리와 경험치를 그대로
 * 두고 1개만 자동으로 쓴다. 아공간 배낭의 다른 쪽도 그대로. 지금 보이지 않는 배낭 쪽에 넣어 둔 인벤세이브는 세지 않는다.
 *
 * <p><b>인벤세이브 없이 죽으면</b>: 바닐라가 인벤토리(지금 쪽 포함)를 떨어뜨리고, 이어서 아공간 배낭의 다른 쪽(잠금 해제된 쪽 전부,
 * 혹시 남은 잠긴 쪽 포함)도 죽은 자리에 떨어진다 — 공유 DB 에서 먼저 비운(CAS) 뒤에만 떨어뜨림 (BagHuskCoordinator.dropOnDeath).
 * 배낭이 그 순간 동기화 중·쪽 넘기는 중이면 1초마다 최대 {@link #RETRIES}번 다시 해 보고(그동안 쪽 넘기기·정리는 막음), 그래도 안 되면
 * 이번에는 떨어뜨리지 않는다 (아이템은 배낭에 그대로 — 복제·분실 대신 벌칙을 한 번 놓침, 기록은 서버 로그·운영 기록).
 *
 * <p>둘 다 건너뛰는 사망: 경기(큐디치·결투) 중 사망(GeneralPresetBridge.combat — 경기 규칙대로, 인벤세이브 안 씀), keepInventory
 * 가 이미 켜진 사망(게임룰·다른 플러그인 — 인벤세이브 안 씀, 배낭도 그대로), 이 기능을 쓰지 않는 서버(inventory-save.yml servers,
 * 기본 학교·야생 — 아레나 아님). 순서: 이것(HIGH) → EquipmentBridge 장신구 드롭(HIGHEST, keepInventory 면 건너뜀) → 다음 틱 배낭.
 *
 * <p><b>후원 사망</b> (별풍선 10000개, BalloonDonationBridge, 오너 2026-10-26 "무조건 제자리에 드랍, 인벤세이브 안 먹힘"):
 * {@link #forcedDeath} 로 죽이는 동안만 표시가 켜진다. 그 사망은 인벤세이브를 쓰지도 세지도 않고, keepInventory 게임룰이 켜져 있어도
 * 끄고 인벤토리를 그 자리에 떨어뜨리며(LOWEST), 아공간 배낭 다른 쪽도 서버 목록(servers)·drop-bag-pages-on-death 와 상관없이 떨어뜨린다.
 * 단 큐디치·결투 경기 중(GeneralPresetBridge.combat)이면 경기 규칙대로 둔다 (경기용 장비라 떨어뜨리면 복제가 됨).
 *
 * <p>부담: 사망 때만 일한다 (인벤토리 41칸 한 번 훑기 + 배낭은 쪽 넘기기 한 번과 같은 일).
 */
final class InventorySaveBridge implements Listener {
    static final String FILE = "inventory-save.yml";
    /** 1초마다 다시 해 보는 횟수. 사망 귀환(TravelBridge, 2초마다 5번)이 그 안에 끝나도록 8번. */
    static final int RETRIES = 8;

    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final NamespacedKey key;
    private String item = "item:inventory_save", name = "인벤세이브";
    private Set<String> servers = Set.of("school", "wild1", "wild2");
    private boolean bagDrop = true;
    /** 이번 사망에서 인벤세이브를 쓴 사람 (부활하면 알림). */
    private final Map<UUID, Integer> saved = new HashMap<>();
    /** 후원 사망 중인 사람 (메인 스레드, {@link #forcedDeath} 안에서만 들어 있음). */
    private static final Set<UUID> FORCED = new HashSet<>();

    /**
     * 후원 사망: 그 자리에서 죽이고, 그 사망은 인벤세이브·keepInventory 없이 인벤토리·배낭 다른 쪽을 모두 떨어뜨린다. 메인 스레드.
     * 반환: 실제로 죽었는지 (이미 죽어 있음·접속 안 함·사망 이벤트 취소 = false).
     */
    static boolean forcedDeath(Player p) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("server thread required");
        if (p == null || !p.isOnline() || p.isDead()) return false;
        UUID id = p.getUniqueId();
        FORCED.add(id);
        try { p.setHealth(0); }   // 사망 이벤트가 이 안에서 바로 불린다
        finally { FORCED.remove(id); }
        return p.isDead();
    }
    static boolean forced(UUID id) { return FORCED.contains(id); }

    /** 후원 사망: keepInventory(게임룰·다른 플러그인)를 끄고, 비어 있으면 인벤토리를 떨어뜨릴 목록에 채운다. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void forcedDrop(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!forced(p.getUniqueId()) || GeneralPresetBridge.combat(p.getUniqueId()) || !plugin.playerStateReady(p)) return;
        unkeep(e, p);
    }

    /** keepInventory 를 끈다. 켜져 있던 사망은 바닐라가 drops 를 비워 두므로 인벤토리를 채운다 (안 채우면 지워지기만 함). */
    private static void unkeep(PlayerDeathEvent e, Player p) {
        if (e.getKeepLevel()) {   // 경험치도 보통 사망처럼 (바닐라: 레벨×7, 최대 100 을 떨어뜨리고 0 에서 다시)
            e.setKeepLevel(false);
            e.setNewLevel(0); e.setNewExp(0); e.setNewTotalExp(0);
            e.setDroppedExp(Math.min(100, p.getLevel() * 7));
        }
        if (!e.getKeepInventory()) return;   // 보통 사망: 바닐라가 이미 인벤토리를 drops 에 넣었다
        e.setKeepInventory(false);
        if (e.getDrops().isEmpty())
            for (ItemStack it : p.getInventory().getContents())
                if (it != null && !it.getType().isAir() && !it.containsEnchantment(org.bukkit.enchantments.Enchantment.VANISHING_CURSE)) e.getDrops().add(it.clone());
    }

    InventorySaveBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.key = new NamespacedKey(plugin, "inventory_save");
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ChacaApi.reload(plugin, "인벤세이브", FILE + " (인벤세이브 아이템·쓰는 서버·배낭 다른 쪽 떨어뜨리기)", this::reload);
    }

    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        var y = new YamlConfiguration();
        y.load(file);
        item = TravelItemRules.item(y.getString("item", item), "item:inventory_save");
        name = TravelItemRules.name(y.getString("item-name", name), "인벤세이브");
        servers = TravelItemRules.ids(y.isList("servers") ? y.getStringList("servers") : null, Set.of("school", "wild1", "wild2"));
        bagDrop = y.getBoolean("drop-bag-pages-on-death", true);
    }

    private String self() {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); } catch (RuntimeException e) { return ""; }
    }
    /** 서버 ID 를 모르면(travel.yml 없음) 쓰지 않는다 — 아레나일 수 있음. */
    private boolean here() { String s = self(); return !s.isEmpty() && servers.contains(s); }

    private boolean isSave(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        var meta = it.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        return item.equals(itemsAdder.idOf(it));
    }

    // ------------------------------------------------------------------ 인벤세이브 (HIGH: EquipmentBridge HIGHEST 보다 먼저 keepInventory)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void death(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (forced(p.getUniqueId())) {   // 후원 사망: 인벤세이브를 쓰지 않고, 다른 것이 켠 keepInventory 도 끈다 (경기 중 제외)
            if (!GeneralPresetBridge.combat(p.getUniqueId()) && plugin.playerStateReady(p)) unkeep(e, p);
            return;
        }
        if (e.getKeepInventory() || GeneralPresetBridge.combat(p.getUniqueId()) || !here()) return;
        if (!plugin.playerStateReady(p)) return;   // 캐릭터 정보를 불러오기 전: 예전 인벤토리일 수 있어 건드리지 않음
        var inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        int slot = -1, left = 0;
        for (int i = 0; i < all.length; i++) if (isSave(all[i])) { if (slot < 0) slot = i; left += all[i].getAmount(); }
        if (slot < 0) return;
        ItemStack it = all[slot];
        it.setAmount(it.getAmount() - 1);
        inv.setItem(slot, it.getAmount() <= 0 ? null : it);
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        saved.put(p.getUniqueId(), left - 1);
        plugin.getLogger().info(name + " 사용: " + p.getName() + " (남은 " + (left - 1) + "개) " + AuditLog.where(p.getLocation()));
        var detail = new LinkedHashMap<String, String>();
        detail.put("위치", AuditLog.where(p.getLocation()));
        detail.put("남은 개수", String.valueOf(left - 1));
        ChacaApi.audit(p.getUniqueId(), AuditLog.사망, "사망 · " + name + " 1개 사용 (인벤토리·경험치 유지)", detail);
    }

    @EventHandler public void respawn(PlayerRespawnEvent e) {
        Integer left = saved.remove(e.getPlayer().getUniqueId());
        if (left == null) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) CmdKit.notify(p, "system", "inventory-save", name + "이 인벤토리를 지켜 줬어요", "1개 사용 · 남은 " + Math.max(0, left) + "개", 0);
        }, 5);
    }

    // ------------------------------------------------------------------ 배낭 다른 쪽 (인벤세이브 없음)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dropBag(PlayerDeathEvent e) {
        Player p = e.getEntity();
        boolean forced = forced(p.getUniqueId());   // 후원 사망: 서버 목록·설정·keepInventory 와 상관없이 (경기 중만 제외)
        if (!forced && (!bagDrop || e.getKeepInventory())) return;   // 인벤세이브·게임룰·경기(keepInventory) = 배낭도 그대로
        if (GeneralPresetBridge.combat(p.getUniqueId()) || (!forced && !here()) || !plugin.playerStateReady(p)) return;
        var bags = plugin.bagPagesBridge();
        if (bags == null) return;   // 아공간 배낭이 없는 서버
        Location at = p.getLocation().clone();
        UUID id = p.getUniqueId();
        bags.deathPending(id, true);
        // 다음 틱: HuskSync 사망 저장·바닐라 드롭이 끝난 뒤
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryDrop(p, at, 0), 1);
    }

    private void tryDrop(Player p, Location at, int tries) {
        var bags = plugin.bagPagesBridge();
        UUID id = p.getUniqueId();
        if (bags == null) return;
        if (!p.isOnline()) { bags.deathPending(id, false); return; }
        var r = bags.dropOnDeath(p, at);
        if (r == BagHuskCoordinator.DeathDrop.NOT_READY && tries < RETRIES) { Bukkit.getScheduler().runTaskLater(plugin, () -> tryDrop(p, at, tries + 1), 20); return; }
        if (r == BagHuskCoordinator.DeathDrop.STARTED) return;   // 적용이 끝나면 coordinator 가 풂
        bags.deathPending(id, false);
        if (r == BagHuskCoordinator.DeathDrop.NOT_READY || r == BagHuskCoordinator.DeathDrop.FAILED) {
            plugin.getLogger().warning("아공간 배낭 사망 떨어뜨리기 못 함: " + p.getName() + " (" + r + ") — 다른 쪽 아이템은 배낭에 그대로");
            var detail = new LinkedHashMap<String, String>();
            detail.put("위치", AuditLog.where(at));
            detail.put("이유", r == BagHuskCoordinator.DeathDrop.NOT_READY ? "배낭 동기화 중" : "배낭 처리 실패");
            ChacaApi.audit(id, AuditLog.사망, "사망 · 아공간 배낭 다른 쪽 못 떨굼 (배낭에 그대로)", detail);
        }
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        saved.remove(id);
        var bags = plugin.bagPagesBridge();
        if (bags != null) bags.deathPending(id, false);
    }
}
