package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.TeleportPickProtocol;

/**
 * 리턴권·텔포권 (오너 2026-10-26).
 *
 * <ul>
 *   <li><b>리턴권</b> (item:death_return): 우클릭 → 야생 서버(travel-items.yml death-servers)에서 가장 최근에 죽은 자리로.
 *       학교·야생 어디서 써도 된다 (다른 서버면 서버를 옮긴 뒤 그 자리). 사망 자리는 공유 DB codex_travel_death
 *       (플레이어마다 한 줄: 서버·세계·세계 UID·xyz·시각), 아레나 사망·경기 중 사망은 적지 않는다. 기록이 없으면 거절(소모 없음).
 *       세계가 초기화됐으면(세계 UID 가 다름) 거절. 쓴 뒤에도 기록은 남는다 (다음에 죽으면 바뀜).</li>
 *   <li><b>텔포권</b> (item:friend_teleport): 우클릭 → 지금 접속 중인 학교·야생 유저 목록 창 (공유 DB codex_travel_presence,
 *       서버마다 한 줄을 15초마다·들고 날 때 씀; 아레나 접속자는 아예 없음) → 고르면 그 유저 위치로 (다른 서버면 서버 이동 후,
 *       도착 서버가 그 순간 그 유저의 위치를 읽음).</li>
 * </ul>
 * 둘 다 아레나(use-servers 에 없는 서버)에서는 못 쓰고, 전투 중(메모 #91 CombatTag)·경기 중·관전·사망·캐릭터 불러오는 중·
 * travel.yml 이동 쿨타임이면 아이템을 쓰기 전에 막는다. 이동은 귀환권과 같은 지도 이동 길(TravelBridge.travelItem).
 *
 * <p>소모 순서 (귀환권과 같음): 이동을 시작하기 직전에 1개를 빼고 플레이어 파일을 저장한 뒤 이동을 시작한다. 이동이 실패하면
 * (출발 서버에서 거절·프록시 실패·도착 서버에서 실패·시간 초과) 정확히 한 번 돌려준다 (공통 지급: 넣을 칸 → 남으면 우편).
 * "성공한 뒤에 뺀다"로 하지 않은 이유: 다른 서버로 옮겨 가면 출발 서버의 인벤토리는 이미 저장·인계된 뒤라 거기서 뺄 수 없고,
 * 도착 서버에서 빼려면 그 사이 아이템을 옮겨 두는 것으로 공짜 이동(복제)이 된다. 먼저 빼고 실패 때 돌려주면 항상 1개만 쓴다.
 *
 * <p>부담: 매 틱 일 없음. 우클릭·고르기·사망 때만 일하고, DB 는 IO 스레드(TravelStore)에서만 읽고 쓴다.
 */
final class TravelItemsBridge implements Listener, PluginMessageListener, TravelBridge.ItemMoves {
    static final String FILE = "travel-items.yml";
    private static final long PICK_TIMEOUT_MS = 60_000;

    private final MagicCodexBridge plugin;
    private final TravelBridge travel;
    private final ItemsAdderItems itemsAdder;
    private final NamespacedKey deathKey, friendKey;
    private TravelItemRules.Settings settings = TravelItemRules.Settings.DEFAULT;
    /** 이동을 시작하며 뺀 아이템 (그 이동이 실패하면 그대로 돌려줌). */
    private final Map<UUID, ItemStack> taken = new HashMap<>();
    /** 우클릭 → DB 답을 기다리는 중 (두 번 누르기 막기). */
    private final Set<UUID> asking = new HashSet<>();
    private final Map<UUID, Long> clickGate = new HashMap<>();
    /** 텔포권 고르기 창: 토큰·어느 손·보낸 후보. */
    private record Pending(long token, EquipmentSlot hand, int slot, Map<UUID, TravelItemRules.Candidate> candidates, long expiresAt) {}
    private final Map<UUID, Pending> pending = new HashMap<>();

