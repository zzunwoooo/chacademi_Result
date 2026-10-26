package school.magiccodex.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.BagProtocol;

/**
 * 아공간 배낭 pages (2026-10-24, owner: "배낭 잠금 해제권을 사용해서 페이지 해금 … 다음 페이지를 열면 디비에 저장된
 * 아이템이 나오게, 핫바는 그대로").
 *
 * <p>Page 1 is open for everyone; each 가방 확장권 (ItemsAdder id in bag-pages.yml) opens the next page, up to 3. The
 * unlock count is global. Inactive pages use a UUID-global, lease/CAS-guarded shared row. Active/seq and
 * the inventory block are captured together through the version-pinned HuskSync LOCKSTEP extension.
 * A durable local WAL is recovery evidence only; it cannot replay itself over central ownership.
 * Shared schema/bootstrap is an explicit reviewed migration, never an automatic local-inventory fallback.
 */
final class BagPagesBridge implements PluginMessageListener, Listener {
    static final int PAGES = BagProtocol.PAGES, FIRST_SLOT = 9;
    private static final long SWITCH_GAP_MS = 700;

    static final class Entry {
        boolean loaded, loading;
        int unlocked = 1, active = 1;
        long seq;
        byte[][] pages = {new byte[0], new byte[0], new byte[0]};
        final List<Runnable> waiting = new ArrayList<>();
    }

    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final NamespacedKey unlockKey;
    private final NamespacedKey commitKey;
    private final DatabaseSettings settings;
    private final java.nio.file.Path sqlite;
    private final BagHuskCoordinator coordinator;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "MagicCodex-bag-pages"); t.setDaemon(true); return t; });
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Long> lastSwitch = new HashMap<>();
    private final Set<UUID> unlocking = new HashSet<>();
    private Connection connection;
    private BagPagesSql storage;
    private boolean allowCleanBootstrap;
    private String serverId = "school", unlockItem = "item:bag_expansion", unlockName = "가방 확장권";

    BagPagesBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.unlockKey = new NamespacedKey(plugin, "bag_unlock");
        this.commitKey = new NamespacedKey(plugin, "bag_page_commit");
        loadConfig();
        var folder = plugin.getDataFolder().toPath();
        var dedicated = folder.resolve("player-state-database.properties");
        settings = DatabaseSettings.load(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"));
        sqlite = folder.resolve("bag-pages.db");
        var huskPlugin = plugin.getServer().getPluginManager().getPlugin("HuskSync");
        if (huskPlugin == null || !huskPlugin.isEnabled()) throw new IllegalStateException("Shared bag is unavailable until HuskSync is enabled; no local-inventory fallback");
        coordinator = new BagHuskCoordinator(plugin, this, settings, folder, allowCleanBootstrap);
        coordinator.install();
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, BagProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BagProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) load(p.getUniqueId(), () -> send(p));
        CmdKit.register(plugin, "/유저관리 배낭", this::commands);
        if (!settings.mariaDb()) plugin.getLogger().warning("아공간 배낭 쪽 저장이 SQLite(서버 로컬, bag-pages.db)입니다. 확장권 해금 수는 서버끼리 공유되지 않습니다.");
    }

    private void loadConfig() throws Exception {
        var file = new File(plugin.getDataFolder(), "bag-pages.yml");
        if (!file.exists()) plugin.saveResource("bag-pages.yml", false);
        var y = new YamlConfiguration();
        y.load(file);
        String sid = y.getString("server-id", "").trim();
        if (sid.isEmpty()) {
            // Same name the quest board uses for this server (quests.yml server-id), so one setting covers both.
            var quests = new File(plugin.getDataFolder(), "quests.yml");
            if (quests.exists()) { var q = new YamlConfiguration(); q.load(quests); sid = q.getString("server-id", "school").trim(); }
            else sid = "school";
        }
        if (!sid.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("bag-pages.yml server-id (영문 소문자·숫자·_- 1~48자)");
        serverId = sid;
        allowCleanBootstrap = y.getBoolean("allow-clean-bootstrap", false);
        unlockItem = y.getString("unlock-item", unlockItem).trim();
        unlockName = y.getString("unlock-item-name", unlockName).trim();
    }

    void close() {
        coordinator.beforeBridgeDisable();
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        entries.clear();
    }

    // ------------------------------------------------------------------ database (io thread only)

    private BagPagesSql db() throws Exception {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            connection = settings.connect(sqlite);
            settings.configure(connection);
            storage = new BagPagesSql(connection, settings.mariaDb());
            try { storage.init(); } catch (Exception e) { connection.close(); connection = null; storage = null; throw e; }
        }
        return storage;
    }

    private void failure(String action, Exception e) { plugin.getLogger().severe("BAG PAGES " + action + " failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"); }

    private void main(Runnable r) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r); }

    // ------------------------------------------------------------------ state

    Entry entry(UUID id) { return entries.computeIfAbsent(id, k -> new Entry()); }

    private void load(UUID id, Runnable done) {
        coordinator.requestStatus(id);
        if(done!=null)done.run();
    }
    void sharedBlocked(UUID id) { var e=entry(id);e.loaded=false;var p=Bukkit.getPlayer(id);if(p!=null)send(p); }
    void sharedReady(UUID id,BagSharedSql.Row row,int unlocked) {var e=entry(id);e.active=row.active();e.seq=row.sequence();e.pages=row.pages();e.unlocked=unlocked;e.loaded=true;var p=Bukkit.getPlayer(id);if(p!=null)send(p);}
    int sharedUnlocked(UUID id) {var e=entries.get(id);return e==null?1:e.unlocked;}

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(BagProtocol.RESPONSE); }

    private void send(Player p) {
        if (!p.isOnline() || !modded(p)) return;
        var e = entries.get(p.getUniqueId());
        int unlocked = e == null ? 1 : Math.max(e.unlocked, e.active);
        try {
            p.sendPluginMessage(plugin, BagProtocol.RESPONSE, BagProtocol.encode(new BagProtocol.State(
                    Math.clamp(unlocked, 1, PAGES), e == null ? 1 : e.active, e != null && e.loaded && coordinator.inputReady(p.getUniqueId()), "")));
        } catch (IllegalArgumentException ex) { plugin.getLogger().warning("아공간 배낭 패킷 실패: " + ex.getMessage()); }
    }

    @EventHandler public void join(PlayerJoinEvent event) { var p = event.getPlayer(); load(p.getUniqueId(), () -> send(p)); }

    @EventHandler public void register(PlayerRegisterChannelEvent event) { if (BagProtocol.RESPONSE.equals(event.getChannel())) send(event.getPlayer()); }

    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        entries.remove(id); lastSwitch.remove(id); unlocking.remove(id);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!BagProtocol.REQUEST.equals(channel)) return;
        BagProtocol.Request r;
        try { r = BagProtocol.decodeRequest(bytes); } catch (IllegalArgumentException e) { return; }
        var e = entry(p.getUniqueId());
        // HELLO (인벤 열기, #101 2026-10-26): memory only — entries + coordinator holders, never SQL/HuskSync on this
        // thread. Pages/unlocks are read off-thread at join/sync (BagHuskCoordinator.verifyIncoming/complete) and after
        // each switch. Keep it that way; SWITCH below keeps its WAL/lease/CAS path unchanged.
        if (r.action() == BagProtocol.HELLO) { if (!e.loaded && !e.loading) load(p.getUniqueId(), () -> send(p)); else send(p); return; }
        long now = System.currentTimeMillis();
        if (now - lastSwitch.getOrDefault(p.getUniqueId(), 0L) < SWITCH_GAP_MS) { send(p); return; }
        lastSwitch.put(p.getUniqueId(), now);
        String refused = switchTo(p, r.page());
        if (refused != null) CmdKit.notify(p, "warning", "bag-page", "배낭 쪽을 넘기지 못했어요", refused, 1500);
        send(p);
    }

    /** Swaps the 27 main slots with the stored page. Null on success, otherwise the reason for the player. */
    String switchTo(Player p, int target) {
        var e = entries.get(p.getUniqueId());
        if (e == null || !e.loaded) return "배낭 정보를 읽는 중이에요. 잠시 뒤 다시 해 주세요";
        if (target < 1 || target > PAGES || target == e.active) return null;
        if (target > e.unlocked) return "잠긴 공간이에요. " + unlockName + "으로 열 수 있어요";
        if (p.isDead()) return "지금은 넘길 수 없어요";
        var top = p.getOpenInventory().getTopInventory().getType();
        if (top != InventoryType.CRAFTING && top != InventoryType.CREATIVE) return "다른 창을 닫고 다시 해 주세요";
        return coordinator.switchPage(p, target);
    }

    /**
     * 382 배낭 정리 (LifeSpells): sorts and merges every unlocked page in one shared step (BagHuskCoordinator.sortPages,
     * the same WAL / lock / CAS / player-marker path as a page switch). check = only test (nothing changes).
     * Null = sorted (or, with check, sortable); otherwise the reason for the player.
     */
    String sortPages(Player p, boolean check) {
        var e = entries.get(p.getUniqueId());
        if (e == null || !e.loaded) return "배낭 정보를 읽는 중이에요. 잠시 뒤 다시 해 주세요";
        if (p.isDead()) return "지금은 정리할 수 없어요";
        var top = p.getOpenInventory().getTopInventory().getType();
        if (top != InventoryType.CRAFTING && top != InventoryType.CREATIVE) return "다른 창을 닫고 다시 해 주세요";
        if (p.getItemOnCursor() != null && !p.getItemOnCursor().getType().isAir()) return "들고 있는 아이템을 내려놓고 다시 해 주세요";
        if (unlocking.contains(p.getUniqueId())) return "배낭 공간을 여는 중이에요. 잠시 뒤 다시 해 주세요";
        long now = System.currentTimeMillis();
        if (now - lastSwitch.getOrDefault(p.getUniqueId(), 0L) < SWITCH_GAP_MS) return "배낭 쪽을 넘기는 중이에요. 잠시 뒤 다시 해 주세요";
        int unlocked = Math.clamp(e.unlocked, 1, PAGES);
        if (e.active > unlocked) return "잠긴 쪽을 보고 있어서 정리할 수 없어요";
        String refused = coordinator.sortPages(p, unlocked, check);
        if (refused == null && !check) lastSwitch.put(p.getUniqueId(), now);
        return refused;
    }

    /** 27 slots → page bytes (null / air = empty slot). */
    static byte[] encode(ItemStack[] items) {
        var slots = new ArrayList<byte[]>(BagPageCodec.SLOTS);
        for (int i = 0; i < BagPageCodec.SLOTS; i++) {
            ItemStack it = i < items.length ? items[i] : null;
            slots.add(it == null || it.getType().isAir() || it.getAmount() <= 0 ? new byte[0] : it.serializeAsBytes());
        }
        return BagPageCodec.encodePage(slots);
    }

    static byte[] page(org.bukkit.inventory.PlayerInventory inv, int first) {
        var slots = new ArrayList<byte[]>(BagPageCodec.SLOTS);
        for (int i = 0; i < BagPageCodec.SLOTS; i++) {
            ItemStack it = inv.getItem(first + i);
            slots.add(it == null || it.getType().isAir() || it.getAmount() <= 0 ? new byte[0] : it.serializeAsBytes());
        }
        return BagPageCodec.encodePage(slots);
    }

    static ItemStack[] items(byte[] page) {
        var slots = BagPageCodec.decodePage(page);
        var out = new ItemStack[BagPageCodec.SLOTS];
        for (int i = 0; i < out.length; i++) out[i] = slots.get(i).length == 0 ? null : ItemStack.deserializeBytes(slots.get(i));
        return out;
    }

    // ------------------------------------------------------------------ 가방 확장권

    private boolean isUnlockItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        var meta = item.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(unlockKey, PersistentDataType.BYTE)) return true;
        return !unlockItem.isEmpty() && unlockItem.equals(itemsAdder.idOf(item));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItem(hand);
        if (!isUnlockItem(item)) return;
        event.setCancelled(true);
        UUID id = p.getUniqueId();
        var e = entries.get(id);
        if (e == null || !e.loaded) { CmdKit.notify(p, "warning", "bag-unlock", "배낭 정보를 읽는 중이에요", "잠시 뒤 다시 사용해 주세요", 1500); return; }
        if (e.unlocked >= PAGES) { CmdKit.notify(p, "warning", "bag-unlock", "모든 공간이 이미 열려 있어요", "아공간 배낭은 " + PAGES + "쪽까지예요", 1500); return; }
        if (!unlocking.add(id)) return;
        // Take the ticket first; a failed database write gives it back.
        ItemStack one = item.clone(); one.setAmount(1);
        item.setAmount(item.getAmount() - 1);
        p.getInventory().setItem(hand, item.getAmount() <= 0 ? null : item);
        // 2026-10-25 (#101): the taken ticket is on disk before the shared unlock is written — a crash after the DB
        // commit can no longer leave the ticket in the saved inventory (one ticket = one page, never two).
        try { p.saveData(); }
        catch (RuntimeException ex) {
            failure("save before unlock; database unlock cancelled", ex);
            unlocking.remove(id);
            for (var left : p.getInventory().addItem(one).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            CmdKit.notify(p, "error", "bag-unlock", "공간을 열지 못했어요", "확장권을 돌려 드렸어요. 잠시 뒤 다시 해 주세요", 0);
            return;
        }
        io.execute(() -> {
            int pages = -2;
            try { pages = db().unlockOne(id, PAGES); } catch (Exception ex) { failure("unlock", ex); }
            int result = pages;
            main(() -> {
                unlocking.remove(id);
                var online = Bukkit.getPlayer(id);
                if (result >= 1) {
                    var cur = entries.get(id);
                    if (cur != null) cur.unlocked = result;
                    if (online != null) {
                        CmdKit.notify(online, "shop", "bag-unlock", "아공간 배낭 " + result + "쪽이 열렸어요", "배낭 아래 화살표로 넘겨 보세요", 0);
                        online.playSound(online.getLocation(), org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
                        send(online);
                    }
                    return;
                }
                // Not used: all pages were already open (another server) or the database failed.
                if (online != null) {
                    for (var left : online.getInventory().addItem(one).values()) online.getWorld().dropItemNaturally(online.getLocation(), left);
                    if (result == -1) { var cur = entries.get(id); if (cur != null) cur.unlocked = PAGES; send(online); }
                    CmdKit.notify(online, result == -1 ? "warning" : "error", "bag-unlock",
                            result == -1 ? "모든 공간이 이미 열려 있어요" : "공간을 열지 못했어요",
                            result == -1 ? unlockName + "을 돌려 드렸어요" : unlockName + "을 돌려 드렸어요. 잠시 뒤 다시 해 주세요", 0);
                } else mailBack(id, one);
            });
        });
    }

    /** The player left before the unlock answered and it was not used: the ticket goes back by mail (one mail per use). */
    private void mailBack(UUID id, ItemStack one) {
        MailService mail = Bukkit.getServicesManager().load(MailService.class);
        String what = unlockItem + " x1";
        if (mail == null) { plugin.getLogger().severe("BAG PAGES unlock not used for offline " + id + "; mail unavailable, ticket not returned (" + what + ")"); return; }
        try {
            mail.sendSystem("bag-unlock-refund:" + id + ":" + UUID.randomUUID(), id, unlockName + " 반환", "공간을 열지 못해 " + unlockName + "을 돌려드려요.", List.of(one))
                    .whenComplete((mailId, error) -> {
                        if (error != null) plugin.getLogger().severe("BAG PAGES refund mail failed for " + id + " (" + what + "): " + error);
                        else plugin.getLogger().info("BAG PAGES unlock not used for offline " + id + "; ticket mailed back (" + mailId + ")");
                    });
        } catch (RuntimeException ex) { plugin.getLogger().severe("BAG PAGES refund mail failed for " + id + " (" + what + "): " + ex); }
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 배낭

    private void commands() {
        ChacaApi.branch(plugin, ChacaApi.유저관리, Commands.literal("배낭")
                .then(Commands.literal("보기").executes(CmdKit.run(ctx -> {
                    var p = ChacaApi.targetOnline(ctx);
                    var e = entries.get(p.getUniqueId());
                    if (e == null || !e.loaded) throw ChacaApi.error("배낭 정보를 읽는 중이에요. 잠시 뒤 다시 해 주세요");
                    var lines = new ArrayList<String>();
                    lines.add("── " + ChacaApi.name(p.getUniqueId()) + " 님의 아공간 배낭 (서버 " + serverId + ") ──");
                    lines.add(" 열린 쪽: " + e.unlocked + " / " + PAGES + " · 지금 인벤토리에 있는 쪽: " + e.active);
                    for (int i = 1; i <= PAGES; i++) {
                        if (i == e.active) { lines.add(" " + i + "쪽: (인벤토리에 있음)"); continue; }
                        String count;
                        try { count = BagPageCodec.itemCount(e.pages[i - 1]) + "칸 사용"; } catch (RuntimeException ex) { count = "읽을 수 없음"; }
                        lines.add(" " + i + "쪽: " + count + (i > e.unlocked ? " · 잠김" : ""));
                    }
                    ChacaApi.lines(CodexCommands.sender(ctx), lines);
                })))
                .then(Commands.literal("해금").then(Commands.argument("쪽수", IntegerArgumentType.integer(1, PAGES)).executes(CmdKit.run(ctx -> {
                    UUID id = ChacaApi.target(ctx);
                    int pages = IntegerArgumentType.getInteger(ctx, "쪽수");
                    var sender = CodexCommands.sender(ctx);
                    io.execute(() -> {
                        Exception error = null;
                        try { db().setUnlocked(id, pages); } catch (Exception ex) { error = ex; }
                        var err = error;
                        main(() -> {
                            if (err != null) { ChacaApi.fail(sender, "배낭 해금 수를 바꾸지 못했어요", err); return; }
                            var e = entries.get(id);
                            if (e != null) e.unlocked = pages;
                            var p = Bukkit.getPlayer(id);
                            if (p != null) send(p);
                            ChacaApi.ok(sender, ChacaApi.name(id) + " 님의 아공간 배낭을 " + pages + "쪽까지 열었어요"
                                    + (e != null && e.active > pages ? " (지금 " + e.active + "쪽을 보고 있어서 앞쪽으로만 넘길 수 있어요)" : ""));
                        });
                    });
                })))), "아공간 배낭 열린 쪽 보기·해금 수 설정", "배낭 보기");
    }
}
