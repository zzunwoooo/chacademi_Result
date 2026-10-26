package school.magiccodex.paper;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.protocol.TravelLinkProtocol;
import school.magiccodex.protocol.TravelProtocol;

/**
 * 지도 이동 (2026-10-23, owner spec). /지도 opens the map window; the server decides everything.
 *
 * <ul>
 *   <li>travel.yml (same file on every server except server-id): servers (logical id → Velocity name), tabs (학교 /
 *       야생1 / 야생2: which server+world, image map with reference corners or terrain), public points (id, name,
 *       server, world, x y z, radius). /서버관리 새로고침 이동 reloads it; /컨텐츠관리 이동지점 edits this server's copy.</li>
 *   <li>Unlock: the server checks every half second whether a player stands inside one of ITS points (players and
 *       points in the same world only) and records the first visit once in the shared DB. The client never says it.</li>
 *   <li>Personal point: one per player across all servers, set ONLY through {@link #setPersonal} (the future spell /
 *       an admin command) — there is no packet to set it.</li>
 *   <li>Moving: same server = teleport here. Another server = ticket in the shared DB, then the proxy connects the
 *       player (TravelLinkProtocol); the destination moves them once their character data is loaded and only then
 *       records DONE. Failures (server down / full / world reset / logout / timeout) are recorded as FAILED, never DONE.</li>
 * </ul>
 * Rows are never removed because a server or point left travel.yml: they show as unavailable with the reason.
 */
public final class TravelBridge implements Listener, PluginMessageListener, AutoCloseable {
    static final long TICKET_MS = 90_000, ORIGIN_TIMEOUT_TICKS = 45 * 20, ARRIVAL_WAIT_TRIES = 180;
    private final MagicCodexBridge plugin;
    private final File file;
    private TravelModel.Config config;
    private final TravelStore store;
    private final SecureRandom random = new SecureRandom();
    private final List<BukkitTask> tasks = new ArrayList<>();

    /** Unlocked public points per online player (null until loaded). */
    private final Map<UUID, Set<String>> unlocked = new HashMap<>();
    private final Set<String> unlocking = new HashSet<>();
    private final Map<UUID, Long> unlockRetryAt = new HashMap<>();
    /** One move at a time per player on this server: op from the client + ticket; charged = spatial_leap mana/cooldown taken. */
    private record Move(long op, long ticket, boolean remote, BukkitTask timeout, boolean charged) {}
    private final Map<UUID, Move> moves = new HashMap<>();
    private final Map<UUID, Long> lastTravel = new HashMap<>(), lastAsk = new HashMap<>(), lastOpen = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private Map<String, TravelModel.ServerStatus> status = Map.of();
    private ManaBridge mana;
    /** Discovery state key: number of distinct public points this player has unlocked (shared DB, every server). */
    static final String DISCOVERY_KEY = "state.travel_waypoints";
    /** 귀환권 (2026-10-25, 메모 #14): ticket point of a return move — arrival = that world's spawn, like 야생 스폰. */
    static final String RETURN_ID = "~return";
    /** Return moves started on this server whose ticket is still ours to refund (cleared on success / refund / leaving for the destination). */
    private final Set<UUID> returning = new HashSet<>();
    /**
     * 사망 귀환 (오너 2026-10-26): travel.yml death-return.from 서버(야생)에서 죽으면 침대·리스폰 앵커와 상관없이 무조건
     * to-server 의 to-world 스폰(Multiverse setspawn)으로 보낸다. from 에 없는 서버(학교·아레나 등)는 아무 일 없음 —
     * 아레나 큐디치·결투는 바닐라 사망 대신 자체 부활을 쓰고, 경기 중(GeneralPresetBridge.combat)이면 여기서도 건너뜀.
     * 비어 있으면 꺼짐. 귀환권과 같은 이동(표·프록시 이동·도착 확인)이지만 환불할 아이템이 없고 쿨타임을 보지 않는다.
     */
    private Set<String> deathFrom = Set.of();
    private String deathServer = "", deathWorld = "world";
    private final Set<UUID> deathMoves = new HashSet<>();
    /** The return-ticket item hook: gives the used ticket back when a return move fails (online → inventory, offline → mail). */
    interface ReturnTickets { void refund(UUID player, String why); void arrived(Player player); }
    private ReturnTickets returnTickets;
    void returnTickets(ReturnTickets hook) { returnTickets = hook; }
    private void returnFailed(UUID id, String why) { if (returning.remove(id) && returnTickets != null) returnTickets.refund(id, why); itemFailed(id, why); }
    /**
     * 리턴권·텔포권 (오너 2026-10-26, TravelItemsBridge): 표 지점 이름이 ~ 로 시작하는 아이템 이동. 리턴권 = {@link #DEATH_ID}
     * (표의 좌표 = 마지막 야생 사망 자리), 텔포권 = {@link #FRIEND_PREFIX}+UUID (도착 서버가 그때 그 유저의 위치를 읽음).
     * 귀환권과 같은 길(표·프록시 이동·도착 확인·기존 제한·전투 중 막기). 실패하면 훅이 그 아이템을 한 번 돌려준다
     * (출발 서버에서 실패 → 출발 서버가, 도착 서버에서 실패·시간 초과 → 도착 서버가 새 아이템으로).
     */
    static final String DEATH_ID = "~death", FRIEND_PREFIX = "~friend:";
    static boolean itemPoint(String point) { return point != null && (DEATH_ID.equals(point) || point.startsWith(FRIEND_PREFIX)); }
    interface ItemMoves { void refund(UUID player, String point, String why); void arrived(Player player, String point); String arrivalName(String point); }
    private ItemMoves itemMoves;
    void itemMoves(ItemMoves hook) { itemMoves = hook; }
    /** 이 서버에서 시작한 아이템 이동 (플레이어 → 지점). 성공·환불·도착 서버로 떠날 때 지운다. */
    private final Map<UUID, String> itemMoving = new HashMap<>();
    private void itemFailed(UUID id, String why) { String point = itemMoving.remove(id); if (point != null && itemMoves != null) itemMoves.refund(id, point, why); }
    private void itemArrivalFailed(UUID id, String point, String why) { if (itemPoint(point) && itemMoves != null) itemMoves.refund(id, point, why); }

    TravelBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "travel.yml");
        if (!file.exists()) plugin.saveResource("travel.yml", false);
        reload();
        store = new TravelStore(plugin, config.allowLocalDb());
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, TravelProtocol.REQUEST, this);
        messenger.registerOutgoingPluginChannel(plugin, TravelProtocol.RESPONSE);
        messenger.registerIncomingPluginChannel(plugin, TravelLinkProtocol.LINK, this);
        messenger.registerOutgoingPluginChannel(plugin, TravelLinkProtocol.LINK);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::proximity, 40, 10));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, 20, 15 * 20));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> store.servers(s -> { if (s != null) status = s; }), 30, 10 * 20));
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        CmdKit.register(plugin, "/지도·/컨텐츠관리 이동지점·/유저관리 이동지점", this::commands);
    }

    @Override public void close() {
        tasks.forEach(BukkitTask::cancel); tasks.clear();
        moves.values().forEach(m -> { if (m.timeout() != null) m.timeout().cancel(); });
        moves.clear();
        if (config != null) store.offline(config.self());
        store.close();
    }

    // ------------------------------------------------------------------ config

    void reload() throws Exception {
        var y = new YamlConfiguration();
        y.load(file);
        var next = TravelModel.parse(plain(y));
        if (config != null && !next.self().equals(config.self()))
            plugin.getLogger().warning("travel.yml server-id 가 " + config.self() + " → " + next.self() + " 로 바뀌었어요 (진행 중인 이동 표의 서버 이름이 맞지 않을 수 있어요)");
        config = next;
        deathFrom = Set.copyOf(y.getStringList("death-return.from"));
        deathServer = y.getString("death-return.to-server", "");
        deathWorld = y.getString("death-return.to-world", "world");
        if (!deathFrom.isEmpty() && next.server(deathServer) == null)
            plugin.getLogger().warning("travel.yml death-return.to-server 가 servers 에 없어요: " + deathServer + " (사망 귀환 꺼짐)");
        for (var t : next.tabs()) if (t.server().equals(next.self()) && Bukkit.getWorld(t.world()) == null)
            plugin.getLogger().warning("travel.yml 탭 " + t.id() + ": 이 서버에 세계 " + t.world() + " 가 없어요");
        for (var p : next.points().values()) if (p.server().equals(next.self()) && Bukkit.getWorld(p.world()) == null)
            plugin.getLogger().warning("travel.yml 지점 " + p.id() + ": 이 서버에 세계 " + p.world() + " 가 없어요");
        if (store == null) return;   // first load, from the constructor
        for (Player p : Bukkit.getOnlinePlayers()) { sendMarkers(p); if (viewers.contains(p.getUniqueId())) send(p, TravelProtocol.KIND_STATE, ""); }
    }
    private static Map<String, Object> plain(ConfigurationSection s) {
        var out = new LinkedHashMap<String, Object>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            out.put(k, v instanceof ConfigurationSection c ? plain(c) : v);
        }
        return out;
    }
    TravelModel.Config config() { return config; }

    private Map<String, String> localWorlds() {
        var out = new HashMap<String, String>();
        for (World w : Bukkit.getWorlds()) out.put(w.getName(), w.getUID().toString());
        return out;
    }
    private Map<String, double[]> localSpawns() {
        var out = new HashMap<String, double[]>();
        for (World w : Bukkit.getWorlds()) { var l = w.getSpawnLocation(); out.put(w.getName(), new double[]{l.getX(), l.getY(), l.getZ()}); }
        return out;
    }
    /** Where a spawn point is now (this server: the world spawn; another server: what it published). */
    private double[] spawnOf(TravelModel.PointDef d) {
        if (d.server().equals(config.self())) { World w = Bukkit.getWorld(d.world()); if (w != null) { var l = w.getSpawnLocation(); return new double[]{l.getX(), l.getY(), l.getZ()}; } }
        var s = status.get(d.server());
        double[] at = s == null ? null : s.spawns().get(d.world());
        return at != null ? at : new double[]{d.x(), d.y(), d.z()};
    }
    /** Arrival spot: a spawn point is the world spawn right now (Multiverse setspawn), anything else the ticket's place. */
    private Location destination(World w, TravelStore.Ticket t) {
        var d = config.points().get(t.point());
        if (d != null && d.spawn() || RETURN_ID.equals(t.point())) return w.getSpawnLocation().clone();
        if (t.point().startsWith(FRIEND_PREFIX)) return friendSpot(t.point());   // null = 그 유저에게 지금 갈 수 없음
        return new Location(w, t.x(), t.y(), t.z(), t.yaw(), t.pitch());
    }
    static final String FRIEND_GONE = "그 유저에게 갈 수 없어요 (나갔거나 갈 수 없는 곳에 있어요)";
    /** 텔포권 도착 자리: 이 서버에 있는 그 유저의 지금 위치. 없음·쓰러짐·관전·경기 중·지도에 없는 세계(던전·아레나 등)면 null. */
    private Location friendSpot(String point) {
        UUID target;
        try { target = UUID.fromString(point.substring(FRIEND_PREFIX.length())); } catch (IllegalArgumentException e) { return null; }
        Player t = Bukkit.getPlayer(target);
        if (t == null || !t.isOnline() || t.isDead() || t.getGameMode() == GameMode.SPECTATOR || GeneralPresetBridge.combat(target)) return null;
        if (config.tab(config.self(), t.getWorld().getName()) == null) return null;
        return t.getLocation().clone();
    }
    private void heartbeat() {
        if (!store.usable()) return;
        store.heartbeat(config.self(), Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), localWorlds(), localSpawns(), config.hash());
        writePresence();
    }
    /** 텔포권 목록용 이 서버 접속자 (관전·숨은 유저 빼고). 서버 상태와 같은 15초마다, 들어오고 나갈 때는 2초 안에 한 번 몰아서. */
    private boolean presenceDirty;
    private void writePresence() {
        presenceDirty = false;
        if (!store.usable()) return;
        var list = new ArrayList<TravelItemRules.Online>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || vanished(p)) continue;
            list.add(new TravelItemRules.Online(p.getUniqueId(), p.getWorld().getName(), plugin.names().name(p), plugin.dorm(p)));
        }
        store.presence(config.self(), TravelItemRules.pack(list));
    }
    private void presenceSoon() {
        if (presenceDirty || !store.usable()) return;
        presenceDirty = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (presenceDirty) writePresence(); }, 40);
    }
    /** 숨기 플러그인(SuperVanish·Essentials 등)이 다는 "vanished" 표시. */
    static boolean vanished(Player p) {
        try { for (var m : p.getMetadata("vanished")) if (m.asBoolean()) return true; } catch (RuntimeException ignored) {}
        return false;
    }
    /** 모든 서버 접속자 줄 (텔포권). null = 공유 저장소 오류. */
    void presenceAll(Consumer<List<TravelItemRules.Presence>> done) { store.presenceAll(done); }
    /** 리턴권: 마지막 야생 사망 자리 저장·읽기 (공유 DB). */
    void saveDeath(UUID player, TravelItemRules.DeathPoint d, Consumer<Boolean> done) { store.saveDeath(player, d, done); }
    void death(UUID player, Consumer<Optional<TravelItemRules.DeathPoint>> done) { store.death(player, done); }
    boolean storeReady() { return store.ready(); }
    /** Servers whose travel.yml differs from this one (admins see it in /서버관리 상태 and the point list). */
    List<String> mismatched() {
        long now = System.currentTimeMillis();
        var out = new ArrayList<String>();
        status.values().forEach(s -> { if (s.alive(now) && !s.server().equals(config.self()) && !s.configHash().isEmpty() && !s.configHash().equals(config.hash())) out.add(s.server()); });
        return out;
    }

    // ------------------------------------------------------------------ players

    /**
     * The client registers our channel a moment AFTER join, so markers sent at join (portal marks + the minimap key) were
     * dropped (modded() false). Send them again as soon as the channel is registered (same fix as ShinyBridge).
     */
    @EventHandler public void onChannel(PlayerRegisterChannelEvent e) {
        if (!TravelProtocol.RESPONSE.equals(e.getChannel())) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) sendMarkers(p); });
    }

    /** Personal point per online player, so opening the map needs no DB round trip (filled on join, kept on every change). */
    private final Map<UUID, Optional<TravelModel.Personal>> personalCache = new HashMap<>();
    private void loadPersonal(Player p) {
        UUID id = p.getUniqueId();
        store.personal(id, v -> { if (v != null && Bukkit.getPlayer(id) == p) personalCache.put(id, v); });
    }

    private void load(Player p) {
        UUID id = p.getUniqueId();
        loadPersonal(p);
        store.unlocks(id, set -> {
            if (Bukkit.getPlayer(id) != p) return;
            if (set == null) { unlockRetryAt.put(id, System.currentTimeMillis() + 10_000); return; }
            unlocked.put(id, set);
            sendMarkers(p);
            discovery(p, 0);   // re-check on every load: players who already have 1+ get 공간 도약 too
        });
    }

    /**
     * 공간 도약 획득 조건 (owner 2026-10-24: 1곳, 예전 3곳): distinct PUBLIC points unlocked, counted from the shared DB rows (uuid, point)
     * — revisits, reconnects and server changes add nothing; the personal point is a separate table and never counts.
     * Sent as an idempotent state value to the existing discovery system (requirement state.travel_waypoints: 1 in
     * discoveries.yml), which grants the spell once and shows its usual "새로운 마법 발견" notice. The discovery session
     * may still be loading right after join: retried every 2 s, 10 times.
     */
    private void discovery(Player p, int retry) {
        Set<String> set = unlocked.get(p.getUniqueId());
        if (set == null || !p.isOnline()) return;
        if (DiscoveryLink.state(p.getUniqueId(), DISCOVERY_KEY, set.size())) return;
        if (retry < 10) Bukkit.getScheduler().runTaskLater(plugin, () -> discovery(p, retry + 1), 40);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        load(p);
        awaitArrival(p, 0);
        presenceSoon();
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        unlocked.remove(id); unlockRetryAt.remove(id); viewers.remove(id); lastAsk.remove(id); lastOpen.remove(id); personalCache.remove(id);
        unlocking.removeIf(k -> k.startsWith(id + "|"));
        // a move in progress keeps its ticket: the destination finishes it (or it expires as FAILED)
        Move m = moves.remove(id);
        if (m != null && m.timeout() != null) m.timeout().cancel();
        if (m != null && m.remote()) { returning.remove(id); itemMoving.remove(id); }   // 귀환권·리턴권·텔포권 on the way: the destination (or ticket expiry) settles it
        presenceSoon();
    }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { sendMarkers(e.getPlayer()); }

    // ------------------------------------------------------------------ unlock (server decides)

    private void proximity() {
        if (!store.ready()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Set<String> set = unlocked.get(id);
            if (set == null) { Long retry = unlockRetryAt.get(id); if (retry != null && now >= retry) { unlockRetryAt.remove(id); load(p); } continue; }
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            Location at = p.getLocation();
            for (var point : config.localPoints(at.getWorld().getName())) {
                if (set.contains(point.id()) || !TravelModel.inside(point, at.getX(), at.getY(), at.getZ())) continue;
                String key = id + "|" + point.id();
                if (!unlocking.add(key)) continue;
                store.unlock(id, point.id(), config.self(), claim -> {
                    unlocking.remove(key);
                    Set<String> current = unlocked.get(id);
                    if (claim == TravelStore.Claim.ERROR || current == null) return;
                    current.add(point.id());
                    if (claim == TravelStore.Claim.CLAIMED) discovery(p, 0);
                    if (claim == TravelStore.Claim.CLAIMED && p.isOnline()) {
                        CmdKit.notify(p, "system", "travel-unlock-" + point.id(), "웨이포인트 등록", point.name(), 0);
                        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, .8f, 1.2f);
                        plugin.getLogger().info("이동 지점 해금: " + p.getName() + " → " + point.id());
                        StoryProgressEvent.fire(p, StoryProgressEvent.WAYPOINT);
                    }
                    sendMarkers(p);
                    // owner: the map shows the same notice when it is open
                    if (viewers.contains(id)) send(p, TravelProtocol.KIND_STATE, claim == TravelStore.Claim.CLAIMED ? "웨이포인트 등록 · " + point.name() : "");
                });
            }
        }
    }

    // ------------------------------------------------------------------ packets

    private static boolean modded(Player p) { return p.getListeningPluginChannels().contains(TravelProtocol.RESPONSE); }
    private void frame(Player p, TravelProtocol.Frame f) {
        try { p.sendPluginMessage(plugin, TravelProtocol.RESPONSE, TravelProtocol.encode(f)); }
        catch (IllegalArgumentException tooLarge) { plugin.getLogger().warning("지도 이동 패킷을 보낼 수 없어요: " + tooLarge.getMessage()); }
    }
    private void result(Player p, long op, int stage, String notice) { if (op != 0) frame(p, new TravelProtocol.Result(op, stage, notice)); }

    void sendMarkers(Player p) {
        if (!modded(p)) return;
        Set<String> set = unlocked.getOrDefault(p.getUniqueId(), Set.of());
        String world = p.getWorld().getName();
        var list = new ArrayList<TravelProtocol.Marker>();
        for (var point : config.localPoints(world))
            list.add(new TravelProtocol.Marker(point.id(), set.contains(point.id()) || config.revealLockedNames() ? point.name() : "미발견 지점",
                    point.x(), point.y(), point.z(), (float) point.radius(), set.contains(point.id())));
        var tab = config.tab(config.self(), world);
        String cache = tab == null ? "" : cacheKey(tab, localWorlds());
        frame(p, new TravelProtocol.Markers(TravelProtocol.validWorld(world) ? world : "", cache, list));
    }

    /** Opens (or refreshes) the window. */
    boolean open(Player p) {
        if (!modded(p)) { CmdKit.notify(p, "warning", "travel-mod", "지도를 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return false; }
        if (!store.usable()) { CmdKit.notify(p, "error", "travel-db", "지도 이동을 쓸 수 없어요", "관리자에게 알려 주세요 (공유 저장소 연결 없음)", 3000); return false; }
        viewers.add(p.getUniqueId());
        sendMarkers(p);   // also repairs a minimap key lost at join
        send(p, TravelProtocol.KIND_OPEN, "");
        StoryProgressEvent.fire(p, StoryProgressEvent.MAP_OPEN);
        return true;
    }

    /**
     * Builds the state from the cached unlocks / personal point (filled on join, kept current on every change) and the
     * cached server status, in this tick — no DB round trip when the window opens. Only a player whose cache is not
     * loaded yet waits for one read.
     */
    private void send(Player p, int kind, String notice) {
        UUID id = p.getUniqueId();
        var cached = personalCache.get(id);
        if (cached != null) { build(p, kind, notice, cached); return; }
        store.personal(id, personal -> {
            if (!p.isOnline()) return;
            if (personal != null) personalCache.put(id, personal);
            build(p, kind, personal == null && notice.isEmpty() ? "개인 지점을 불러오지 못했어요" : notice, personal == null ? Optional.<TravelModel.Personal>empty() : personal);
        });
    }
    private void build(Player p, int kind, String notice, Optional<TravelModel.Personal> personal) {
        Set<String> set = unlocked.get(p.getUniqueId());
        String note = notice;
        if (set == null) { set = Set.of(); if (note.isEmpty()) note = "발견한 지점을 불러오는 중이에요…"; load(p); }
        try { frame(p, state(p, kind, set, personal, note)); }
        catch (IllegalArgumentException bad) { plugin.getLogger().warning("지도 상태 만들기 실패: " + bad.getMessage()); }
    }

    TravelProtocol.State state(Player p, int kind, Set<String> set, Optional<TravelModel.Personal> personal, String notice) {
        long now = System.currentTimeMillis();
        var worlds = localWorlds();
        var tabs = new ArrayList<TravelProtocol.Tab>();
        for (var t : config.tabs()) {
            double[] c = t.corners(), b = t.bounds();
            boolean online = TravelModel.destination(config, t.server(), t.world(), "", status, worlds, now).state() == TravelProtocol.UNLOCKED;
            String why = online ? "" : TravelModel.destination(config, t.server(), t.world(), "", status, worlds, now).reason();
            tabs.add(new TravelProtocol.Tab(t.id(), t.label(), t.server(), t.world(), t.map(), t.texture(), t.imageW(), t.imageH(),
                    c[0], c[1], c[2], c[3], c[4], c[5], cacheKey(t, worlds), b[0], b[1], b[2], b[3], online, why));
        }
        var points = new ArrayList<TravelProtocol.Point>();
        for (var d : config.points().values()) {
            boolean known = d.spawn() || set.contains(d.id());
            if (!d.enabled() && !known) continue;
            var v = TravelModel.publicPoint(config, d, known, status, worlds, now);
            var tab = config.tab(d.server(), d.world());
            String name = known || config.revealLockedNames() ? d.name() : "미발견 지점";
            double[] at = d.spawn() ? spawnOf(d) : new double[]{d.x(), d.y(), d.z()};
            points.add(new TravelProtocol.Point(d.id(), name, tab == null ? "" : tab.id(), TravelProtocol.POINT_PUBLIC, v.state(), at[0], at[1], at[2], v.reason()));
        }
        personal.ifPresent(m -> {
            var v = TravelModel.personal(config, m, status, worlds, now);
            var tab = config.tab(m.server(), m.world());
            points.add(new TravelProtocol.Point(TravelProtocol.PERSONAL_ID, "개인 지점", tab == null ? "" : tab.id(), TravelProtocol.POINT_PERSONAL,
                    v.state(), m.x(), m.y(), m.z(), v.reason()));
        });
        var here = config.tab(config.self(), p.getWorld().getName());
        var at = p.getLocation();
        var pos = new TravelProtocol.Position(here == null ? "" : here.id(), at.getX(), at.getZ(), at.getYaw());
        return new TravelProtocol.State(kind, pos, tabs, points, notice.length() > TravelProtocol.MAX_TEXT ? notice.substring(0, TravelProtocol.MAX_TEXT) : notice);
    }
    /** Minimap cache key: server + world + that world's UID, so a reset world or another wild never reuses old tiles. */
    private String cacheKey(TravelModel.TabDef t, Map<String, String> worlds) {
        if (t.map() != TravelProtocol.MAP_TERRAIN) return "";
        String uid = t.server().equals(config.self()) ? worlds.getOrDefault(t.world(), "unknown") : uidOf(t.server(), t.world());
        String key = t.server() + "/" + t.world() + "/" + uid;
        return key.length() > TravelProtocol.MAX_TEXTURE ? "" : key;
    }
    private String uidOf(String server, String world) {
        var s = status.get(server);
        return s == null ? "unknown" : s.worlds().getOrDefault(world, "unknown");
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (TravelLinkProtocol.LINK.equals(channel)) { link(p, data); return; }
        if (!TravelProtocol.REQUEST.equals(channel) || data == null || data.length > TravelProtocol.MAX_REQUEST_BYTES) return;
        TravelProtocol.Request r;
        try { r = TravelProtocol.request(data); } catch (IllegalArgumentException invalid) { return; }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        switch (r.action()) {
            case TravelProtocol.CLOSE -> viewers.remove(id);
            case TravelProtocol.OPEN -> {   // the map key (J) / top menu 이동: same as /지도 (the client already shows the window)
                if (now - lastOpen.getOrDefault(id, 0L) < 200) return;   // only a key-repeat guard: a quick reopen must still answer
                lastOpen.put(id, now);
                open(p);
            }
            case TravelProtocol.QUERY -> {
                if (!viewers.contains(id) || now - lastAsk.getOrDefault(id, 0L) < 1000) return;
                lastAsk.put(id, now);
                send(p, TravelProtocol.KIND_STATE, "");
            }
            case TravelProtocol.TRAVEL -> {
                if (!viewers.contains(id)) return;   // only from a window this server opened
                travel(p, r.pointId(), r.operation());
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ travelling

    private void travel(Player p, String pointId, long op) {
        UUID id = p.getUniqueId();
        if (moves.containsKey(id)) { result(p, op, TravelProtocol.STAGE_FAILED, "이미 이동하는 중이에요"); return; }
        String combat = combatRefusal(p);   // 메모 #91: 전투 중 지도 이동(공개·개인 지점·야생 스폰) 막기 — 마나·쿨 쓰기 전
        if (combat != null) { result(p, op, TravelProtocol.STAGE_FAILED, combat); return; }
        var def = config.points().get(pointId);
        boolean free = def != null && def.spawn();   // 야생 스폰: no travel cooldown (owner 2026-10-24)
        String refusal = refusal(p, free);
        if (refusal != null) { result(p, op, TravelProtocol.STAGE_FAILED, refusal); return; }
        // hold the slot now: a second click while the DB reads run is refused above
        moves.put(id, new Move(op, 0, false, null, false));
        if (TravelProtocol.PERSONAL_ID.equals(pointId)) {
            store.personal(id, personal -> {
                if (personal == null) { abort(p, op, "개인 지점을 불러오지 못했어요"); return; }
                if (personal.isEmpty()) { abort(p, op, "개인 지점이 아직 없어요"); return; }
                var m = personal.get();
                go(p, op, TravelProtocol.PERSONAL_ID, m.server(), m.world(), m.worldUid(), m.x(), m.y(), m.z(), m.yaw(), m.pitch(),
                        TravelModel.personal(config, m, status, localWorlds(), System.currentTimeMillis()));
            });
            return;
        }
        var d = def;
        if (d == null) { abort(p, op, "없는 지점이에요"); return; }
        if (d.spawn()) {   // always open, no unlock row; the destination resolves the world spawn on arrival
            go(p, op, d.id(), d.server(), d.world(), "", d.x(), d.y(), d.z(), d.yaw(), d.pitch(),
                    TravelModel.publicPoint(config, d, true, status, localWorlds(), System.currentTimeMillis()));
            return;
        }
        // the unlock is read from the DB again: never trust the cache alone (nor the client) for a move
        store.unlocks(id, set -> {
            if (set == null) { abort(p, op, "발견한 지점을 확인하지 못했어요"); return; }
            if (unlocked.containsKey(id)) unlocked.get(id).addAll(set);
            go(p, op, d.id(), d.server(), d.world(), "", d.x(), d.y(), d.z(), d.yaw(), d.pitch(),
                    TravelModel.publicPoint(config, d, set.contains(d.id()), status, localWorlds(), System.currentTimeMillis()));
        });
    }
    private void abort(Player p, long op, String why) {
        Move m = moves.remove(p.getUniqueId());
        if (m != null && m.charged()) refund(p);
        returnFailed(p.getUniqueId(), why);
        if (p.isOnline()) result(p, op, TravelProtocol.STAGE_FAILED, why);
    }

    /**
     * 귀환권 (메모 #14): the same move as the map (ticket, proxy transfer, arrival checks, existing limits), to the spawn
     * of `world` on `server`. No window: op 0, the item hook tells the player. null = started (a failure later calls
     * the hook's refund once), otherwise the refusal and nothing was started.
     */
    String travelReturn(Player p, String server, String world) {
        UUID id = p.getUniqueId();
        if (moves.containsKey(id) || returning.contains(id)) return "이미 이동하는 중이에요";
        String combat = combatRefusal(p);   // 메모 #91 (ReturnTicketBridge 가 아이템을 쓰기 전에 먼저 검사함)
        if (combat != null) return combat;
        String refusal = refusal(p);
        if (refusal != null) return refusal;
        var verdict = TravelModel.destination(config, server, world, "", status, localWorlds(), System.currentTimeMillis());
        if (verdict.state() != TravelProtocol.UNLOCKED) return verdict.reason();
        returning.add(id);
        moves.put(id, new Move(0, 0, false, null, false));
        go(p, 0, RETURN_ID, server, world, "", 0, 64, 0, 0, 0, verdict);
        return null;
    }
    /**
     * 리턴권·텔포권 (2026-10-26): 귀환권과 같은 이동으로 point 에 (지점 이름은 {@link #itemPoint}). 리턴권은 표의 좌표,
     * 텔포권은 도착 서버가 그 유저 위치를 읽는다 (x y z 는 안 씀, world = 그 유저가 있던 세계). worldUid 가 있으면 세계가
     * 초기화됐을 때 거절. null = 시작함 (뒤에 실패하면 훅 refund 한 번 — 이 호출 안에서 바로일 수도 있음), 아니면 거절 문구.
     */
    String travelItem(Player p, String point, String server, String world, String worldUid, double x, double y, double z, float yaw, float pitch) {
        UUID id = p.getUniqueId();
        if (!itemPoint(point)) return "이동할 곳이 잘못됐어요";
        if (moves.containsKey(id) || returning.contains(id) || itemMoving.containsKey(id)) return "이미 이동하는 중이에요";
        String combat = combatRefusal(p);   // 메모 #91 (TravelItemsBridge 가 아이템을 쓰기 전에 먼저 검사함)
        if (combat != null) return combat;
        String refusal = refusal(p);
        if (refusal != null) return refusal;
        String uid = worldUid == null ? "" : worldUid;
        var verdict = TravelModel.destination(config, server, world, uid, status, localWorlds(), System.currentTimeMillis());
        if (verdict.state() != TravelProtocol.UNLOCKED) return verdict.reason();
        itemMoving.put(id, point);
        moves.put(id, new Move(0, 0, false, null, false));
        go(p, 0, point, server, world, uid, x, y, z, yaw, pitch, verdict);
        return null;
    }
    /** 사망 귀환을 이 서버에서 쓰는지 (travel.yml death-return). */
    boolean deathReturnHere() { return deathFrom.contains(config.self()) && config.server(deathServer) != null && !deathServer.equals(config.self()); }

    /** 야생에서 죽은 뒤 되살아날 때: 일단 이 세계 스폰(침대 무시)에 세우고, 곧바로 학교 스폰으로 보낸다. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!deathReturnHere()) return;
        Player p = e.getPlayer();
        if (GeneralPresetBridge.combat(p.getUniqueId())) return;   // 경기(큐디치·결투) 중 사망은 경기 규칙대로
        e.setRespawnLocation(e.getRespawnLocation().getWorld() != null ? e.getRespawnLocation().getWorld().getSpawnLocation() : p.getWorld().getSpawnLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> deathReturn(p, 0), 10);
    }
    private void deathReturn(Player p, int tries) {
        if (!p.isOnline() || !deathReturnHere()) return;
        UUID id = p.getUniqueId();
        String why = null;
        if (moves.containsKey(id) || returning.contains(id)) why = "이미 이동하는 중이에요";
        else {
            why = refusal(p, true);
            if (why == null) {
                var verdict = TravelModel.destination(config, deathServer, deathWorld, "", status, localWorlds(), System.currentTimeMillis());
                if (verdict.state() != TravelProtocol.UNLOCKED) why = verdict.reason();
                else {
                    deathMoves.add(id);
                    moves.put(id, new Move(0, 0, false, null, false));
                    go(p, 0, RETURN_ID, deathServer, deathWorld, "", 0, 64, 0, 0, 0, verdict);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> deathMoves.remove(id), 20);
                    return;
                }
            }
        }
        if (tries < 5) { Bukkit.getScheduler().runTaskLater(plugin, () -> deathReturn(p, tries + 1), 40); return; }
        plugin.getLogger().warning("사망 귀환 실패: " + p.getName() + " — " + why);
        CmdKit.notify(p, "system", "travel-death", "학교로 돌아가지 못했어요", why + " · 지도에서 직접 이동해 주세요", 0);
    }

    /** 전투 중 이동 막기 (CombatTag, combat-lock.yml). 막으면 알림창에 알리고 문구, 아니면 null. */
    private String combatRefusal(Player p) { var c = plugin.combatTag(); return c == null ? null : c.refuse(p); }

    private void refund(Player p) { if (mana != null && p.isOnline()) mana.refundPersonalTravel(p, config.rules().personalMana()); }

    /** Existing limits a move must not get around (owner: 기존 제한 우회 금지). null = fine. */
    private String refusal(Player p) { return refusal(p, false); }
    private String refusal(Player p, boolean noCooldown) {
        if (!store.ready()) return "공유 저장소 연결이 없어 이동할 수 없어요";
        if (p.isDead()) return "지금은 이동할 수 없어요";
        if (p.getGameMode() == GameMode.SPECTATOR) return "관전 중에는 이동할 수 없어요";
        if (!plugin.playerStateReady(p)) return "캐릭터 정보를 불러오는 중이에요…";
        if (GeneralPresetBridge.combat(p.getUniqueId())) return "경기 중에는 이동할 수 없어요";
        var bags = plugin.bagPagesBridge();   // 사망 뒤 배낭 다른 쪽 떨어뜨리는 중 (2026-10-26): 끝난 뒤 이동 (사망 귀환은 2초마다 다시 시도)
        if (bags != null && bags.deathBusy(p.getUniqueId())) return "쓰러진 뒤 배낭을 정리하는 중이에요. 잠시 뒤 다시 해 주세요";
        if (config.rules().blockInCombat() && plugin.inCombat(p)) return "전투 중에는 이동할 수 없어요";
        long left = noCooldown ? 0 : TravelModel.cooldownLeft(config.rules(), lastTravel.getOrDefault(p.getUniqueId(), 0L), System.currentTimeMillis());
        if (left > 0) return "다시 이동하려면 " + (left + 999) / 1000 + "초 기다려 주세요";
        return null;
    }

    private void go(Player p, long op, String point, String server, String world, String worldUid, double x, double y, double z, float yaw, float pitch,
                    TravelModel.Verdict verdict) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) { moves.remove(id); returnFailed(id, "출발 전에 접속이 끊겼어요"); return; }
        if (verdict.state() != TravelProtocol.UNLOCKED) { abort(p, op, verdict.reason()); return; }
        var pdef = config.points().get(point);
        String again = refusal(p, pdef != null && pdef.spawn() || RETURN_ID.equals(point) && deathMoves.contains(id));
        if (again != null) { abort(p, op, again); return; }
        // 메모 #91: DB 확인 사이에 전투가 시작됐을 수 있어 한 번 더 (사망 귀환은 막지 않음). 개인 지점 마나는 아래에서 쓰므로 아직 안 씀.
        if (!(RETURN_ID.equals(point) && deathMoves.contains(id))) {
            String combat = combatRefusal(p);
            if (combat != null) { abort(p, op, combat); return; }
        }
        // 개인 지점으로 이동만 마나·쿨 (owner 2026-10-24: 지정은 무료; travel.yml rules.personal-travel-*); refunded if the move fails
        boolean charged = false;
        if (TravelProtocol.PERSONAL_ID.equals(point)) {
            var rules = config.rules();
            Integer charge = mana == null ? null : mana.chargePersonalTravel(p, rules.personalMana(), rules.personalCooldownSeconds());
            if (charge == null) { abort(p, op, "공간 도약 마법이 꺼져 있어 개인 지점으로 이동할 수 없어요"); return; }
            if (charge != school.magiccodex.protocol.ManaProtocol.OK) {
                String why = switch (charge) {
                    case school.magiccodex.protocol.ManaProtocol.LOCKED -> "공간 도약 마법을 배워야 개인 지점으로 이동할 수 있어요";
                    case school.magiccodex.protocol.ManaProtocol.COOLDOWN -> "개인 지점 이동은 " + (mana.personalTravelCooldown(p) + 999) / 1000 + "초 뒤에 다시 할 수 있어요";
                    case school.magiccodex.protocol.ManaProtocol.EMPTY -> "마나가 부족해요 (개인 지점 이동 마나 " + Math.round(rules.personalMana()) + ")";
                    default -> ManaBridge.message(charge);
                };
                abort(p, op, why);
                return;
            }
            charged = true;
            moves.put(id, new Move(op, 0, false, null, true));
        }
        long now = System.currentTimeMillis();
        long ticket; do ticket = random.nextLong(); while (ticket == 0);
        boolean remote = !server.equals(config.self());
        var t = new TravelStore.Ticket(ticket, point, config.self(), server, world, worldUid, x, y, z, yaw, pitch, now, now + TICKET_MS,
                remote ? TravelStore.PENDING : TravelStore.ARRIVING, "");
        long tk = ticket;
        boolean chargedNow = charged;
        store.openTicket(id, t, opened -> {
            if (!opened) { abort(p, op, "이미 진행 중인 이동이 있어요. 잠시 후 다시 시도해 주세요"); return; }
            if (!p.isOnline()) { store.finish(id, t, t.state(), TravelStore.FAILED, "출발 전에 접속이 끊겼어요", null); moves.remove(id); returnFailed(id, "출발 전에 접속이 끊겼어요"); return; }
            if (!remote) { local(p, op, t); return; }
            var def = config.server(server);
            var timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> originTimeout(p, op, tk), ORIGIN_TIMEOUT_TICKS);
            moves.put(id, new Move(op, tk, true, timeout, chargedNow));
            stopBroom(p);
            try { p.sendPluginMessage(plugin, TravelLinkProtocol.LINK, TravelLinkProtocol.encode(new TravelLinkProtocol.Transfer(id, tk, def.proxy()))); }
            catch (RuntimeException e) { timeout.cancel(); failOrigin(p, op, tk, "이동 요청을 보내지 못했어요"); return; }
            result(p, op, TravelProtocol.STAGE_ACCEPTED, def.label() + "(으)로 이동하는 중…");
            plugin.getLogger().info("지도 이동 요청: " + p.getName() + " → " + server + "/" + point + " (표 " + Long.toHexString(tk) + ")");
            AuditLog.log(p, AuditLog.접속, "서버 이동 요청 · " + config.self() + " → " + server + " (" + point + ")", "출발", config.self(), "도착", server, "지점", point, "위치", AuditLog.where(p.getLocation()));
            AuditStats.add(id, "서버이동", 1);
        });
    }

    /** Same server: teleport (cause PLUGIN, so match / protection listeners still apply), then record the outcome. */
    private void local(Player p, long op, TravelStore.Ticket t) {
        UUID id = p.getUniqueId();
        World w = Bukkit.getWorld(t.world());
        String why = w == null ? "그 세계가 지금 없어요" : !t.worldUid().isEmpty() && !t.worldUid().equals(w.getUID().toString()) ? "세계가 초기화되어 그 지점은 쓸 수 없어요" : null;
        if (why != null) { store.finish(id, t, TravelStore.ARRIVING, TravelStore.FAILED, why, null); abort(p, op, why); return; }
        Location to = destination(w, t);
        if (to == null) { store.finish(id, t, TravelStore.ARRIVING, TravelStore.FAILED, FRIEND_GONE, null); abort(p, op, FRIEND_GONE); return; }
        stopBroom(p);
        p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean done = err == null && Boolean.TRUE.equals(ok);
                    store.finish(id, t, TravelStore.ARRIVING, done ? TravelStore.DONE : TravelStore.FAILED, done ? "" : "이동이 막혔어요", null);
                    Move m = moves.remove(id);
                    if (!done && m != null && m.charged()) refund(p);
                    if (done) {
                        if (returning.remove(id) && returnTickets != null && p.isOnline()) returnTickets.arrived(p);
                        String item = itemMoving.remove(id);
                        if (item != null && itemMoves != null && p.isOnline()) itemMoves.arrived(p, item);
                    }
                    else returnFailed(id, "이동이 막혔어요");
                    if (!p.isOnline()) return;
                    if (done) { p.setFallDistance(0); if (!isSpawn(t.point())) lastTravel.put(id, System.currentTimeMillis()); arrivedEffect(p); }
                    if (done && m != null && m.charged()) AuditStats.add(id, "개인지점이동", 1); // 공간 도약 개인 지점 이동 (마법 발견 v2 섬광 도약)
                    result(p, op, done ? TravelProtocol.STAGE_DONE : TravelProtocol.STAGE_FAILED, done ? "도착했어요" : "이동이 막혔어요 (이 구역에서는 이동할 수 없어요)");
                }));
    }

    private void originTimeout(Player p, long op, long ticket) {
        UUID id = p.getUniqueId();
        Move m = moves.get(id);
        if (m == null || m.ticket() != ticket || Bukkit.getPlayer(id) != p) return;   // left = on the way; the destination finishes it
        failOrigin(p, op, ticket, "서버 연결 응답이 없어요");
    }
    private void failOrigin(Player p, long op, long ticket, String why) {
        UUID id = p.getUniqueId();
        Move m = moves.get(id);
        boolean charged = m != null && m.ticket() == ticket && m.charged();
        boolean ret = m != null && m.ticket() == ticket && returning.remove(id);
        String item = m != null && m.ticket() == ticket ? itemMoving.remove(id) : null;
        if (m != null && m.ticket() == ticket) { if (m.timeout() != null) m.timeout().cancel(); moves.remove(id); }
        store.fail(id, ticket, why, done -> {
            if (done && ret && returnTickets != null) returnTickets.refund(id, why);   // this call failed it: the 귀환권 comes back once
            if (done && item != null && itemMoves != null) itemMoves.refund(id, item, why);   // 리턴권·텔포권도 한 번
            if (!done || !p.isOnline()) return;   // already finished (e.g. it did arrive) — never overwrite
            if (charged) refund(p);
            result(p, op, TravelProtocol.STAGE_FAILED, why);
            plugin.getLogger().info("지도 이동 실패: " + p.getName() + " (표 " + Long.toHexString(ticket) + ") " + why);
        });
    }

    /** Proxy → origin: it could not connect the player. Only a proxy connection can send it (the proxy swallows client copies). */
    private void link(Player p, byte[] data) {
        TravelLinkProtocol.Message m;
        try { m = TravelLinkProtocol.decode(data); } catch (IllegalArgumentException invalid) { return; }
        if (!(m instanceof TravelLinkProtocol.Failed f) || !f.player().equals(p.getUniqueId())) return;
        Move move = moves.get(p.getUniqueId());
        if (move == null || move.ticket() != f.ticket()) return;
        String why = switch (f.reason()) {
            case TravelLinkProtocol.NO_SERVER -> "이동할 서버를 찾을 수 없어요";
            case TravelLinkProtocol.OFFLINE -> "도착 서버가 닫혀 있어요";
            case TravelLinkProtocol.REFUSED -> f.detail().isBlank() ? "도착 서버가 접속을 받지 않아요 (정원 초과일 수 있어요)" : "도착 서버가 접속을 거절했어요: " + f.detail();
            case TravelLinkProtocol.BUSY -> f.detail().isBlank() ? "지금은 서버를 옮길 수 없어요" : f.detail();
            default -> "서버를 옮기지 못했어요";
        };
        failOrigin(p, move.op(), f.ticket(), why.length() > 110 ? why.substring(0, 110) : why);
    }

    // ------------------------------------------------------------------ arrival (destination server)

    /** Waits until the character data is loaded here (lease taken over from the old server), then reads the ticket. */
    private void awaitArrival(Player p, int tries) {
        if (!store.usable()) return;
        if (!p.isOnline()) return;
        if (!store.ready() || !plugin.playerStateReady(p)) {
            if (tries >= ARRIVAL_WAIT_TRIES) {
                plugin.getLogger().warning("지도 이동 도착 확인 포기 (캐릭터 정보 미준비): " + p.getName());
                if (store.ready()) store.failPending(p.getUniqueId(), config.self(), "도착 서버에서 캐릭터 정보를 불러오지 못했어요", null);
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> awaitArrival(p, tries + 1), 10);
            return;
        }
        UUID id = p.getUniqueId();
        store.claimArrival(id, config.self(), arrival -> {
            if (arrival.ticket() != null) arrive(p, arrival.ticket());
            else if (arrival.expired() != null && RETURN_ID.equals(arrival.expired().point()) && returnTickets != null) returnTickets.refund(id, "이동 시간이 지났어요");
            else if (arrival.expired() != null && itemPoint(arrival.expired().point())) itemArrivalFailed(id, arrival.expired().point(), "이동 시간이 지났어요");
            else if (arrival.expired() != null && TravelProtocol.PERSONAL_ID.equals(arrival.expired().point()) && p.isOnline()) {
                refund(p);   // a move to the personal point that never arrived: mana and cooldown back
                CmdKit.notify(p, "warning", "travel-arrive", "개인 지점 이동이 끝나지 않았어요", "마나·재사용 대기는 돌려받았어요", 0);
            }
        });
    }
    private void arrive(Player p, TravelStore.Ticket t) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            store.finish(id, t, TravelStore.ARRIVING, TravelStore.FAILED, "도착 전에 접속이 끊겼어요", null);
            if (RETURN_ID.equals(t.point()) && returnTickets != null) returnTickets.refund(id, "도착 전에 접속이 끊겼어요");
            itemArrivalFailed(id, t.point(), "도착 전에 접속이 끊겼어요");
            return;
        }
        World w = Bukkit.getWorld(t.world());
        String why = w == null ? "도착할 세계가 없어요" : !t.worldUid().isEmpty() && !t.worldUid().equals(w.getUID().toString()) ? "세계가 초기화되어 그 지점은 쓸 수 없어요" : null;
        if (why != null) {
            store.finish(id, t, TravelStore.ARRIVING, TravelStore.FAILED, why, null);
            if (TravelProtocol.PERSONAL_ID.equals(t.point())) refund(p);
            if (RETURN_ID.equals(t.point()) && returnTickets != null) returnTickets.refund(id, why);
            itemArrivalFailed(id, t.point(), why);
            CmdKit.notify(p, "error", "travel-arrive", "목적지에 도착하지 못했어요", why, 0);
            return;
        }
        Location to = destination(w, t);
        if (to == null) {
            store.finish(id, t, TravelStore.ARRIVING, TravelStore.FAILED, FRIEND_GONE, null);
            itemArrivalFailed(id, t.point(), FRIEND_GONE);
            CmdKit.notify(p, "error", "travel-arrive", "목적지에 도착하지 못했어요", FRIEND_GONE + " · 이 서버의 처음 위치에 있어요", 0);
            return;
        }
        stopBroom(p);
        p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean done = err == null && Boolean.TRUE.equals(ok) && p.isOnline();
                    store.finish(id, t, TravelStore.ARRIVING, done ? TravelStore.DONE : TravelStore.FAILED, done ? "" : "도착 위치로 옮기지 못했어요", null);
                    plugin.getLogger().info("지도 이동 " + (done ? "도착" : "도착 실패") + ": " + p.getName() + " ← " + t.from() + " (" + t.point() + ", 표 " + Long.toHexString(t.ticket()) + ")");
                    if (!done && RETURN_ID.equals(t.point()) && returnTickets != null) returnTickets.refund(id, "도착 위치로 옮기지 못했어요");
                    if (!done) itemArrivalFailed(id, t.point(), "도착 위치로 옮기지 못했어요");
                    if (!p.isOnline()) return;
                    if (done) {
                        p.setFallDistance(0); if (!isSpawn(t.point())) lastTravel.put(id, System.currentTimeMillis()); arrivedEffect(p);
                        CmdKit.notify(p, "system", "travel-arrive", "도착했어요", t.point().equals(TravelProtocol.PERSONAL_ID) ? "개인 지점" : RETURN_ID.equals(t.point()) ? "귀환 지점"
                                : itemPoint(t.point()) ? (itemMoves == null ? "" : itemMoves.arrivalName(t.point())) : nameOf(t.point()), 0);
                    } else {
                        if (TravelProtocol.PERSONAL_ID.equals(t.point())) refund(p);
                        CmdKit.notify(p, "error", "travel-arrive", "목적지에 도착하지 못했어요", "이 서버의 처음 위치에 있어요", 0);
                    }
                }));
    }
    /** Same as the return-point spell: get off the broom before a teleport (ChacademyMotion, optional). */
    private static void stopBroom(Player p) {
        var motion = Bukkit.getPluginManager().getPlugin("ChacademyMotion");
        if (motion == null || !motion.isEnabled()) return;
        try {
            if (Boolean.TRUE.equals(motion.getClass().getMethod("isRidingBroom", Player.class).invoke(motion, p)))
                motion.getClass().getMethod("stopBroom", Player.class).invoke(motion, p);
        } catch (ReflectiveOperationException | LinkageError ignored) {}
    }
    private boolean isSpawn(String point) { var d = config.points().get(point); return d != null && d.spawn(); }
    private String nameOf(String point) { var d = config.points().get(point); return d == null ? point : d.name(); }
    private static void arrivedEffect(Player p) { p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .5f, 1.4f); }

    // ------------------------------------------------------------------ personal point (server API, for the spell later)

    /**
     * Sets the player's one personal point (replaces the old one, any server) to `at` on this server. Owner: this is
     * the ONLY way to set it (future spell 135 공간 도약 or another source, still undecided) — there is no packet for it.
     * Refuses worlds no map tab shows. done gets null on success or the reason (main thread).
     */
    public void setPersonal(Player p, Location at, String source, Consumer<String> done) {
        Consumer<String> answer = done == null ? s -> {} : done;
        String refusal = personalRefusal(p, at);
        if (refusal != null) { answer.accept(refusal); return; }
        String world = at.getWorld().getName();
        String src = source == null || source.isBlank() ? "unknown" : source.length() > 64 ? source.substring(0, 64) : source;
        var personal = new TravelModel.Personal(config.self(), world, at.getWorld().getUID().toString(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                System.currentTimeMillis(), src);
        store.savePersonal(p.getUniqueId(), personal, ok -> {
            if (ok) personalCache.put(p.getUniqueId(), Optional.of(personal));
            if (!ok) { answer.accept("개인 지점을 저장하지 못했어요"); return; }
            plugin.getLogger().info("개인 지점 지정: " + p.getName() + " → " + config.self() + "/" + world + " " + Math.round(at.getX()) + "," + Math.round(at.getY()) + "," + Math.round(at.getZ()) + " (" + src + ")");
            if (p.isOnline() && viewers.contains(p.getUniqueId())) send(p, TravelProtocol.KIND_STATE, "개인 지점을 바꿨어요");
            answer.accept(null);
        });
    }

    /** Synchronous checks before saving a personal point. null = fine. */
    String personalRefusal(Player p, Location at) {
        if (!store.ready()) return "공유 저장소 연결이 없어요";
        if (at == null || at.getWorld() == null) return "위치가 없어요";
        if (!plugin.playerStateReady(p)) return "캐릭터 정보를 불러오는 중이에요";
        if (config.tab(config.self(), at.getWorld().getName()) == null) return "지도에 없는 세계에서는 새길 수 없어요";
        return null;
    }

    void mana(ManaBridge mana) { this.mana = mana; }

    /**
     * 공간 도약 시전: the current location becomes the personal point (replacing the old one, every server). Owner
     * 2026-10-24: setting it is free (mana-spells.yml cost 0 / cooldown 0) — only moving there costs. Allowed only in
     * worlds a map tab shows (학교·야생; not the arena or dungeons).
     */
    boolean castSpatialLeap(Player p) {
        Location at = p.getLocation();
        String why = personalRefusal(p, at);
        if (why != null) { CmdKit.notify(p, "warning", "travel-personal", "개인 지점을 새길 수 없어요", why, 2000); return false; }
        setPersonal(p, at, "spell:spatial_leap", error -> {
            if (!p.isOnline()) return;
            if (error == null) {
                CmdKit.notify(p, "system", "travel-personal", "개인 지점을 새겼어요", "지도에서 이곳으로 이동할 수 있어요", 0);
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, 1.1f);
            } else CmdKit.notify(p, "error", "travel-personal", "개인 지점을 새기지 못했어요", error, 0);
        });
        return true;
    }

    // ------------------------------------------------------------------ commands

    private Collection<String> pointIds() { return config.points().keySet(); }

    private void commands() {
        ChacaApi.playerCommand(plugin, CmdKit.literal("지도").executes(CmdKit.run(ctx -> open(CmdKit.self(ctx)))), "지도 이동 창 열기", List.of());
        var root = CmdKit.literal("이동지점")
                .then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> list(CmdKit.sender(ctx)))))
                .then(CmdKit.literal("열기").executes(CmdKit.run(ctx -> open(CmdKit.self(ctx))))
                        .then(CmdKit.player("닉네임").executes(CmdKit.run(ctx -> open(ChacaApi.resolveOnline(ctx, "닉네임"))))))
                .then(CmdKit.literal("추가").then(CmdKit.argument("ID", StringArgumentType.word())
                        .then(CmdKit.argument("반경", DoubleArgumentType.doubleArg(1, 32)).then(CmdKit.argument("이름", StringArgumentType.greedyString())
                                .executes(CmdKit.run(this::add))))))
                .then(CmdKit.literal("위치").then(CmdKit.word("ID", this::pointIds).executes(CmdKit.run(this::move))))
                .then(CmdKit.literal("반경").then(CmdKit.word("ID", this::pointIds).then(CmdKit.argument("반경", DoubleArgumentType.doubleArg(1, 32)).executes(CmdKit.run(this::radius)))))
                .then(CmdKit.literal("이름").then(CmdKit.word("ID", this::pointIds).then(CmdKit.argument("이름", StringArgumentType.greedyString()).executes(CmdKit.run(this::rename)))))
                .then(CmdKit.literal("켜기").then(CmdKit.word("ID", this::pointIds).executes(CmdKit.run(ctx -> enable(ctx, true)))))
                .then(CmdKit.literal("끄기").then(CmdKit.word("ID", this::pointIds).executes(CmdKit.run(ctx -> enable(ctx, false)))))
                .then(CmdKit.literal("삭제").then(CmdKit.confirm(CmdKit.word("ID", this::pointIds), this::remove)))
                .then(CmdKit.literal("서버상태").executes(CmdKit.run(ctx -> servers(CmdKit.sender(ctx)))))
                .then(fitCommands());
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, root, "지도 이동 지점 목록·추가(현재 위치)·위치·반경·이름·켜기·끄기·삭제·서버상태, 타운맵 그림 맞추기(지도) (이 서버 travel.yml)", "이동지점 목록");
        ChacaApi.branch(plugin, ChacaApi.유저관리, CmdKit.literal("이동지점")
                .then(CmdKit.literal("보기").executes(CmdKit.run(this::userView)))
                .then(CmdKit.literal("기록").executes(CmdKit.run(this::userHistory)))
                .then(CmdKit.literal("해금").then(CmdKit.word("ID", this::pointIds).executes(CmdKit.run(this::userUnlock))))
                .then(CmdKit.literal("잠금").then(CmdKit.confirm(CmdKit.word("ID", this::pointIds), this::userLock)))
                .then(CmdKit.literal("개인지점")
                        .then(CmdKit.literal("여기").executes(CmdKit.run(this::userPersonalHere)))
                        .then(CmdKit.confirm(CmdKit.literal("삭제"), this::userPersonalDelete))),
                "지도 이동 해금 보기·해금·잠금, 이동 기록, 개인 지점(테스트용: 관리자 위치로 지정·삭제)", "이동지점 보기");
        ChacaApi.reload(plugin, "이동", "travel.yml (지도 탭·이동 지점·서버 목록)", this::reload);
        ChacaApi.status(plugin, "지도 이동", () -> (store.usable() ? store.shared() ? "공유 DB" : "테스트 로컬 DB(공유 안 됨)" : "꺼짐(공유 DB 없음)")
                + " · 서버 " + config.self() + " · 지점 " + config.points().size() + "개" + (mismatched().isEmpty() ? "" : " · travel.yml 다른 서버: " + String.join(",", mismatched())));
    }

    private void list(org.bukkit.command.CommandSender sender) {
        var out = new ArrayList<String>();
        out.add("── 이동 지점 " + config.points().size() + "개 (이 서버: " + config.self() + ") ──");
        for (var d : config.points().values()) {
            var tab = config.tab(d.server(), d.world());
            out.add(" " + d.id() + " · " + d.name() + " · " + d.server() + "/" + d.world() + " " + Math.round(d.x()) + "," + Math.round(d.y()) + "," + Math.round(d.z())
                    + " · 반경 " + d.radius() + (tab == null ? " · (탭 없음: 목록에만)" : " · 탭 " + tab.label()) + (d.enabled() ? "" : " · 꺼짐"));
        }
        if (!mismatched().isEmpty()) out.add(" ! travel.yml 이 다른 서버: " + String.join(", ", mismatched()) + " — 같은 파일을 복사하고 새로고침 해 주세요");
        CmdKit.lines(sender, out);
    }
    private void servers(org.bukkit.command.CommandSender sender) {
        long now = System.currentTimeMillis();
        var out = new ArrayList<String>();
        out.add("── 지도 이동 서버 상태 ──");
        for (var s : config.servers().values()) {
            var st = status.get(s.id());
            String state = s.id().equals(config.self()) ? "이 서버" : st == null ? "기록 없음" : st.alive(now) ? "켜짐 " + st.online() + "/" + st.max() : "꺼짐(" + (now - st.heartbeat()) / 1000 + "초 전)";
            out.add(" " + s.id() + " (" + s.label() + ", Velocity " + s.proxy() + ")" + (s.enabled() ? "" : " · 이동 막음") + " · " + state
                    + (st != null && !st.configHash().isEmpty() && !st.configHash().equals(config.hash()) && !s.id().equals(config.self()) ? " · travel.yml 다름" : ""));
        }
        CmdKit.lines(sender, out);
    }

    // ------------------------------------------------------------------ 타운맵 그림 맞추기 (#53)

    /** 학교 타운맵 기준 픽셀 기본값 = 광장 동상 한가운데 (그림 1479×1064, 오너 2026-10-25: 동상 = 월드 32.5, 124, 12.5). */
    static final double STATUE_PX = 493, STATUE_PY = 432;

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> fitCommands() {
        var px = DoubleArgumentType.doubleArg(-16384, 32768);
        return CmdKit.literal("지도")
                .then(CmdKit.literal("보기").executes(CmdKit.run(this::fitView)))
                .then(CmdKit.literal("기준").executes(CmdKit.run(ctx -> fitAnchor(ctx, false)))
                        .then(CmdKit.argument("그림X", px).then(CmdKit.argument("그림Y", px).executes(CmdKit.run(ctx -> fitAnchor(ctx, true))))))
                .then(CmdKit.literal("두번째").then(CmdKit.argument("그림X", px).then(CmdKit.argument("그림Y", px).executes(CmdKit.run(this::fitSecond)))))
                .then(CmdKit.literal("배율").then(CmdKit.argument("블록", DoubleArgumentType.doubleArg(0.01, 64)).executes(CmdKit.run(this::fitScale))))
                .then(CmdKit.literal("회전").then(CmdKit.argument("각도", DoubleArgumentType.doubleArg(-360, 360)).executes(CmdKit.run(this::fitRotate))))
                .then(CmdKit.literal("밀기").then(CmdKit.argument("x", DoubleArgumentType.doubleArg(-100000, 100000))
                        .then(CmdKit.argument("z", DoubleArgumentType.doubleArg(-100000, 100000)).executes(CmdKit.run(this::fitShift)))));
    }

    /** 지금 서 있는 세계의 그림 지도 탭. */
    private TravelModel.TabDef imageTab(Player p) throws CommandSyntaxException {
        var t = config.tab(config.self(), p.getWorld().getName());
        if (t == null) throw ChacaApi.error("이 세계(" + p.getWorld().getName() + ")를 보여 주는 지도 탭이 없어요");
        if (t.map() != TravelProtocol.MAP_IMAGE) throw ChacaApi.error("탭 " + t.label() + " 은 그림 지도(map: image)가 아니에요 — travel.yml 에서 map: image 로 바꿔 주세요");
        return t;
    }
    /** travel.yml 에 적힌 기준 픽셀 (image.anchor, 없으면 동상). */
    private double[] anchorPixel(TravelModel.TabDef t) {
        try {
            var y = new YamlConfiguration(); y.load(file);
            var a = y.getDoubleList("tabs." + t.id() + ".image.anchor");
            if (a.size() == 2) return new double[] { a.get(0), a.get(1) };
        } catch (Exception ignored) {}
        return new double[] { STATUE_PX, STATUE_PY };
    }
    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String f3(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    private void fitSave(org.bukkit.command.CommandSender sender, TravelModel.TabDef t, TravelMapFit.Fit f, double[] anchor, String what) {
        double[] c = f.corners(t.imageW(), t.imageH());
        String at = "tabs." + t.id() + ".image.";
        edit(sender, "타운맵 " + what + " (1픽셀 = " + f3(f.scale()) + "블록, 회전 " + f1(f.degrees()) + "도) — 지도를 다시 열어 확인", y -> {
            y.set(at + "top-left", List.of(c[0], c[1])); y.set(at + "top-right", List.of(c[2], c[3])); y.set(at + "bottom-left", List.of(c[4], c[5]));
            if (anchor != null) y.set(at + "anchor", List.of(anchor[0], anchor[1]));
        });
    }
    private void fitView(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p);
        var f = TravelMapFit.of(t.corners(), t.imageW()); double[] a = anchorPixel(t); Location l = p.getLocation();
        CmdKit.lines(CmdKit.sender(ctx), List.of(
                "── 타운맵 맞추기 · 탭 " + t.label() + " (그림 " + t.imageW() + "×" + t.imageH() + ") ──",
                " 1픽셀 = " + f3(f.scale()) + "블록 · 회전 " + f1(f.degrees()) + "도",
                " 기준 픽셀 (" + f1(a[0]) + ", " + f1(a[1]) + ") = 월드 " + f1(f.x(a[0], a[1])) + ", " + f1(f.z(a[0], a[1])),
                " 지금 서 있는 곳 = 그림 (" + f1(f.px(l.getX(), l.getZ())) + ", " + f1(f.py(l.getX(), l.getZ())) + ")",
                " 맞추는 법: 동상 한가운데 서서 '지도 기준' → 멀리 떨어진 알아보기 쉬운 곳(성 모서리 등)에 서서 '지도 두번째 <그림X> <그림Y>'",
                " 조금씩: '지도 밀기 <x> <z>' (블록) · '지도 배율 <블록/픽셀>' · '지도 회전 <도>'"));
    }
    private void fitAnchor(CommandContext<CommandSourceStack> ctx, boolean given) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p); Location l = p.getLocation();
        double[] a = given ? new double[] { ctx.getArgument("그림X", Double.class), ctx.getArgument("그림Y", Double.class) } : new double[] { STATUE_PX, STATUE_PY };
        var f = TravelMapFit.move(TravelMapFit.of(t.corners(), t.imageW()), a[0], a[1], l.getX(), l.getZ());
        fitSave(CmdKit.sender(ctx), t, f, a, "기준점 그림 (" + f1(a[0]) + ", " + f1(a[1]) + ") = 지금 위치 " + f1(l.getX()) + ", " + f1(l.getZ()));
    }
    private void fitSecond(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p); Location l = p.getLocation();
        var f = TravelMapFit.of(t.corners(), t.imageW()); double[] a = anchorPixel(t);
        double px = ctx.getArgument("그림X", Double.class), py = ctx.getArgument("그림Y", Double.class);
        try {
            var next = TravelMapFit.two(a[0], a[1], f.x(a[0], a[1]), f.z(a[0], a[1]), px, py, l.getX(), l.getZ());
            fitSave(CmdKit.sender(ctx), t, next, null, "두 번째 기준점 그림 (" + f1(px) + ", " + f1(py) + ") = 지금 위치 " + f1(l.getX()) + ", " + f1(l.getZ()));
        } catch (IllegalArgumentException bad) { throw ChacaApi.error(bad.getMessage()); }
    }
    private void fitScale(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p); double[] a = anchorPixel(t);
        var f = TravelMapFit.scale(TravelMapFit.of(t.corners(), t.imageW()), a[0], a[1], ctx.getArgument("블록", Double.class));
        fitSave(CmdKit.sender(ctx), t, f, null, "배율 변경");
    }
    private void fitRotate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p); double[] a = anchorPixel(t);
        var f = TravelMapFit.rotate(TravelMapFit.of(t.corners(), t.imageW()), a[0], a[1], ctx.getArgument("각도", Double.class));
        fitSave(CmdKit.sender(ctx), t, f, null, "회전 변경");
    }
    private void fitShift(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx); var t = imageTab(p);
        var f = TravelMapFit.shift(TravelMapFit.of(t.corners(), t.imageW()), ctx.getArgument("x", Double.class), ctx.getArgument("z", Double.class));
        fitSave(CmdKit.sender(ctx), t, f, null, "밀기");
    }

    /** Edits this server's travel.yml (comments kept) and reloads. */
    private void edit(org.bukkit.command.CommandSender sender, String what, Consumer<YamlConfiguration> change) {
        try {
            var y = new YamlConfiguration();
            y.load(file);
            change.accept(y);
            TravelModel.parse(plain(y));   // never save a file that would not load
            var tmp = new File(file.getParentFile(), "travel.yml.tmp");
            y.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            reload();
            CmdKit.ok(sender, what + " — 다른 서버의 travel.yml 에도 같은 내용을 복사하고 /서버관리 새로고침 이동 해 주세요");
        } catch (IllegalArgumentException bad) { CmdKit.userFail(sender, bad.getMessage()); }
        catch (Exception e) { CmdKit.fail(sender, "travel.yml 저장 실패", e); }
    }
    private String pick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = CmdKit.text(ctx, "ID");
        if (!config.points().containsKey(id)) throw ChacaApi.error("travel.yml 에 없는 지점이에요: " + id);
        return id;
    }
    private static void at(ConfigurationSection s, Location l) {
        s.set("x", Math.round(l.getX() * 10) / 10.0); s.set("y", Math.round(l.getY() * 10) / 10.0); s.set("z", Math.round(l.getZ() * 10) / 10.0);
        s.set("yaw", (double) Math.round(l.getYaw())); s.set("pitch", 0.0);
    }
    private void add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = CmdKit.self(ctx);
        String id = CmdKit.text(ctx, "ID"), name = CmdKit.text(ctx, "이름").strip();
        double radius = ctx.getArgument("반경", Double.class);
        if (!id.matches(TravelModel.ID)) throw ChacaApi.error("지점 ID 는 영문 소문자·숫자·_·- 1~48자");
        if (config.points().containsKey(id)) throw ChacaApi.error("이미 있는 지점이에요: " + id + " (위치 바꾸기는 이동지점 위치)");
        if (config.tab(config.self(), p.getWorld().getName()) == null) CmdKit.userFail(CmdKit.sender(ctx), "주의: 이 세계(" + p.getWorld().getName() + ")를 보여 주는 탭이 없어 목록에만 나와요");
        Location l = p.getLocation();
        edit(CmdKit.sender(ctx), "지점 " + id + " 추가", y -> {
            var s = y.createSection("points." + id);
            s.set("name", name); s.set("server", config.self()); s.set("world", l.getWorld().getName()); at(s, l); s.set("radius", radius); s.set("enabled", true);
        });
    }
    private void move(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = pick(ctx); Player p = CmdKit.self(ctx); Location l = p.getLocation();
        edit(CmdKit.sender(ctx), "지점 " + id + " 위치를 지금 위치로", y -> { var s = y.getConfigurationSection("points." + id); s.set("server", config.self()); s.set("world", l.getWorld().getName()); at(s, l); });
    }
    private void radius(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = pick(ctx); double r = ctx.getArgument("반경", Double.class);
        edit(CmdKit.sender(ctx), "지점 " + id + " 반경 " + r, y -> y.set("points." + id + ".radius", r));
    }
    private void rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = pick(ctx), name = CmdKit.text(ctx, "이름").strip();
        edit(CmdKit.sender(ctx), "지점 " + id + " 이름 " + name, y -> y.set("points." + id + ".name", name));
    }
    private void enable(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        String id = pick(ctx);
        edit(CmdKit.sender(ctx), "지점 " + id + (on ? " 켜기" : " 끄기 (해금 기록은 그대로)"), y -> y.set("points." + id + ".enabled", on));
    }
    private void remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = pick(ctx);
        edit(CmdKit.sender(ctx), "지점 " + id + " 삭제 (DB 해금 기록은 남아 있어 같은 ID로 다시 만들면 그대로 쓰여요)", y -> y.set("points." + id, null));
    }

    private void userView(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx); var sender = CmdKit.sender(ctx); String name = CmdKit.name(target);
        store.unlockTimes(target, rows -> store.personal(target, personal -> {
            if (rows == null || personal == null) { CmdKit.userFail(sender, "기록을 읽지 못했어요 (콘솔 확인)"); return; }
            var out = new ArrayList<String>();
            out.add("── " + name + " 님 이동 지점 해금 " + rows.size() + "개 ──");
            var fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
            rows.forEach((id, at) -> out.add(" " + id + (config.points().containsKey(id) ? " · " + config.points().get(id).name() : " · (지금 travel.yml 에 없음)") + " · " + fmt.format(java.time.Instant.ofEpochMilli(at))));
            out.add(personal.map(m -> " 개인 지점: " + m.server() + "/" + m.world() + " " + Math.round(m.x()) + "," + Math.round(m.y()) + "," + Math.round(m.z()) + " · " + m.source()
                    + " · " + fmt.format(java.time.Instant.ofEpochMilli(m.at()))).orElse(" 개인 지점: 없음"));
            CmdKit.lines(sender, out);
        }));
    }
    private void userHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx); var sender = CmdKit.sender(ctx); String name = CmdKit.name(target);
        store.history(target, 15, rows -> {
            if (rows == null) { CmdKit.userFail(sender, "기록을 읽지 못했어요 (콘솔 확인)"); return; }
            var out = new ArrayList<String>(); out.add("── " + name + " 님 최근 이동 " + rows.size() + "건 ──"); out.addAll(rows);
            if (rows.isEmpty()) out.add(" (기록 없음)");
            CmdKit.lines(sender, out);
        });
    }
    private void userUnlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx); String id = pick(ctx); var sender = CmdKit.sender(ctx);
        store.unlock(target, id, "admin:" + config.self(), claim -> {
            if (claim == TravelStore.Claim.ERROR) { CmdKit.userFail(sender, "해금 기록 실패 (콘솔 확인)"); return; }
            var set = unlocked.get(target); if (set != null) set.add(id);
            var p = Bukkit.getPlayer(target); if (p != null) { sendMarkers(p); discovery(p, 0); if (viewers.contains(target)) send(p, TravelProtocol.KIND_STATE, ""); }
            CmdKit.ok(sender, CmdKit.name(target) + " 님 " + id + (claim == TravelStore.Claim.CLAIMED ? " 해금" : " 은 이미 해금돼 있어요"));
        });
    }
    private void userLock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx); String id = pick(ctx); var sender = CmdKit.sender(ctx);
        store.lock(target, id, ok -> {
            var set = unlocked.get(target); if (set != null) set.remove(id);
            var p = Bukkit.getPlayer(target); if (p != null) { sendMarkers(p); if (viewers.contains(target)) send(p, TravelProtocol.KIND_STATE, ""); }
            if (ok) CmdKit.ok(sender, CmdKit.name(target) + " 님 " + id + " 해금 기록을 지웠어요 (다른 서버는 재접속 때 반영)");
            else CmdKit.userFail(sender, "해금 기록이 없어요 (또는 DB 오류)");
        });
    }
    private void userPersonalHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = ChacaApi.targetOnline(ctx); Player admin = CmdKit.self(ctx); var sender = CmdKit.sender(ctx);
        setPersonal(target, admin.getLocation(), "admin:" + admin.getName(), why -> {
            if (why == null) CmdKit.ok(sender, CmdKit.name(target.getUniqueId()) + " 님 개인 지점을 관리자 위치로 지정했어요 (예전 지점 교체)");
            else CmdKit.userFail(sender, why);
        });
    }
    private void userPersonalDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID target = ChacaApi.target(ctx); var sender = CmdKit.sender(ctx);
        store.deletePersonal(target, ok -> { if (ok) personalCache.put(target, Optional.empty()); if (ok) CmdKit.ok(sender, CmdKit.name(target) + " 님 개인 지점을 지웠어요"); else CmdKit.userFail(sender, "개인 지점이 없어요 (또는 DB 오류)"); });
    }
}
