package school.magiccodex.paper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.ChunkClaimProtocol;
import school.magiccodex.protocol.ChunkClaimProtocol.*;

/**
 * 야생 청크 점유 (땅문서, 메모 #88, 오너 2026-10-26). MagicCodexBridge 안의 기능 하나 — chunk-claims.yml 의 servers(travel.yml server-id)
 * 에 든 서버(기본 야생 wild·wild2)에서만 켜진다. 학교 등 다른 서버는 이벤트·작업·DB 를 하나도 열지 않는다.
 *
 * <ul>
 *   <li>땅문서(ItemsAdder item:chunk_deed)를 손에 들면 클라에 주변 청크 상태(주인 한글 닉·소유 가능·제한·내 땅·허용됨)를 보낸다.
 *       우클릭 = 1장 쓰고 서 있는 청크를 내 땅으로 (주인 있음·제한·최대 수면 아이템을 쓰지 않고 거절).</li>
 *   <li>내 땅 안에 서 있으면 그 청크 안 사람 목록·허용 명단을 보낸다 (클라 오른쪽 위 작은 칸 → 펼친 관리 창). 허용 넣기·빼기, 판매(땅문서 돌려받기).</li>
 *   <li>보호: 점유 청크에서는 주인·허용 명단만 블록 부수기·놓기·보관함·문/버튼/레버·양동이·액자/갑옷 거치대·작물 밟기·동물 해치기.
 *       폭발·불 번짐·피스톤·흐르는 물/용암이 바깥에서 들어와 깨는 것도 막는다. PvP 는 건드리지 않는다. 우회: {@value #BYPASS}.</li>
 *   <li>판정은 전부 서버 메모리 표({@link ChunkClaimRules.Index})에서. DB({@link ChunkClaimStore})는 시작 때 한 번 읽고, 쓰기는 전용 작업 스레드에서.</li>
 *   <li>보내기: 0.5초마다 접속자를 훑어 상태를 계산하고, 지난번에 보낸 것과 다를 때만 보낸다 (손에 들었을 때·청크를 옮길 때·바뀐 일이 있을 때).</li>
 * </ul>
 */
final class ChunkClaimBridge implements Listener, PluginMessageListener, AutoCloseable {
    static final String FILE = "chunk-claims.yml";
    static final String BYPASS = "magiccodex.chunkclaim.bypass";
    static final String NOTIFY_KEY = "chunk-claim", DENY_KEY = "chunk-deny";
    private static final String PET_TAG = "chacademia_pet", NPC_TAG = "magiccodex_npc";

    private final MagicCodexBridge plugin;
    private final File file;
    private final ItemsAdderItems itemsAdder;
    private final ChunkClaimWorldGuard worldGuard;
    private ChunkClaimRules.Settings settings = ChunkClaimRules.DEFAULT;
    private final String serverId;
    private final boolean active;
    private ChunkClaimStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-chunk-claim"); t.setDaemon(true); return t; });
    private ChunkClaimRules.Index index = new ChunkClaimRules.Index();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, View> sentView = new HashMap<>();
    private final Map<UUID, Land> sentLand = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private record Name(String value, long until) {}
    private final Map<UUID, Name> names = new HashMap<>();
    private Material deedType;
    private long deedRetryAt;
    private volatile boolean closed;

    ChunkClaimBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        this.worldGuard = new ChunkClaimWorldGuard(plugin.getLogger());
        settings = parse();
        serverId = serverId(plugin);
        active = settings.activeOn(serverId);
        if (active) {
            var dbSettings = GenericDatabaseFile.load(plugin.getDataFolder().toPath());
            try {
                store = io.submit(() -> new ChunkClaimStore(dbSettings, plugin.getDataFolder().toPath().resolve("chunk-claims.db"))).get(15, TimeUnit.SECONDS);
                index = io.submit(this::loadIndex).get(20, TimeUnit.SECONDS);
            } catch (Exception e) { io.shutdownNow(); throw e; }
            var messenger = Bukkit.getMessenger();
            messenger.registerOutgoingPluginChannel(plugin, ChunkClaimProtocol.STATE);
            messenger.registerIncomingPluginChannel(plugin, ChunkClaimProtocol.ACTION, this);
            Bukkit.getPluginManager().registerEvents(this, plugin);
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::pass, 40, 10));
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTrust, 1200, 1200));
            plugin.getLogger().info("청크 점유: 켜짐 · 서버 " + serverId + " · 세계 " + settings.worlds() + " · " + (store.shared() ? "공유 DB" : "SQLite chunk-claims.db (이 서버)")
                    + " · 점유 " + index.size() + "칸 · 최대 " + (settings.maxClaims() <= 0 ? "제한 없음" : settings.maxClaims() + "칸"));
        } else plugin.getLogger().info("청크 점유: 이 서버(" + (serverId.isEmpty() ? "server-id 없음" : serverId) + ")에서는 꺼짐 (chunk-claims.yml servers " + settings.servers() + ")");
        CmdKit.register(plugin, "/컨텐츠관리 청크·/유저관리 청크", this::commands);
    }

    private static String serverId(MagicCodexBridge plugin) {
        var travel = plugin.travel();
        try { return travel == null || travel.config() == null ? "" : travel.config().self(); }
        catch (RuntimeException e) { return ""; }
    }

    @Override public void close() {
        closed = true;
        tasks.forEach(BukkitTask::cancel); tasks.clear();
        if (store != null) io.execute(() -> { try { store.close(); } catch (Exception e) { plugin.getLogger().warning("청크 점유 저장소 종료 실패: " + e); } });
        io.shutdown();
        try { if (!io.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().severe("청크 점유 DB 종료 대기 초과"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        sentView.clear(); sentLand.clear(); busy.clear();
    }

    // ------------------------------------------------------------------ 설정

    private ChunkClaimRules.Settings parse() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        var d = ChunkClaimRules.DEFAULT;
        var excluded = new ArrayList<ChunkClaimRules.Exclusion>();
        for (var m : y.getMapList("excluded")) {
            Object w = m.get("world"), x = m.get("x"), z = m.get("z"), r = m.get("radius");
            if (w == null || !(r instanceof Number radius)) throw new IllegalArgumentException("excluded: world·radius 가 필요해요 (" + m + ")");
            excluded.add(new ChunkClaimRules.Exclusion(String.valueOf(w), x instanceof Number nx ? nx.doubleValue() : 0, z instanceof Number nz ? nz.doubleValue() : 0, radius.doubleValue()));
        }
        var messages = new HashMap<String, String>();
        ConfigurationSection ms = y.getConfigurationSection("messages");
        if (ms != null) for (String k : ms.getKeys(false)) { String v = ms.getString(k); if (v != null && !v.isBlank()) messages.put(k, v); }
        var ignore = new HashSet<String>();
        for (String id : y.getStringList("worldguard.ignore-regions")) if (id != null && !id.isBlank()) ignore.add(id.strip().toLowerCase(Locale.ROOT));
        String free = ChunkClaimProtocol.cut(y.getString("labels.free", d.freeLabel()), 24), restricted = ChunkClaimProtocol.cut(y.getString("labels.restricted", d.restrictedLabel()), 24);
        String item = y.getString("item", d.item()).strip();
        if (!item.contains(":")) throw new IllegalArgumentException("item 은 ItemsAdder namespace:id 모양이어야 해요 (" + item + ")");
        return new ChunkClaimRules.Settings(y.getBoolean("enabled", true), item, y.getString("item-name", d.itemName()),
                y.isList("servers") ? new HashSet<>(y.getStringList("servers")) : d.servers(),
                y.isList("worlds") ? new HashSet<>(y.getStringList("worlds")) : d.worlds(), excluded,
                Math.max(0, y.getInt("max-claims-per-player", 0)), ChunkClaimRules.clampRadius(y.getInt("view-radius", d.viewRadius())),
                y.getBoolean("worldguard.restrict-overlap", true), ignore, Math.max(0, y.getLong("notify-cooldown-ms", d.notifyCooldownMs())),
                free.isEmpty() ? d.freeLabel() : free, restricted.isEmpty() ? d.restrictedLabel() : restricted, messages);
    }

    void reload() throws Exception {
        var next = parse();
        if (next.activeOn(serverId) != active)
            plugin.getLogger().warning("청크 점유: 이 서버에서 켜기/끄기가 바뀌었어요 — 서버를 다시 시작해야 적용돼요");
        settings = next;
        deedType = null; deedRetryAt = 0;
        worldGuard.clear();
        if (store != null) io.execute(() -> {
            ChunkClaimRules.Index fresh;
            try { fresh = loadIndex(); } catch (Exception e) { plugin.getLogger().warning("청크 점유: 새로고침 중 점유 다시 읽기 실패 " + e); return; }
            main(() -> { index = fresh; sentView.clear(); sentLand.clear(); });
        });
    }

    /** 작업 스레드: 이 서버에 있는 대상 세계의 점유 + 허용 명단 전부. */
    private ChunkClaimRules.Index loadIndex() throws Exception {
        var worlds = new ArrayList<String>();
        for (String w : settings.worlds()) if (Bukkit.getWorld(w) != null) worlds.add(w);
        var fresh = new ChunkClaimRules.Index();
        for (var r : store.load(serverId, worlds)) fresh.put(r.world(), r.cx(), r.cz(), r.owner(), r.at());
        fresh.replaceTrust(store.trust());
        return fresh;
    }

    /** 1분마다: 다른 서버에서 바꾼 허용 명단도 맞춘다 (작은 표 한 번 읽기). */
    private void refreshTrust() {
        if (store == null || closed) return;
        io.execute(() -> {
            Map<UUID, List<UUID>> all;
            try { all = store.trust(); } catch (Exception e) { return; }
            main(() -> index.replaceTrust(all));
        });
    }

    private void main(Runnable r) { if (!closed && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r); }

    // ------------------------------------------------------------------ 판정

    private boolean worldOn(String world) { return active && settings.worlds().contains(world); }

    /** "제한": 대상 세계 밖 · 설정 제외 구역 · WorldGuard 지역과 겹침. */
    boolean restricted(World world, int cx, int cz) {
        if (ChunkClaimRules.configRestricted(settings, world.getName(), cx, cz)) return true;
        return settings.worldGuard() && worldGuard.overlaps(world, cx, cz, settings.ignoreRegions());
    }

    private UUID ownerAt(Block b) {
        String w = b.getWorld().getName();
        if (!worldOn(w)) return null;
        return index.owner(w, b.getX() >> 4, b.getZ() >> 4);
    }
    private UUID ownerAt(Location l) {
        if (l.getWorld() == null) return null;
        String w = l.getWorld().getName();
        if (!worldOn(w)) return null;
        return index.owner(w, l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }
    private boolean allowedAt(Player p, UUID owner) {
        return owner == null || owner.equals(p.getUniqueId()) || index.trusted(owner, p.getUniqueId()) || p.hasPermission(BYPASS);
    }
    /** 남의 땅이면 알리고 true. */
    private boolean deny(Player p, UUID owner, boolean tell) {
        if (allowedAt(p, owner)) return false;
        if (tell) CmdKit.notify(p, "warning", DENY_KEY, settings.message("denied", Map.of("name", name(owner))), settings.message("denied-body"), settings.notifyCooldownMs());
        return true;
    }

    // ------------------------------------------------------------------ 땅문서

    private Material deedType() {
        if (deedType == null && System.currentTimeMillis() >= deedRetryAt) {
            ItemStack sample = itemsAdder.create(settings.item(), 1);
            if (sample != null) deedType = sample.getType(); else deedRetryAt = System.currentTimeMillis() + 30_000;
        }
        return deedType;
    }
    private boolean isDeed(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        Material type = deedType();
        if (type != null && it.getType() != type) return false;
        return settings.item().equals(itemsAdder.idOf(it));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player p = event.getPlayer();
        ItemStack held = p.getInventory().getItem(hand);
        if (!isDeed(held)) return;
        event.setCancelled(true);
        if (hand == EquipmentSlot.OFF_HAND && isDeed(p.getInventory().getItemInMainHand())) return;   // 양손: 주 손 한 번만
        if (!worldOn(p.getWorld().getName())) { tell(p, "warning", "restricted", Map.of()); return; }
        claim(p, hand, held);
    }

    private void tell(Player p, String kind, String key, Map<String, String> values) {
        CmdKit.notify(p, kind, NOTIFY_KEY, settings.message(key, values), settings.message(key + "-body", values).equals(key + "-body") ? "" : settings.message(key + "-body", values), settings.notifyCooldownMs());
    }

    private void claim(Player p, EquipmentSlot hand, ItemStack held) {
        UUID id = p.getUniqueId();
        World world = p.getWorld();
        Location at = p.getLocation();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        String w = world.getName();
        UUID owner = index.owner(w, cx, cz);
        if (owner != null) { if (owner.equals(id)) tell(p, "warning", "mine", Map.of()); else tell(p, "warning", "taken", Map.of("name", name(owner))); return; }
        if (restricted(world, cx, cz)) { tell(p, "warning", "restricted", Map.of()); return; }
        int max = settings.maxClaims();
        if (max > 0 && index.count(id) >= max) { tell(p, "warning", "limit", Map.of("max", String.valueOf(max))); return; }
        if (!busy.add(id)) { tell(p, "warning", "busy", Map.of()); return; }
        // 1장을 먼저 가져가고 (디스크에도 저장), DB 가 거절하면 돌려준다. 같은 땅문서로 두 번 점유할 수 없다.
        ItemStack one = held.clone(); one.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
        try { p.saveData(); } catch (RuntimeException e) { plugin.getLogger().warning("청크 점유: 사용 전 저장 실패 " + p.getName() + " (" + e + ")"); }
        long now = System.currentTimeMillis();
        io.execute(() -> {
            ChunkClaimStore.ClaimResult result = null;
            try { result = store.claim(w, cx, cz, id, max, serverId, now); }
            catch (Exception e) { plugin.getLogger().warning("청크 점유 저장 실패 " + id + " " + w + " " + cx + "," + cz + ": " + e); }
            var r = result;
            main(() -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (r == ChunkClaimStore.ClaimResult.CLAIMED) {
                    index.put(w, cx, cz, id, now);
                    plugin.getLogger().info("청크 점유: " + id + " → " + w + " " + cx + "," + cz);
                    if (online != null) {
                        tell(online, "shop", "claimed", Map.of());
                        online.playSound(online.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.3f);
                    }
                    changed();
                    return;
                }
                give(id, one, "청크 점유 실패");
                if (online == null) return;
                if (r == ChunkClaimStore.ClaimResult.TAKEN) tell(online, "warning", "taken", Map.of("name", "다른 사람"));
                else if (r == ChunkClaimStore.ClaimResult.LIMIT) tell(online, "warning", "limit", Map.of("max", String.valueOf(max)));
                else tell(online, "error", "error", Map.of());
                if (r == ChunkClaimStore.ClaimResult.TAKEN) io.execute(() -> { try { var fresh = loadIndex(); main(() -> index = fresh); } catch (Exception ignored) {} });
            });
        });
    }

    /** 땅문서 돌려주기: 접속 중이면 인벤토리(꽉 차면 발밑), 아니면 우편. */
    private void give(UUID id, ItemStack one, String why) {
        // 공통 지급 함수 (2026-10-26): 접속 중이면 넣을 수 있는 칸(핫바 + 잠금 해제된 지금 쪽) → 남으면 우편, 아니면 우편.
        var delivery = RewardDelivery.get();
        if (delivery != null) { delivery.give(id, List.of(one), "chunk-deed-refund:" + id + ":" + UUID.randomUUID(), settings.itemName() + " 반환", why + " — " + settings.itemName() + "을 돌려드려요.", null); return; }
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            for (var left : p.getInventory().addItem(one).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            try { p.saveData(); } catch (RuntimeException ignored) {}
            return;
        }
        MailService mail = Bukkit.getServicesManager().load(MailService.class);
        String what = settings.itemName();
        if (mail == null) { plugin.getLogger().severe("청크 점유: " + what + " 반환 실패 (우편 없음) player=" + id + " (" + why + ") — 직접 지급 필요"); return; }
        try {
            mail.sendSystem("chunk-deed-refund:" + id + ":" + UUID.randomUUID(), id, what + " 반환", why + " — " + what + "을 돌려드려요.", List.of(one))
                    .whenComplete((mailId, error) -> { if (error != null) plugin.getLogger().severe("청크 점유: " + what + " 반환 우편 실패 player=" + id + ": " + error); });
        } catch (RuntimeException e) { plugin.getLogger().severe("청크 점유: " + what + " 반환 우편 실패 player=" + id + ": " + e); }
    }

    // ------------------------------------------------------------------ 클라 동작 (허용 넣기·빼기, 판매)

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (!ChunkClaimProtocol.ACTION.equals(channel) || !active) return;
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < 250) return;
        lastAction.put(p.getUniqueId(), now);
        ChunkClaimProtocol.Action a;
        try { a = ChunkClaimProtocol.decodeAction(data); } catch (IllegalArgumentException bad) { return; }
        switch (a.action()) {
            case ChunkClaimProtocol.A_TRUST, ChunkClaimProtocol.A_UNTRUST -> trust(p, a.target(), a.action() == ChunkClaimProtocol.A_TRUST);
            case ChunkClaimProtocol.A_SELL -> sell(p, a.cx(), a.cz());
            default -> { }
        }
    }

    private void result(Player p, boolean ok, String message) {
        if (p.getListeningPluginChannels().contains(ChunkClaimProtocol.STATE)) p.sendPluginMessage(plugin, ChunkClaimProtocol.STATE, ChunkClaimProtocol.encode(new Result(ok, message)));
        else CmdKit.notify(p, ok ? "system" : "warning", NOTIFY_KEY, message, "", 0);
    }

    private void trust(Player p, UUID target, boolean add) {
        UUID id = p.getUniqueId();
        if (target.equals(id)) { result(p, false, "나 자신은 넣을 수 없어요"); return; }
        if (index.count(id) <= 0) { result(p, false, "내 땅이 있어야 허용 명단을 쓸 수 있어요"); return; }
        if (add) {
            Player other = Bukkit.getPlayer(target);
            if (other == null) { result(p, false, "접속 중인 사람만 넣을 수 있어요"); return; }
            if (index.trusted(id, target)) { result(p, true, settings.message("trusted", Map.of("name", name(target)))); return; }
            if (index.trustedOf(id).size() >= ChunkClaimProtocol.MAX_PEOPLE) { result(p, false, "허용 명단은 " + ChunkClaimProtocol.MAX_PEOPLE + "명까지예요"); return; }
        } else if (!index.trusted(id, target)) { result(p, true, settings.message("untrusted", Map.of("name", name(target)))); return; }
        if (!busy.add(id)) { result(p, false, settings.message("busy")); return; }
        long now = System.currentTimeMillis();
        io.execute(() -> {
            boolean ok = false;
            try { store.trust(id, target, add, now); ok = true; }
            catch (Exception e) { plugin.getLogger().warning("청크 점유: 허용 명단 저장 실패 " + id + " → " + target + ": " + e); }
            boolean saved = ok;
            main(() -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (!saved) { if (online != null) result(online, false, settings.message("error")); return; }
                index.trust(id, target, add);
                String targetName = name(target);
                if (online != null) result(online, true, settings.message(add ? "trusted" : "untrusted", Map.of("name", targetName)));
                Player other = Bukkit.getPlayer(target);
                if (add && other != null) CmdKit.notify(other, "system", NOTIFY_KEY, settings.message("trusted-target", Map.of("name", name(id))), "", 0);
                changed();
            });
        });
    }

    private void sell(Player p, int cx, int cz) {
        UUID id = p.getUniqueId();
        Location at = p.getLocation();
        String w = p.getWorld().getName();
        if (!worldOn(w) || (at.getBlockX() >> 4) != cx || (at.getBlockZ() >> 4) != cz) { result(p, false, "팔 땅 안에 서 있어야 해요"); return; }
        if (!id.equals(index.owner(w, cx, cz))) { result(p, false, "내 땅이 아니에요"); return; }
        ItemStack deed = itemsAdder.create(settings.item(), 1);
        if (deed == null) { result(p, false, settings.itemName() + "을 만들 수 없어 지금은 팔 수 없어요"); plugin.getLogger().severe("청크 점유: 판매 거절 — ItemsAdder 아이템 없음 " + settings.item()); return; }
        if (!busy.add(id)) { result(p, false, settings.message("busy")); return; }
        io.execute(() -> {
            UUID released = null; boolean failed = false;
            try { released = store.release(serverId, w, cx, cz, id); }
            catch (Exception e) { failed = true; plugin.getLogger().warning("청크 점유: 판매 저장 실패 " + id + " " + w + " " + cx + "," + cz + ": " + e); }
            UUID r = released; boolean f = failed;
            main(() -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (r == null) {
                    if (!f) io.execute(() -> { try { var fresh = loadIndex(); main(() -> index = fresh); } catch (Exception ignored) {} });   // DB 와 메모리가 달랐음 — 다시 읽어 맞춘다
                    if (online != null) result(online, false, f ? settings.message("error") : "내 땅이 아니에요");
                    changed();
                    return;
                }
                index.remove(w, cx, cz);
                plugin.getLogger().info("청크 판매: " + id + " ← " + w + " " + cx + "," + cz);
                give(id, deed, "땅 판매");
                if (online != null) {
                    tell(online, "shop", "sold", Map.of());
                    online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.1f);
                }
                changed();
            });
        });
    }

    // ------------------------------------------------------------------ 클라 상태 보내기

    /** 점유가 바뀌었다: 다음 훑기에서 모두 다시 비교한다 (실제로 다른 사람에게만 감). */
    private void changed() { Bukkit.getScheduler().runTask(plugin, this::pass); }

    /** 0.5초마다 (그리고 바뀐 일이 있을 때): 접속자마다 보여 줄 상태를 계산하고 지난번과 다를 때만 보낸다. */
    private void pass() {
        if (closed || store == null) return;
        Material type = deedType();
        // 청크 → 그 안의 사람 (내 땅 안 사람 목록용, 한 번만 훑음)
        Map<String, Map<Long, List<Player>>> occupants = new HashMap<>();
        var online = Bukkit.getOnlinePlayers();
        for (Player p : online) {
            String w = p.getWorld().getName();
            if (!worldOn(w)) continue;
            Location l = p.getLocation();
            occupants.computeIfAbsent(w, k -> new HashMap<>()).computeIfAbsent(ChunkClaimRules.key(l.getBlockX() >> 4, l.getBlockZ() >> 4), k -> new ArrayList<>(2)).add(p);
        }
        for (Player p : online) {
            UUID id = p.getUniqueId();
            if (!p.getListeningPluginChannels().contains(ChunkClaimProtocol.STATE)) continue;
            String w = p.getWorld().getName();
            View view = View.HIDDEN; Land land = Land.OUTSIDE;
            if (worldOn(w)) {
                Location l = p.getLocation();
                int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
                ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
                boolean holding = type != null && (main.getType() == type && isDeed(main) || off.getType() == type && isDeed(off));
                if (holding) view = view(p, cx, cz);
                if (id.equals(index.owner(w, cx, cz))) land = land(p, w, cx, cz, occupants.getOrDefault(w, Map.of()).getOrDefault(ChunkClaimRules.key(cx, cz), List.of()));
            }
            send(p, view, sentView);
            send(p, land, sentLand);
        }
    }
    private <T extends Frame> void send(Player p, T frame, Map<UUID, T> sent) {
        T before = sent.get(p.getUniqueId());
        boolean hidden = frame == View.HIDDEN || frame == Land.OUTSIDE;
        if (Objects.equals(before, frame) || before == null && hidden) return;
        byte[] bytes;
        try { bytes = ChunkClaimProtocol.encode(frame); } catch (IllegalArgumentException tooLarge) { return; }
        p.sendPluginMessage(plugin, ChunkClaimProtocol.STATE, bytes);
        if (hidden) sent.remove(p.getUniqueId()); else sent.put(p.getUniqueId(), frame);
    }

    private View view(Player p, int cx, int cz) {
        World world = p.getWorld();
        String w = world.getName();
        int r = settings.viewRadius();
        var cells = new ArrayList<Cell>((2 * r + 1) * (2 * r + 1));
        UUID me = p.getUniqueId();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            UUID owner = index.owner(w, cx + dx, cz + dz);
            if (owner != null) cells.add(new Cell(owner.equals(me) ? ChunkClaimProtocol.MINE : index.trusted(owner, me) ? ChunkClaimProtocol.TRUSTED : ChunkClaimProtocol.OTHER, name(owner)));
            else cells.add(new Cell(restricted(world, cx + dx, cz + dz) ? ChunkClaimProtocol.RESTRICTED : ChunkClaimProtocol.FREE, ""));
        }
        return new View(true, ChunkClaimProtocol.cut(w, 64), cx, cz, r, settings.freeLabel(), settings.restrictedLabel(), cells);
    }

    private Land land(Player p, String w, int cx, int cz, List<Player> here) {
        UUID me = p.getUniqueId();
        var visitors = new ArrayList<Person>();
        for (Player o : here) {
            if (o.getUniqueId().equals(me) || o.hasMetadata("NPC") || visitors.size() >= ChunkClaimProtocol.MAX_PEOPLE) continue;
            if (o.getGameMode() == org.bukkit.GameMode.SPECTATOR || !p.canSee(o)) continue;   // 관전·숨은 관리자는 목록에 안 보임
            visitors.add(new Person(o.getUniqueId(), name(o.getUniqueId()), ChunkClaimProtocol.F_ONLINE | (index.trusted(me, o.getUniqueId()) ? ChunkClaimProtocol.F_TRUSTED : 0)));
        }
        visitors.sort(Comparator.comparing(Person::name));
        var trusted = new ArrayList<Person>();
        for (UUID t : index.trustedOf(me)) {
            if (trusted.size() >= ChunkClaimProtocol.MAX_PEOPLE) break;
            trusted.add(new Person(t, name(t), ChunkClaimProtocol.F_TRUSTED | (Bukkit.getPlayer(t) != null ? ChunkClaimProtocol.F_ONLINE : 0)));
        }
        trusted.sort(Comparator.comparing(Person::name));
        return new Land(true, cx, cz, Math.min(65535, index.count(me)), Math.min(65535, settings.maxClaims()), visitors, trusted);
    }

    /** 한글 닉네임 (30초 기억). 접속하지 않은 주인은 닉네임 저장소에 읽기를 부탁해 두고, 그동안 계정 이름. */
    private String name(UUID id) {
        long now = System.currentTimeMillis();
        var cached = names.get(id);
        if (cached != null && cached.until() > now) return cached.value();
        String value = "";
        Player online = Bukkit.getPlayer(id);
        if (online != null) value = plugin.nicknameOf(online);
        else if (plugin.names() != null) value = plugin.names().registered(id);
        boolean known = !value.isBlank();
        if (!known) { String account = Bukkit.getOfflinePlayer(id).getName(); value = account == null ? "주인" : account; }
        value = ChunkClaimProtocol.cut(value, 32);
        if (value.isEmpty()) value = "주인";
        if (names.size() > 4096) names.values().removeIf(n -> n.until() < now);
        names.put(id, new Name(value, now + (known ? 30_000 : 3_000)));
        return value;
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        sentView.remove(id); sentLand.remove(id); lastAction.remove(id); names.remove(id);
    }
    @EventHandler public void join(PlayerJoinEvent e) { sentView.remove(e.getPlayer().getUniqueId()); sentLand.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ 보호 (메모리 표만 본다)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) { if (deny(e.getPlayer(), ownerAt(e.getBlock()), true)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent e) { if (deny(e.getPlayer(), ownerAt(e.getBlockPlaced()), true)) e.setCancelled(true); }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        var action = e.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && action != org.bukkit.event.block.Action.PHYSICAL) return;
        UUID owner = ownerAt(b);
        if (owner == null || allowedAt(e.getPlayer(), owner)) return;
        if (action == org.bukkit.event.block.Action.PHYSICAL) { e.setUseInteractedBlock(Event.Result.DENY); e.setCancelled(true); return; }   // 작물 밟기·압력판
        ItemStack item = e.getItem();
        if (item != null && isDeed(item)) return;   // 땅문서는 점유 처리(use)가 "주인이 있어요" 로 알린다
        boolean block = b.getType().isInteractable(), modifies = item != null && ChunkClaimRules.modifiesBlocks(item.getType().name());
        e.setUseInteractedBlock(Event.Result.DENY);   // 보관함·문·버튼·레버·작업대 등
        if (modifies) e.setUseItemInHand(Event.Result.DENY);   // 부싯돌·뼛가루·괭이·삽·도끼·갑옷 거치대·액자 놓기 등
        if (block || modifies) deny(e.getPlayer(), owner, true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent e) { if (deny(e.getPlayer(), ownerAt(e.getBlock()), true)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) { if (deny(e.getPlayer(), ownerAt(e.getBlock()), true)) e.setCancelled(true); }

    /** 지켜 주는 엔티티: 동물·주민·갑옷 거치대·액자/그림. 플레이어(PvP)·몬스터·NPC·펫은 건드리지 않는다. */
    private static boolean protectedEntity(Entity e) {
        if (e instanceof Player || e.hasMetadata("NPC") || e.getScoreboardTags().contains(NPC_TAG) || e.getScoreboardTags().contains(PET_TAG)) return false;
        if (e instanceof Tameable t && t.isTamed()) return false;   // 길들인 동물은 그 주인의 것 (땅 주인 것이 아님)
        return e instanceof Animals || e instanceof AbstractVillager || e instanceof ArmorStand || e instanceof Hanging;
    }
    private static Player playerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        if (!protectedEntity(target)) return;
        if (deny(e.getPlayer(), ownerAt(target.getLocation()), true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damageEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!protectedEntity(victim)) return;
        UUID owner = ownerAt(victim.getLocation());
        if (owner == null) return;
        Player attacker = playerOf(e.getDamager());
        if (attacker != null) { if (deny(attacker, owner, true)) e.setCancelled(true); return; }
        // 폭발이 갑옷 거치대·액자를 깨는 것
        if ((victim instanceof ArmorStand || victim instanceof Hanging) && (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hangingBreak(HangingBreakEvent e) {
        UUID owner = ownerAt(e.getEntity().getLocation());
        if (owner == null) return;
        if (e instanceof HangingBreakByEntityEvent by) {
            Player p = playerOf(by.getRemover());
            if (p != null) { if (deny(p, owner, true)) e.setCancelled(true); return; }
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION || e.getCause() == HangingBreakEvent.RemoveCause.ENTITY) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent e) { protectBlocks(e.getLocation().getWorld(), e.blockList()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent e) { protectBlocks(e.getBlock().getWorld(), e.blockList()); }

    private void protectBlocks(World world, List<Block> blocks) {
        if (world == null || blocks.isEmpty() || !worldOn(world.getName())) return;
        String w = world.getName();
        blocks.removeIf(b -> index.owner(w, b.getX() >> 4, b.getZ() >> 4) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) { if (ownerAt(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) {
        UUID owner = ownerAt(e.getBlock());
        if (owner == null) return;
        Player p = e.getPlayer();
        if (p != null) { if (deny(p, owner, true)) e.setCancelled(true); return; }
        e.setCancelled(true);   // 불 번짐·용암·화염구·번개 등 사람이 아닌 불
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) { if (e.getSource().getType() == Material.FIRE && ownerAt(e.getBlock()) != null) e.setCancelled(true); }

    /** 바깥(다른 주인·주인 없음) 피스톤이 점유 청크 블록을 밀거나 당기지 못하게. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent e) {
        if (!worldOn(e.getBlock().getWorld().getName())) return;
        UUID piston = ownerAt(e.getBlock());
        for (Block b : e.getBlocks()) if (foreign(piston, b) || foreign(piston, b.getRelative(e.getDirection()))) { e.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent e) {
        if (!worldOn(e.getBlock().getWorld().getName())) return;
        UUID piston = ownerAt(e.getBlock());
        for (Block b : e.getBlocks()) if (foreign(piston, b)) { e.setCancelled(true); return; }
    }
    private boolean foreign(UUID source, Block b) { UUID owner = ownerAt(b); return owner != null && !owner.equals(source); }

    /** 물·용암이 다른 청크로 흘러 들어갈 때만 본다 (같은 청크 안 흐름은 계산 없이 통과). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        Block from = e.getBlock(), to = e.getToBlock();
        if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) return;
        if (!worldOn(to.getWorld().getName())) return;
        if (foreign(ownerAt(from), to)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ 관리 명령

    private void commands() {
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, CmdKit.literal("청크")
                .then(CmdKit.literal("보기").executes(CmdKit.run(ctx -> view(CmdKit.self(ctx)))))
                .then(CmdKit.confirm(CmdKit.literal("해제"), ctx -> adminRelease(CmdKit.self(ctx)))),
                "청크 점유 보기·해제 (지금 서 있는 청크, 해제는 땅문서를 돌려주지 않음)", "청크 보기");
        ChacaApi.branch(plugin, ChacaApi.유저관리, CmdKit.literal("청크")
                .then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> userList(CmdKit.sender(ctx), ChacaApi.target(ctx))))),
                "청크 점유 목록 (모든 서버·세계) · 허용 명단", "청크 목록");
        ChacaApi.reload(plugin, "청크", FILE + " (청크 점유: 땅문서 아이템·세계·제외 구역·최대 수·문구)", this::reload);
        ChacaApi.status(plugin, "청크 점유", () -> !active ? "꺼짐 (이 서버 " + (serverId.isEmpty() ? "?" : serverId) + ")"
                : (store != null && store.shared() ? "공유 DB" : "SQLite(이 서버)") + " · 점유 " + index.size() + "칸 · 세계 " + settings.worlds());
        ChacaApi.summary(plugin, "청크", id -> { int n = index.count(id); return n <= 0 ? "" : "이 서버 " + n + "칸"; });
    }

    private void view(Player p) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!active) throw ChacaApi.error("이 서버에서는 청크 점유가 꺼져 있어요");
        World world = p.getWorld();
        Location l = p.getLocation();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        var claim = index.get(world.getName(), cx, cz);
        var out = new ArrayList<String>();
        out.add("── 청크 " + world.getName() + " " + cx + ", " + cz + " (블록 " + cx * 16 + "~" + (cx * 16 + 15) + ", " + cz * 16 + "~" + (cz * 16 + 15) + ") ──");
        if (claim == null) {
            boolean config = ChunkClaimRules.configRestricted(settings, world.getName(), cx, cz);
            boolean region = !config && settings.worldGuard() && worldGuard.overlaps(world, cx, cz, settings.ignoreRegions());
            out.add(" 상태: " + (config ? "제한 (대상 세계 밖이거나 제외 구역)" : region ? "제한 (WorldGuard 지역과 겹침)" : "소유 가능"));
        } else {
            out.add(" 주인: " + name(claim.owner()) + " (" + claim.owner() + ")");
            out.add(" 점유: " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(claim.at())) + " · 주인의 이 서버 땅 " + index.count(claim.owner()) + "칸");
            var trusted = new ArrayList<String>();
            for (UUID t : index.trustedOf(claim.owner())) trusted.add(name(t));
            out.add(" 허용 명단: " + (trusted.isEmpty() ? "없음" : String.join(", ", trusted)));
        }
        CmdKit.lines(p, out);
    }

    private void adminRelease(Player p) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!active) throw ChacaApi.error("이 서버에서는 청크 점유가 꺼져 있어요");
        String w = p.getWorld().getName();
        Location l = p.getLocation();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        if (index.owner(w, cx, cz) == null) throw ChacaApi.error("이 청크에는 주인이 없어요");
        io.execute(() -> {
            UUID released = null; Exception error = null;
            try { released = store.release(serverId, w, cx, cz, null); } catch (Exception e) { error = e; }
            UUID r = released; Exception err = error;
            main(() -> {
                if (err != null) { CmdKit.fail(p, "청크를 해제하지 못했어요", err); return; }
                index.remove(w, cx, cz);
                changed();
                if (r == null) { CmdKit.ok(p, "이미 해제된 청크예요"); return; }
                plugin.getLogger().info("청크 관리자 해제: " + p.getName() + " → " + w + " " + cx + "," + cz + " (주인 " + r + ")");
                CmdKit.ok(p, name(r) + "님의 청크를 해제했어요 (" + cx + ", " + cz + ")");
            });
        });
    }

    private void userList(org.bukkit.command.CommandSender to, UUID target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (store == null) throw ChacaApi.error("이 서버에서는 청크 점유가 꺼져 있어요 (야생 서버에서 확인해 주세요)");
        String who = name(target);
        var trusted = new ArrayList<String>();
        for (UUID t : index.trustedOf(target)) trusted.add(name(t));
        io.execute(() -> {
            List<ChunkClaimStore.Row> rows; Exception error = null;
            try { rows = store.claimsOf(target); } catch (Exception e) { rows = List.of(); error = e; }
            var list = rows; Exception err = error;
            main(() -> {
                if (err != null) { CmdKit.fail(to, "청크 목록을 읽지 못했어요", err); return; }
                var out = new ArrayList<String>();
                out.add("── " + who + "님의 청크 " + list.size() + "칸" + (settings.maxClaims() > 0 ? " / 최대 " + settings.maxClaims() : "") + " ──");
                var format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                for (var r : list) out.add(" " + r.server() + "/" + r.world() + " 청크 " + r.cx() + ", " + r.cz() + " (블록 " + (r.cx() * 16 + 8) + ", " + (r.cz() * 16 + 8) + ") · " + format.format(new Date(r.at())));
                out.add(" 허용 명단: " + (trusted.isEmpty() ? "없음" : String.join(", ", trusted)));
                CmdKit.lines(to, out);
            });
        });
    }
}