    TravelItemsBridge(MagicCodexBridge plugin, TravelBridge travel) throws Exception {
        this.plugin = plugin;
        this.travel = travel;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.deathKey = new NamespacedKey(plugin, "death_return");
        this.friendKey = new NamespacedKey(plugin, "friend_teleport");
        reload();
        travel.itemMoves(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, TeleportPickProtocol.REQUEST, this);
        messenger.registerOutgoingPluginChannel(plugin, TeleportPickProtocol.RESPONSE);
        ChacaApi.reload(plugin, "리턴권", FILE + " (리턴권·텔포권 아이템·쓸 수 있는 서버·사망 기록 서버·목록 서버)", this::reload);
    }

    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        var y = new YamlConfiguration();
        y.load(file);
        var d = TravelItemRules.Settings.DEFAULT;
        settings = TravelItemRules.settings(y.getString("death-return.item", d.deathItem()), y.getString("death-return.item-name", d.deathName()),
                y.getString("friend-teleport.item", d.friendItem()), y.getString("friend-teleport.item-name", d.friendName()),
                y.isList("use-servers") ? y.getStringList("use-servers") : null,
                y.isList("death-servers") ? y.getStringList("death-servers") : null,
                y.isList("list-servers") ? y.getStringList("list-servers") : null);
    }

    private String self() {
        try { return travel.config() == null ? "" : travel.config().self(); } catch (RuntimeException e) { return ""; }
    }

    // ------------------------------------------------------------------ 아이템 판정

    private boolean is(ItemStack it, NamespacedKey key, String id) {
        if (it == null || it.getType().isAir()) return false;
        var meta = it.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        return id.equals(itemsAdder.idOf(it));
    }
    private boolean isDeath(ItemStack it) { return is(it, deathKey, settings.deathItem()); }
    private boolean isFriend(ItemStack it) { return is(it, friendKey, settings.friendItem()); }

    // ------------------------------------------------------------------ 사망 자리 기록 (리턴권)

    /** 야생에서 죽은 자리 (경기 중 사망·아레나 사망은 무시). 취소된 사망은 MONITOR 에 안 옴. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void died(PlayerDeathEvent e) {
        Player p = e.getEntity();
        String server = self();
        if (!settings.recordsDeathOn(server) || GeneralPresetBridge.combat(p.getUniqueId()) || !travel.storeReady()) return;
        var at = p.getLocation();
        var w = at.getWorld();
        if (w == null) return;
        int highest;
        try { highest = at.getY() < w.getMinHeight() ? w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) : at.getBlockY(); }   // 죽은 열 = 이미 불린 청크
        catch (RuntimeException ex) { highest = w.getMinHeight() - 1; }
        double y = TravelItemRules.safeY(at.getY(), w.getMinHeight(), w.getMaxHeight(), highest);
        if (Double.isNaN(y)) return;   // 디딜 곳 없는 공허 열: 이전 기록 유지
        var point = new TravelItemRules.DeathPoint(server, w.getName(), w.getUID().toString(), at.getX(), y, at.getZ(), at.getYaw(), at.getPitch(), System.currentTimeMillis());
        UUID id = p.getUniqueId();
        travel.saveDeath(id, point, ok -> { if (!ok) plugin.getLogger().warning("리턴권: 사망 자리 저장 실패 " + id); });
    }

    // ------------------------------------------------------------------ 우클릭

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItem(hand);
        boolean death = isDeath(held), friend = !death && isFriend(held);
        if (!death && !friend) return;
        e.setCancelled(true);
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = clickGate.put(id, now);
        if (last != null && now - last < 600) return;
        String name = death ? settings.deathName() : settings.friendName();
        if (!settings.usableOn(self())) { warn(p, "여기서는 " + name + "을 쓸 수 없어요", "학교·야생에서만 쓸 수 있어요"); return; }
        if (taken.containsKey(id) || asking.contains(id)) { warn(p, "이미 이동하는 중이에요", ""); return; }
        if (!travel.storeReady()) { warn(p, "지금은 " + name + "을 쓸 수 없어요", "공유 저장소 연결이 없어요"); return; }
        var combat = plugin.combatTag();
        if (combat != null && combat.refuse(p) != null) return;   // 메모 #91: 전투 중 — 아이템을 쓰기 전에 막음 (알림은 CombatTag)
        int slot = hand == EquipmentSlot.HAND ? p.getInventory().getHeldItemSlot() : -1;
        if (death) deathReturn(p, hand, slot);
        else friendList(p, hand, slot);
    }

    private void deathReturn(Player p, EquipmentSlot hand, int slot) {
        UUID id = p.getUniqueId();
        asking.add(id);
        travel.death(id, found -> {
            asking.remove(id);
            if (!p.isOnline()) return;
            if (found == null) { warn(p, "지금은 " + settings.deathName() + "을 쓸 수 없어요", "쓰러진 기록을 읽지 못했어요. 잠시 뒤 다시 해 주세요"); return; }
            if (found.isEmpty()) { warn(p, "야생에서 쓰러진 기록이 없어요", settings.deathName() + "은 그대로예요"); return; }
            var d = found.get();
            if (!stillHeld(p, hand, slot, true)) return;
            start(p, hand, TravelBridge.DEATH_ID, d.server(), d.world(), d.worldUid(), d.x(), d.y(), d.z(), d.yaw(), d.pitch(), settings.deathName(),
                    "마지막으로 쓰러진 자리 · " + d.server() + "/" + d.world() + " " + Math.round(d.x()) + "," + Math.round(d.y()) + "," + Math.round(d.z()));
        });
    }

    /** 아이템이 아직 그 손(주 손이면 같은 칸)에 있는지. */
    private boolean stillHeld(Player p, EquipmentSlot hand, int slot, boolean death) {
        var inv = p.getInventory();
        if (hand == EquipmentSlot.HAND && inv.getHeldItemSlot() != slot) { warn(p, "아이템을 손에 든 채로 써 주세요", ""); return false; }
        ItemStack it = inv.getItem(hand);
        if (death ? !isDeath(it) : !isFriend(it)) { warn(p, "아이템을 손에 든 채로 써 주세요", ""); return false; }
        return true;
    }

    /** 1개 빼고(저장) 이동 시작. 바로 거절되면 그대로 돌려준다. */
    private void start(Player p, EquipmentSlot hand, String point, String server, String world, String worldUid, double x, double y, double z, float yaw, float pitch,
                       String name, String detail) {
        UUID id = p.getUniqueId();
        var combat = plugin.combatTag();
        if (combat != null && combat.refuse(p) != null) return;   // DB 답을 기다리는 사이 전투가 시작됐을 수 있음
        ItemStack held = p.getInventory().getItem(hand);
        ItemStack one = held.clone(); one.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
        taken.put(id, one);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning(name + ": 사용 전 저장 실패 " + p.getName() + " (" + e + ")"); }
        String refusal = travel.travelItem(p, point, server, world, worldUid, x, y, z, yaw, pitch);
        if (refusal != null) {
            if (taken.remove(id) != null) give(p, one);
            warn(p, "지금은 " + name + "을 쓸 수 없어요", refusal);
            return;
        }
        plugin.getLogger().info(name + " 사용: " + p.getName() + " → " + server + "/" + world + " (" + point + ")");
        var info = new LinkedHashMap<String, String>();
        info.put("도착", detail);
        info.put("출발", self() + " " + AuditLog.where(p.getLocation()));
        ChacaApi.audit(id, AuditLog.접속, name + " 사용 · " + detail, info);
    }

    // ------------------------------------------------------------------ 텔포권 목록 창

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(TeleportPickProtocol.RESPONSE); }

    private void friendList(Player p, EquipmentSlot hand, int slot) {
        if (!modded(p)) { warn(p, settings.friendName() + "은 차카데미 런처로 접속했을 때 쓸 수 있어요", ""); return; }
        UUID id = p.getUniqueId();
        asking.add(id);
        travel.presenceAll(rows -> {
            asking.remove(id);
            if (!p.isOnline()) return;
            if (rows == null) { warn(p, "지금은 " + settings.friendName() + "을 쓸 수 없어요", "접속자 목록을 읽지 못했어요"); return; }
            long now = System.currentTimeMillis();
            // 이 서버 줄은 DB 대신 지금 접속자로 (가장 새것)
            String here = self();
            var all = new ArrayList<TravelItemRules.Presence>();
            for (var r : rows) if (!r.server().equals(here)) all.add(r);
            var live = new ArrayList<TravelItemRules.Online>();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getGameMode() == org.bukkit.GameMode.SPECTATOR || TravelBridge.vanished(o) || !p.canSee(o)) continue;
                live.add(new TravelItemRules.Online(o.getUniqueId(), o.getWorld().getName(), plugin.names().name(o), plugin.dorm(o)));
            }
            all.add(new TravelItemRules.Presence(here, now, live));
            var list = TravelItemRules.candidates(all, settings, id, now);
            if (list.size() > TeleportPickProtocol.MAX_ENTRIES) list = list.subList(0, TeleportPickProtocol.MAX_ENTRIES);
            var map = new LinkedHashMap<UUID, TravelItemRules.Candidate>();
            var entries = new ArrayList<TeleportPickProtocol.Entry>();
            for (var c : list) { map.put(c.id(), c); entries.add(new TeleportPickProtocol.Entry(c.id(), c.name(), c.dormitory(), serverLabel(c.server()))); }
            long token; do token = java.util.concurrent.ThreadLocalRandom.current().nextLong(); while (token == 0);
            byte[] bytes;
            try { bytes = TeleportPickProtocol.open(new TeleportPickProtocol.Open(token, entries)); }
            catch (IllegalArgumentException ex) { warn(p, "접속자 목록을 만들지 못했어요", ""); return; }
            pending.put(id, new Pending(token, hand, slot, map, now + PICK_TIMEOUT_MS));
            p.sendPluginMessage(plugin, TeleportPickProtocol.RESPONSE, bytes);
        });
    }

    private String serverLabel(String server) {
        try { var d = travel.config().server(server); return d == null ? server : d.label(); } catch (RuntimeException e) { return server; }
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (!TeleportPickProtocol.REQUEST.equals(channel)) return;
        TeleportPickProtocol.Request r;
        try { r = TeleportPickProtocol.request(data); } catch (IllegalArgumentException ignored) { return; }
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || pd.token() != r.token()) return;
        pending.remove(p.getUniqueId());
        if (r.action() == TeleportPickProtocol.CANCEL) return;   // 닫기: 소모 없음
        close(p, pd.token());
        if (System.currentTimeMillis() > pd.expiresAt()) { warn(p, "목록이 오래됐어요", settings.friendName() + "을 다시 써 주세요"); return; }
        var c = pd.candidates().get(r.target());
        if (c == null) return;   // 보낸 목록에 없던 사람
        if (!settings.usableOn(self()) || taken.containsKey(p.getUniqueId())) return;
        if (!stillHeld(p, pd.hand(), pd.slot(), false)) return;
        Player here = Bukkit.getPlayer(c.id());
        String world = here != null ? here.getWorld().getName() : c.world();
        String server = here != null ? self() : c.server();
        if (here == null && server.equals(self())) { warn(p, "그 유저가 접속을 끊었어요", settings.friendName() + "은 그대로예요"); return; }
        start(p, pd.hand(), TravelBridge.FRIEND_PREFIX + c.id(), server, world, "", 0, 64, 0, 0, 0, settings.friendName(), c.name() + " 님 (" + serverLabel(server) + ")");
    }

    private void close(Player p, long token) {
        if (!p.isOnline() || !modded(p)) return;
        try { p.sendPluginMessage(plugin, TeleportPickProtocol.RESPONSE, TeleportPickProtocol.close(token)); } catch (IllegalArgumentException ignored) {}
    }

    // ------------------------------------------------------------------ 이동 결과 (TravelBridge 훅)

    private boolean deathPoint(String point) { return TravelBridge.DEATH_ID.equals(point); }

    @Override public void arrived(Player p, String point) {
        taken.remove(p.getUniqueId());
        CmdKit.notify(p, "system", "travel-item", "도착했어요", arrivalName(point), 0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .5f, 1.4f);
    }

    @Override public String arrivalName(String point) {
        if (deathPoint(point)) return "마지막으로 쓰러진 자리";
        try {
            UUID target = UUID.fromString(point.substring(TravelBridge.FRIEND_PREFIX.length()));
            Player t = Bukkit.getPlayer(target);
            return (t == null ? CmdKit.name(target) : plugin.names().name(t)) + " 님 곁";
        } catch (RuntimeException e) { return ""; }
    }

    @Override public void refund(UUID id, String point, String why) {
        boolean death = deathPoint(point);
        String name = death ? settings.deathName() : settings.friendName(), item = death ? settings.deathItem() : settings.friendItem();
        ItemStack one = taken.remove(id);
        if (one == null) one = itemsAdder.create(item, 1);   // 도착 서버에서 실패: 새 아이템
        if (one == null) { plugin.getLogger().severe(name + " 반환 실패: 아이템을 만들 수 없어요 (" + item + ") player=" + id + " — 직접 지급 필요"); return; }
        Player p = Bukkit.getPlayer(id);
        plugin.getLogger().info(name + " 반환: " + id + " (" + why + ")");
        String body = name + "을 돌려드렸어요" + (why == null || why.isBlank() ? "" : " · " + why);
        var delivery = RewardDelivery.get();
        if (delivery != null) {
            delivery.give(id, List.of(one), "travel-item-refund:" + id + ":" + UUID.randomUUID(), name + " 반환", "이동하지 못해 " + name + "을 돌려드려요.", null);
            if (p != null && p.isOnline()) CmdKit.notify(p, "warning", "travel-item", "이동하지 못했어요", body, 0);
            return;
        }
        if (p != null && p.isOnline()) { give(p, one); CmdKit.notify(p, "warning", "travel-item", "이동하지 못했어요", body, 0); return; }
        MailService mail = Bukkit.getServicesManager().load(MailService.class);
        if (mail == null) { plugin.getLogger().severe(name + " 반환 실패: 우편 기능 없음 player=" + id + " — 직접 지급 필요"); return; }
        try {
            mail.sendSystem("travel-item-refund:" + id + ":" + UUID.randomUUID(), id, name + " 반환", "이동하지 못해 " + name + "을 돌려드려요.", List.of(one))
                    .whenComplete((mailId, error) -> { if (error != null) plugin.getLogger().severe(name + " 반환 우편 실패 player=" + id + ": " + error); });
        } catch (RuntimeException e) { plugin.getLogger().severe(name + " 반환 우편 실패 player=" + id + ": " + e); }
    }

    private static void give(Player p, ItemStack it) {
        var delivery = RewardDelivery.get();
        if (delivery != null) { delivery.give(p.getUniqueId(), List.of(it), "travel-item-back:" + p.getUniqueId() + ":" + UUID.randomUUID(), "아이템 반환", "쓰지 못한 아이템을 돌려드려요.", null); return; }
        for (var left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private static void warn(Player p, String title, String body) { CmdKit.notify(p, "warning", "travel-item", title, body, 1500); }

    /** 나감 (대개 도착 서버로 가는 중) — 그 뒤 실패는 도착 서버 또는 표 만료가 새 아이템으로 정리. */
    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        taken.remove(id); asking.remove(id); pending.remove(id); clickGate.remove(id);
    }
}
