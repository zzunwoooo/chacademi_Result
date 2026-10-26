package school.magiccodex.party;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol.*;

/**
 * 큐디치 대기열 (오너 결정, docs claude/quidditch-plan.md):
 *  - 3인 파티는 파티장이 신청, 혼자는 랜덤 매칭. 대기 중에는 지금 있는 서버에 그대로 있는다.
 *  - 매칭이 잡히면 6명 모두 수락 확인(10초). 거절·시간 초과한 쪽은 대기열에서 빠지고, 나머지는 원래 대기 순서로 돌아간다.
 *  - 모두 수락하면 경기장 서버로 이동, 경기가 끝나면 원래 있던 서버로 돌아간다 (위치는 그 서버가 저장한 자리).
 *  - 경기 중 접속이 끊겼다가 다시 들어오면 경기장 서버로 바로 보낸다 (60초 재접속은 경기장 서버가 판정).
 * 결투장 (2026-10-18): 모드 0 큐디치 · 1 1:1 대전. 1:1은 혼자만 신청(파티에 있어도 각자), 팀 1명. 대기열·경기장·레이팅은 모드별로 따로.
 * 2026-10-25: 커스텀 경기 (/큐디치관리 경기 커스텀, 경기장 서버가 {@link Custom} 으로 요청) — 대기열 없이 정한 두 팀에 수락 확인 → ASSIGN rated=0.
 *  경기장 서버가 경기를 더 이상 알리지 않으면 (서버 오류·재시작) {@link #VOID_MS} 뒤 경기 무효: 선수는 원래 서버로, 레이팅 변동 없음.
 *  (경기장의 60초 재접속 시간보다 길게: 모두가 한꺼번에 끊겨 LIVE 를 보낼 사람이 없는 동안에도 돌아올 수 있게)
 *  경기 중 경기장에서 튕겨 다른 서버로 옮겨진 선수는 경기장으로 다시 보낸다 (60초 재접속 판정은 경기장 서버).
 * All state lives on the party plugin's single worker thread.
 */
final class KyudichiQueue {
    static final MinecraftChannelIdentifier QUEUE = MinecraftChannelIdentifier.from(KyudichiQueueProtocol.QUEUE);
    static final MinecraftChannelIdentifier LINK = MinecraftChannelIdentifier.from(KyudichiQueueProtocol.LINK);
    static final long OFFER_MS = 10_000, UNSEEN_MS = 120_000, LIVE_FRESH_MS = 10_000, VOID_MS = 75_000, REJOIN_RETRY_MS = 60_000;

    interface Host {
        boolean enqueue(Runnable task);
        PartyBook.Party party(UUID member);
        String name(UUID id);
        void notice(Player p, String text);
        /** House 0..3 or -1, from the party plugin's identity memory. */
        int house(UUID id);
        /** Skin texture hash (textures.minecraft.net) last seen for this player, or "". */
        String skin(UUID id);
        boolean trusted(String server);
        /** /유저관리 &lt;닉네임&gt; 파티 보기 (채팅 줄). 실패는 IllegalArgumentException(관리자에게 보일 문구). */
        List<String> partyView(UUID target);
        /** /유저관리 &lt;닉네임&gt; 파티 빼기. @return 결과 문구 */
        String partyRemove(UUID target) throws IOException;
        /** /유저관리 &lt;닉네임&gt; 파티 해산 확인. @return 결과 문구 */
        String partyDisband(UUID target) throws IOException;
        /** 자동완성용 접속자 한글 닉네임. */
        default List<String> onlineNames() { return List.of(); }
    }

    private final ProxyServer proxy; private final Logger log; private final Host host; private final Object plugin;
    private String arenaServer = "arena", fallbackServer = "school";
    private int arenaSlots = 3, duelSlots = 3;
    private KyudichiDb db;

    private record Queued(KyudichiMatchmaker.Entry entry, UUID leader, int mode) {}
    private final Map<UUID, Queued> queue = new LinkedHashMap<>();       // entry id → entry
    private final Map<UUID, UUID> queuedBy = new HashMap<>();             // player → entry id
    private final Set<UUID> joining = new HashSet<>();                    // DB lookup in flight
    private static final class Offer {
        final UUID id = UUID.randomUUID(); final KyudichiMatchmaker.Pair pair; final long deadline; final int mode; final Set<UUID> accepted = new HashSet<>();
        /** false = 커스텀 경기 (대기열에서 온 게 아님: 실패해도 대기열로 돌아가지 않고, 레이팅 없음). */
        final boolean rated;
        Offer(KyudichiMatchmaker.Pair pair, long deadline, int mode, boolean rated) { this.pair = pair; this.deadline = deadline; this.mode = mode; this.rated = rated; }
        List<UUID> everyone() { var l = new ArrayList<>(pair.a().members()); l.addAll(pair.b().members()); return l; }
        List<KyudichiMatchmaker.Entry> entries() { var l = new ArrayList<>(pair.a().parts()); l.addAll(pair.b().parts()); return l; }
    }
    private final Map<UUID, Offer> offerOf = new HashMap<>();
    /**
     * lastSeen: assignment time, then every LIVE report that still lists the match. Unseen for 2 minutes = gone.
     * seenLive: the arena reported it running at least once — after that, {@link #VOID_MS} unseen = the arena lost it (server error) → void.
     */
    private static final class Assigned { final Assign assign; long lastSeen; boolean seenLive; Assigned(Assign a, long t) { assign = a; lastSeen = t; } }
    private final Map<UUID, Assigned> matches = new HashMap<>();          // match id → assignment
    private final Map<UUID, UUID> playing = new ConcurrentHashMap<>();    // player → match id (also read by initialServer)
    private final Map<UUID, String> origin = new HashMap<>();             // player → server to go back to
    private final Map<UUID, String> pendingSpectate = new HashMap<>();
    private final Set<UUID> pendingPractice = new HashSet<>();
    private final Set<UUID> modded = new HashSet<>();
    private final Map<UUID, Long> rejoinAt = new HashMap<>();             // player → last time we sent them back to a running match
    private List<LiveMatch> live = List.of(); private int liveFree = -1, liveFreeDuel = -1; private long liveAt;

    KyudichiQueue(ProxyServer proxy, Logger log, Host host, Object plugin) { this.proxy = proxy; this.log = log; this.host = host; this.plugin = plugin; }

    void initialize(Path directory) {
        int start = 1000; int[] floors = {900, 1100, 1300, 1500, 1700};
        try {
            Path file = directory.resolve("kyudichi.properties");
            var p = new Properties();
            if (Files.exists(file)) { try (var r = Files.newBufferedReader(file)) { p.load(r); } }
            else {
                p.setProperty("arena-server", "arena"); p.setProperty("return-fallback-server", "school"); p.setProperty("arena-slots", "3"); p.setProperty("duel-slots", "3");
                p.setProperty("rating-start", "1000"); p.setProperty("tier-floors", "900,1100,1300,1500,1700");
                try (var w = Files.newBufferedWriter(file)) { p.store(w, "Kyudichi queue. arena-server = Velocity server name of the arena/PvP server. tier-floors must match ChacaKyudichi config.yml rating.tiers (silver..master). Restart after editing."); }
            }
            arenaServer = p.getProperty("arena-server", "arena").strip();
            fallbackServer = p.getProperty("return-fallback-server", "school").strip();
            arenaSlots = Math.clamp(Integer.parseInt(p.getProperty("arena-slots", "3").strip()), 1, 16);
            duelSlots = Math.clamp(Integer.parseInt(p.getProperty("duel-slots", "3").strip()), 0, 16);
            start = Integer.parseInt(p.getProperty("rating-start", "1000").strip());
            var f = p.getProperty("tier-floors", "900,1100,1300,1500,1700").split(",");
            if (f.length == 5) for (int i = 0; i < 5; i++) floors[i] = Integer.parseInt(f[i].strip());
        } catch (Exception e) { log.error("kyudichi.properties 를 읽지 못해 기본값을 씁니다", e); }
        db = new KyudichiDb(log, start, floors);
        db.open(directory.resolve("kyudichi-database.properties"));
        proxy.getChannelRegistrar().register(QUEUE, LINK);
        proxy.getEventManager().register(plugin, this);
        proxy.getScheduler().buildTask(plugin, () -> host.enqueue(this::tick)).repeat(1, TimeUnit.SECONDS).schedule();
        var manager = proxy.getCommandManager();
        var kyudichi = new BrigadierCommand(lobbyCommand("큐디치", KyudichiQueueProtocol.MODE_KYUDICHI));
        manager.register(manager.metaBuilder(kyudichi).plugin(plugin).build(), kyudichi);
        var duel = new BrigadierCommand(lobbyCommand("결투", KyudichiQueueProtocol.MODE_DUEL));
        manager.register(manager.metaBuilder(duel).plugin(plugin).build(), duel);
        log.info("큐디치 대기열: 경기장 서버 '" + arenaServer + "', 큐디치 경기장 " + arenaSlots + "개 · 1:1 경기장 " + duelSlots + "개");
    }
    void shutdown() { if (db != null) db.close(); }

    // =============================================================== helpers
    private Player online(UUID id) { return proxy.getPlayer(id).orElse(null); }
    private String serverOf(Player p) { return p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(""); }
    private boolean onArena(Player p) { return serverOf(p).equals(arenaServer); }
    /** C12 (2026-10-21): on the arena server but not a match participant (spectator · practice). Worker thread. */
    boolean arenaBystander(Player p) { return onArena(p) && !playing.containsKey(p.getUniqueId()); }
    /** A participant of an assigned match (any server). Worker thread. */
    boolean inMatch(UUID id) { return playing.containsKey(id); }
    /** 지도 이동 (TravelRelay, worker thread): in a match, a match offer, or on the way to the arena — don't move servers. */
    boolean travelBlocked(UUID id) { return playing.containsKey(id) || offerOf.containsKey(id) || pendingSpectate.containsKey(id) || pendingPractice.contains(id); }
    private void say(UUID id, String text) { var p = online(id); if (p != null) host.notice(p, text); }
    private void sayAll(List<UUID> ids, String text) { for (UUID id : ids) say(id, text); }
    private void toClient(Player p, ToClient m) { if (modded.contains(p.getUniqueId())) p.sendPluginMessage(QUEUE, KyudichiQueueProtocol.encode(m)); }
    private void toArena(Player via, ToArena m) {
        var c = via.getCurrentServer().orElse(null);
        if (c != null && c.getServerInfo().getName().equals(arenaServer)) c.sendPluginMessage(LINK, KyudichiQueueProtocol.encode(m));
    }
    private int parties() { return parties(KyudichiQueueProtocol.MODE_KYUDICHI); }
    private int parties(int mode) { return (int) queue.values().stream().filter(q -> q.mode() == mode).count(); }
    private int queuedPlayers(int mode) { return queue.values().stream().filter(q -> q.mode() == mode).mapToInt(q -> q.entry().members().size()).sum(); }
    private static int clampMode(int mode) { return mode == KyudichiQueueProtocol.MODE_DUEL ? mode : KyudichiQueueProtocol.MODE_KYUDICHI; }
    private static String modeName(int mode) { return mode == KyudichiQueueProtocol.MODE_DUEL ? "1:1 대전" : "큐디치"; }
    private static int teamSize(int mode) { return mode == KyudichiQueueProtocol.MODE_DUEL ? 1 : KyudichiMatchmaker.TEAM_SIZE; }
    private int modeOf(UUID player) {
        UUID eid = queuedBy.get(player); if (eid != null && queue.containsKey(eid)) return queue.get(eid).mode();
        var o = offerOf.get(player); if (o != null) return o.mode;
        UUID m = playing.get(player); if (m != null && matches.containsKey(m)) return matches.get(m).assign.mode();
        return KyudichiQueueProtocol.MODE_KYUDICHI;
    }

    /** Arenas of a mode we may still fill: configured slots minus matches we sent, and never more than the arena server last reported free. */
    private int freeArenas(long now, int mode) {
        long sent = matches.values().stream().filter(a -> a.assign.mode() == mode).count();
        int free = (mode == KyudichiQueueProtocol.MODE_DUEL ? duelSlots : arenaSlots) - (int) sent - offersOpen(mode);
        int reported = mode == KyudichiQueueProtocol.MODE_DUEL ? liveFreeDuel : liveFree;
        if (reported >= 0 && now - liveAt < LIVE_FRESH_MS) {
            long notYetLive = matches.entrySet().stream().filter(e -> e.getValue().assign.mode() == mode)
                    .filter(e -> live.stream().noneMatch(l -> l.match().equals(e.getKey()))).count();
            free = Math.min(free, reported - (int) notYetLive - offersOpen(mode));
        }
        return free;
    }
    private int offersOpen(int mode) { return (int) offerOf.values().stream().distinct().filter(o -> o.mode == mode).count(); }

    // =============================================================== join / leave
    void join(Player p) { join(p, KyudichiQueueProtocol.MODE_KYUDICHI); }
    void join(Player p, int mode) {
        final int md = clampMode(mode);
        UUID me = p.getUniqueId();
        if (queuedBy.containsKey(me) || joining.contains(me)) { host.notice(p, "이미 " + modeName(modeOf(me)) + " 대기열에 있어요. 창에서 취소할 수 있어요."); return; }
        if (offerOf.containsKey(me)) { host.notice(p, "매칭 수락 확인 중이에요."); return; }
        if (playing.containsKey(me)) { host.notice(p, "이미 " + modeName(modeOf(me)) + " 경기 중이에요."); return; }
        var party = host.party(me);
        List<UUID> members;
        if (md == KyudichiQueueProtocol.MODE_DUEL) members = List.of(me); // 1:1 is always solo, party or not
        else if (party == null) members = List.of(me);
        else {
            if (!party.leader().equals(me)) { host.notice(p, "파티는 파티장만 큐디치를 신청할 수 있어요."); return; }
            if (party.members().size() == 1) members = List.of(me);
            else if (party.members().size() != KyudichiMatchmaker.TEAM_SIZE) { host.notice(p, "큐디치는 3인 파티 또는 혼자(랜덤 매칭)만 신청할 수 있어요."); return; }
            else members = party.members();
        }
        for (UUID m : members) {
            Player mp = online(m);
            if (mp == null) { host.notice(p, host.name(m) + " 님이 접속 중이 아니에요."); return; }
            String s = serverOf(mp);
            if (!host.trusted(s) && !s.equals(arenaServer)) { host.notice(p, host.name(m) + " 님이 있는 곳에서는 신청할 수 없어요."); return; }
            if (queuedBy.containsKey(m) || offerOf.containsKey(m) || playing.containsKey(m) || joining.contains(m)) { host.notice(p, host.name(m) + " 님은 이미 대기열이나 경기 중이에요."); return; }
            if (pendingPractice.contains(m)) { host.notice(p, host.name(m) + " 님이 연습장으로 가는 중이에요."); return; }
        }
        joining.addAll(members);
        db.rankedStandings(members, KyudichiDb.mode(md), standings -> host.enqueue(() -> {
            joining.removeAll(members);
            if (standings == null) { sayAll(members, modeName(md) + " 매칭을 지금 신청할 수 없어요 (레이팅 정보를 불러오지 못했어요). 관리자에게 알려 주세요."); return; }
            long now = System.currentTimeMillis();
            for (UUID m : members) {
                var st = standings.get(m);
                if (st != null && st.bannedUntil() > now) {
                    long min = Math.max(1, (st.bannedUntil() - now + 59_999) / 60_000);
                    sayAll(members, host.name(m) + " 님은 " + modeName(md) + " 이용이 제한된 상태예요 (" + (min >= 60 ? min / 60 + "시간 " : "") + min % 60 + "분 남음).");
                    return;
                }
                if (online(m) == null || queuedBy.containsKey(m) || playing.containsKey(m)) { say(me, "신청하는 사이에 파티원 상태가 바뀌었어요. 다시 신청해 주세요."); return; }
            }
            int rating = members.stream().mapToInt(m -> standings.get(m).rating()).max().orElse(1000);
            var entry = new KyudichiMatchmaker.Entry(UUID.randomUUID(), List.copyOf(members), rating, now);
            queue.put(entry.id(), new Queued(entry, me, md));
            for (UUID m : members) queuedBy.put(m, entry.id());
            sayAll(members, md == KyudichiQueueProtocol.MODE_DUEL ? "1:1 대전 매칭을 시작했어요."
                    : members.size() == 1 ? "큐디치 랜덤 매칭을 시작했어요." : "파티 큐디치 매칭을 시작했어요.");
            status(now);
        }));
    }

    void leave(Player p, String why) {
        UUID eid = queuedBy.get(p.getUniqueId());
        if (eid == null) { if (why == null) host.notice(p, "대기열에 있지 않아요."); return; }
        var q = queue.remove(eid);
        for (UUID m : q.entry().members()) { queuedBy.remove(m); var mp = online(m); if (mp != null) toClient(mp, idle()); }
        sayAll(q.entry().members(), why != null ? why : host.name(p.getUniqueId()) + " 님이 " + modeName(q.mode()) + " 매칭을 취소했어요.");
    }

    private static Status idle() { return new Status(KyudichiQueueProtocol.S_NONE, 0, 0, 0, 0, false, 0, 0); }

    // =============================================================== offers
    private void offer(KyudichiMatchmaker.Pair pair, long now, int mode, boolean rated) {
        var o = new Offer(pair, now + OFFER_MS, mode, rated);
        for (var e : o.entries()) { queue.remove(e.id()); for (UUID m : e.members()) queuedBy.remove(m); }
        for (UUID m : o.everyone()) {
            offerOf.put(m, o);
            var p = online(m); if (p == null) continue;
            host.notice(p, rated ? modeName(mode) + " 경기를 찾았어요! 10초 안에 수락해 주세요."
                    : modeName(mode) + " 커스텀 경기에 초대됐어요 (레이팅 미반영). 10초 안에 수락해 주세요.");
        }
        status(now);
    }

    void accept(Player p) {
        var o = offerOf.get(p.getUniqueId());
        if (o == null) { host.notice(p, "수락할 매칭이 없어요."); return; }
        o.accepted.add(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (o.accepted.containsAll(o.everyone())) transfer(o, now); else status(now);
    }

    void decline(Player p) {
        var o = offerOf.get(p.getUniqueId());
        if (o == null) { host.notice(p, "거절할 매칭이 없어요."); return; }
        failOffer(o, Set.of(p.getUniqueId()), host.name(p.getUniqueId()) + " 님이 매칭을 거절했어요.");
    }

    /** Entries with anyone in `out` leave the queue; the rest go back with their original waiting time. */
    private void failOffer(Offer o, Set<UUID> out, String why) {
        for (UUID m : o.everyone()) offerOf.remove(m);
        if (!o.rated) {   // 커스텀 경기: 아무도 대기열로 돌아가지 않는다
            for (UUID m : o.everyone()) { var mp = online(m); if (mp != null) toClient(mp, idle()); }
            sayAll(o.everyone(), why + " 커스텀 경기가 취소됐어요.");
            status(System.currentTimeMillis());
            return;
        }
        for (var e : o.entries()) {
            boolean drop = e.members().stream().anyMatch(out::contains);
            if (drop) {
                for (UUID m : e.members()) { var mp = online(m); if (mp != null) toClient(mp, idle()); }
                sayAll(e.members(), why + " 대기열에서 나왔어요.");
            } else {
                boolean allOnline = e.members().stream().allMatch(m -> online(m) != null);
                if (!allOnline) { sayAll(e.members(), why + " 파티원이 접속 중이 아니라 대기열에서 나왔어요."); continue; }
                UUID leader = e.members().getFirst();
                queue.put(e.id(), new Queued(e, leader, o.mode));
                for (UUID m : e.members()) queuedBy.put(m, e.id());
                sayAll(e.members(), why + " 대기열 앞자리로 돌아갔어요.");
            }
        }
        status(System.currentTimeMillis());
    }

    private void transfer(Offer o, long now) {
        for (UUID m : o.everyone()) offerOf.remove(m);
        RegisteredServer arena = proxy.getServer(arenaServer).orElse(null);
        if (arena == null) {
            // no arena server registered: nobody can play — don't leave six people "in a match" for two minutes
            log.error("큐디치: Velocity 에 경기장 서버 '" + arenaServer + "' 가 없어요 (kyudichi.properties arena-server · velocity.toml 확인)");
            for (UUID m : o.everyone()) { var mp = online(m); if (mp != null) toClient(mp, idle()); }
            sayAll(o.everyone(), "경기장 서버(" + arenaServer + ")를 찾을 수 없어 경기가 취소됐어요. 관리자에게 알려 주세요.");
            status(now);
            return;
        }
        var assign = new Assign(UUID.randomUUID(), o.rated, o.pair.a().members(), o.pair.b().members(), o.mode);
        matches.put(assign.match(), new Assigned(assign, now));
        for (UUID m : o.everyone()) {
            playing.put(m, assign.match());
            pendingSpectate.remove(m); pendingPractice.remove(m);
            Player p = online(m); if (p == null) continue;
            int n = o.everyone().size();
            toClient(p, new Status(KyudichiQueueProtocol.S_MOVING, 0, 0, n, n, true, parties(o.mode), o.mode));
            if (onArena(p)) { toArena(p, assign); continue; }
            origin.put(m, serverOf(p));
            host.notice(p, modeName(o.mode) + " 경기장으로 이동해요.");
            p.createConnectionRequest(arena).connect().whenComplete((r, err) -> host.enqueue(() -> {
                if (err == null && r.isSuccessful()) return;
                log.warn("큐디치 경기장 이동 실패: " + p.getUsername() + (err != null ? " " + err : " " + r.getStatus()));
                host.notice(p, "경기장으로 이동하지 못했어요. 다시 접속하면 경기장으로 들어가요.");
            }));
        }
    }

    // =============================================================== tick
    private void tick() {
        long now = System.currentTimeMillis();
        // entries whose people left or whose party changed
        for (var q : List.copyOf(queue.values())) {
            var e = q.entry();
            String gone = null;
            for (UUID m : e.members()) if (online(m) == null) { gone = host.name(m) + " 님이 접속을 끊어서"; break; }
            if (gone == null && e.members().size() > 1) {
                var party = host.party(q.leader());
                if (party == null || !new HashSet<>(party.members()).equals(new HashSet<>(e.members()))) gone = "파티 구성이 바뀌어서";
            }
            if (gone != null) {
                queue.remove(e.id());
                for (UUID m : e.members()) { queuedBy.remove(m); var mp = online(m); if (mp != null) toClient(mp, idle()); }
                sayAll(e.members(), gone + " " + modeName(q.mode()) + " 매칭이 취소됐어요.");
            }
        }
        for (Offer o : offerOf.values().stream().distinct().toList()) {
            if (now < o.deadline) continue;
            var out = new HashSet<UUID>(); for (UUID m : o.everyone()) if (!o.accepted.contains(m)) out.add(m);
            failOffer(o, out, "수락하지 않은 사람이 있어서");
        }
        // server error (crash · restart · lost connection): a match the arena reported running and then stopped reporting is void
        for (var e : List.copyOf(matches.entrySet()))
            if (e.getValue().seenLive && now - e.getValue().lastSeen > VOID_MS) voidMatch(e.getKey(), "경기장 서버 오류로 경기가 무효 처리됐어요 (레이팅 변동 없음).");
        matches.values().removeIf(a -> now - a.lastSeen > UNSEEN_MS);
        playing.values().removeIf(id -> !matches.containsKey(id));
        for (int mode = 0; mode < KyudichiQueueProtocol.MODES; mode++) {
            final int md = mode;
            int free = freeArenas(now, mode);
            while (free > 0) {
                var pair = KyudichiMatchmaker.find(queue.values().stream().filter(q -> q.mode() == md).map(Queued::entry).toList(), now, teamSize(mode));
                if (pair == null) break;
                offer(pair, now, mode, true); free--;
            }
        }
        status(now);
    }

    private void status(long now) {
        for (var q : queue.values()) {
            int waited = (int) Math.min(65535, (now - q.entry().since()) / 1000);
            int n = q.mode() == KyudichiQueueProtocol.MODE_DUEL ? queuedPlayers(q.mode()) : parties(q.mode());
            for (UUID m : q.entry().members()) {
                var p = online(m); if (p == null) continue;
                toClient(p, new Status(KyudichiQueueProtocol.S_SEARCHING, waited, 0, 0, 0, false, n, q.mode()));
            }
        }
        for (Offer o : offerOf.values().stream().distinct().toList()) {
            int left = (int) Math.max(0, (o.deadline - now + 999) / 1000);
            for (UUID m : o.everyone()) {
                var p = online(m); if (p == null) continue;
                toClient(p, new Status(KyudichiQueueProtocol.S_OFFER, 0, left, o.accepted.size(), o.everyone().size(), o.accepted.contains(m), parties(o.mode), o.mode));
            }
        }
    }

    // =============================================================== spectate / practice
    void spectate(Player p, String arena) {
        UUID me = p.getUniqueId();
        if (playing.containsKey(me) || offerOf.containsKey(me)) { host.notice(p, "경기 중에는 관전할 수 없어요."); return; }
        if (onArena(p)) { toArena(p, new Spectate(me, arena)); return; }
        var target = proxy.getServer(arenaServer).orElse(null);
        if (target == null) { host.notice(p, "경기장 서버를 찾을 수 없어요."); return; }
        pendingSpectate.put(me, arena); origin.put(me, serverOf(p));
        host.notice(p, "관전하러 경기장으로 이동해요.");
        p.createConnectionRequest(target).connect().whenComplete((r, err) -> host.enqueue(() -> {
            if (err == null && r.isSuccessful()) return;
            pendingSpectate.remove(me); origin.remove(me); host.notice(p, "경기장으로 이동하지 못했어요.");
        }));
    }

    private final Map<UUID, Integer> practiceMode = new HashMap<>();
    void practice(Player p) { practice(p, KyudichiQueueProtocol.MODE_KYUDICHI); }
    void practice(Player p, int mode) {
        mode = clampMode(mode);
        UUID me = p.getUniqueId();
        if (playing.containsKey(me) || offerOf.containsKey(me)) { host.notice(p, "경기 중이에요."); return; }
        if (queuedBy.containsKey(me)) { host.notice(p, "대기열에 있는 동안에는 연습장에 갈 수 없어요."); return; }
        if (onArena(p)) { toArena(p, new Practice(me, mode)); return; }
        var target = proxy.getServer(arenaServer).orElse(null);
        if (target == null) { host.notice(p, "경기장 서버를 찾을 수 없어요."); return; }
        pendingPractice.add(me); practiceMode.put(me, mode); origin.put(me, serverOf(p));
        host.notice(p, "연습장으로 이동해요.");
        p.createConnectionRequest(target).connect().whenComplete((r, err) -> host.enqueue(() -> {
            if (err == null && r.isSuccessful()) return;
            pendingPractice.remove(me); origin.remove(me); host.notice(p, "경기장으로 이동하지 못했어요.");
        }));
    }

    private void sendHome(UUID id) {
        playing.remove(id); pendingSpectate.remove(id); pendingPractice.remove(id);
        String to = origin.remove(id);
        Player p = online(id); if (p == null || !onArena(p)) return;
        String home = to != null && !to.equals(arenaServer) ? to : fallbackServer;
        // origin server, once more 2 s later, then the fallback (lobby) server — one failed connect never leaves them on the arena
        connectHome(p, home.equals(fallbackServer) ? List.of(home, home) : List.of(home, home, fallbackServer), 0);
    }

    /** The arena lost a running match: everyone in it is out of the match (back home if on the arena), nothing is recorded. */
    private void voidMatch(UUID match, String why) {
        var a = matches.remove(match);
        if (a == null) return;
        log.warn("큐디치 경기 무효: 경기장 서버가 " + (VOID_MS / 1000) + "초 넘게 경기를 알리지 않음 (" + match + ")");
        var all = new ArrayList<>(a.assign.teamA()); all.addAll(a.assign.teamB());
        for (UUID m : all) {
            if (!match.equals(playing.get(m))) continue;
            var p = online(m);
            if (p != null) { say(m, why); toClient(p, idle()); }
            if (p != null && onArena(p)) sendHome(m); // removes playing · origin
            else { playing.remove(m); origin.remove(m); }
        }
    }

    private void connectHome(Player p, List<String> targets, int i) {
        if (online(p.getUniqueId()) != p || !onArena(p)) return;
        if (i >= targets.size()) { host.notice(p, "원래 서버로 돌아가지 못했어요. /서버 로 이동해 주세요."); return; }
        String name = targets.get(i);
        var server = proxy.getServer(name).orElse(null);
        if (server == null) { log.warn("큐디치 복귀: 서버 " + name + " 를 찾을 수 없어요 (" + p.getUsername() + ")"); connectHome(p, targets, i + 1); return; }
        p.createConnectionRequest(server).connect().whenComplete((r, err) -> host.enqueue(() -> {
            if (err == null && r.isSuccessful()) { toClient(p, idle()); return; }
            log.warn("큐디치 복귀 실패 (" + name + "): " + p.getUsername() + (err != null ? " " + err : " " + r.getStatus()));
            proxy.getScheduler().buildTask(plugin, () -> host.enqueue(() -> connectHome(p, targets, i + 1))).delay(2, TimeUnit.SECONDS).schedule();
        }));
    }

    // =============================================================== dashboard
    private void dash(Player p) {
        long now = System.currentTimeMillis();
        var list = new ArrayList<LiveCard>();
        if (now - liveAt < LIVE_FRESH_MS) for (var lm : live) {
            var l = lm.live();
            if (l.mode() != KyudichiQueueProtocol.MODE_KYUDICHI) continue;
            list.add(new LiveCard(l.arena(), l.phase(), l.scoreA(), l.scoreB(), l.timeLeftMs(), l.spectators(), whos(lm.teamA()), whos(lm.teamB())));
        }
        toClient(p, new Dash(parties(), list));
    }
    private List<Who> whos(List<UUID> ids) {
        var out = new ArrayList<Who>();
        for (UUID id : ids) if (out.size() < 6) out.add(new Who(id, host.name(id), host.skin(id)));
        return out;
    }
    private Rank skinned(Rank r) { return r == null ? null : new Rank(r.rank(), r.id(), r.nick(), host.skin(r.id()), r.rating(), r.tier(), r.games(), r.wins(), r.losses()); }

    /** 신청 창: my party (or me alone) with houses and ratings; only the leader (or a solo player) can start. */
    private void apply(Player p) {
        UUID me = p.getUniqueId();
        var party = host.party(me);
        List<UUID> members = party == null || party.members().size() == 1 ? List.of(me) : party.members();
        UUID leader = party == null ? me : party.leader();
        db.standings(members, st -> host.enqueue(() -> {
            if (online(me) != p) return;
            var list = new ArrayList<Member>();
            int base = 0;
            for (UUID id : members) {
                if (list.size() >= 3) break;
                var s = st.get(id); int rating = s == null ? 1000 : s.rating();
                base = Math.max(base, rating);
                list.add(new Member(id, host.name(id), host.skin(id), host.house(id), rating, members.size() > 1 && id.equals(leader)));
            }
            var mine = st.get(me);
            boolean solo = members.size() == 1, three = members.size() == KyudichiMatchmaker.TEAM_SIZE;
            boolean can = (solo || three && leader.equals(me)) && !playing.containsKey(me);
            String note = solo ? "혼자 신청하면 랜덤 팀으로 매칭돼요." : !three ? "큐디치는 3인 파티 또는 혼자만 신청할 수 있어요." : "파티장만 시작할 수 있습니다.";
            toClient(p, new Apply(mine == null ? 1000 : mine.rating(), mine == null ? 0 : mine.wins(), mine == null ? 0 : mine.losses(), base, can, note, list));
        }));
    }
    private void ranking(Player p) {
        db.ranking(p.getUniqueId(), res -> host.enqueue(() -> {
            @SuppressWarnings("unchecked") var top = (List<Rank>) res[0];
            toClient(p, new Ranking(top.stream().map(this::skinned).toList(), skinned((Rank) res[1])));
            if (!modded.contains(p.getUniqueId())) {
                if (top.isEmpty()) { host.notice(p, db.available() ? "아직 랭킹이 없어요." : "랭킹을 불러올 수 없어요."); return; }
                host.notice(p, "── 큐디치 랭킹 ──");
                for (var r : top.subList(0, Math.min(10, top.size()))) host.notice(p, r.rank() + ". " + r.nick() + " · " + TIERS[r.tier()] + " " + r.rating() + " · " + r.wins() + "승 " + r.losses() + "패");
                if (res[1] instanceof Rank mine) host.notice(p, "내 순위 " + mine.rank() + "위 · " + TIERS[mine.tier()] + " " + mine.rating());
            }
        }));
    }
    static final String[] TIERS = {"브론즈", "실버", "골드", "플래티넘", "다이아몬드", "마스터", "챌린저 1", "챌린저 2", "챌린저 3"};
    private void record(Player p, String nick) {
        UUID id = nick == null || nick.isBlank() ? p.getUniqueId() : null;
        db.record(id, nick, rec -> host.enqueue(() -> {
            toClient(p, new RecordReply(rec == null ? null : new PlayerRecord(rec.id(), rec.nick(), host.skin(rec.id()), rec.rating(), rec.tier(), rec.rank(), rec.games(),
                    rec.wins(), rec.losses(), rec.draws(), rec.goals(), rec.kills(), rec.deaths(), rec.assists(), rec.snitch(), rec.deflectHits(), rec.mvp(), rec.damage(), rec.heal(), rec.recent())));
            if (!modded.contains(p.getUniqueId())) {
                if (rec == null) { host.notice(p, "기록이 없어요."); return; }
                host.notice(p, "── " + rec.nick() + " · " + TIERS[rec.tier()] + " " + rec.rating() + (rec.rank() > 0 ? " · " + rec.rank() + "위" : "") + " ──");
                host.notice(p, rec.games() + "경기 " + rec.wins() + "승 " + rec.losses() + "패 " + rec.draws() + "무 · 골 " + rec.goals() + " · 처치 " + rec.kills() + " · 데스 " + rec.deaths()
                        + " · 딜 " + Math.round(rec.damage()) + " · 힐 " + Math.round(rec.heal()) + " · 스니치 " + rec.snitch() + " · MVP " + rec.mvp());
            }
        }));
    }

    // =============================================================== 결투장 로비 (2026-10-18)
    /** Lobby frame: both modes' season / rating / tier / queue and my party. open = the server asks the client to show it (command). */
    private void lobby(Player p, int tab, int mode, boolean open) {
        UUID me = p.getUniqueId();
        var party = host.party(me);
        List<UUID> members = party == null || party.members().size() == 1 ? List.of(me) : party.members();
        UUID leader = party == null ? me : party.leader();
        final int tb = Math.clamp(tab, 0, 3), md = clampMode(mode);
        db.lobby(members, both -> host.enqueue(() -> {
            if (online(me) != p) return;
            long now = System.currentTimeMillis();
            var modes = new ArrayList<ModeInfo>();
            for (int m = 0; m < KyudichiQueueProtocol.MODES; m++) {
                var st = both.standings().get(m).get(me);
                int rating = st == null ? 1000 : st.rating();
                int tier = db.tier(rating, 0);
                var season = both.seasons().get(m);
                int left = season.number() == 0 ? 0 : (int) Math.clamp((season.endsAt() - now) / 1000, 0, Integer.MAX_VALUE);
                boolean busy = playing.containsKey(me) || offerOf.containsKey(me);
                boolean can; String note;
                if (m == KyudichiQueueProtocol.MODE_DUEL) {
                    can = !busy; note = busy ? "경기 중이에요." : "혼자 신청해 비슷한 레이팅의 상대와 1:1로 겨뤄요.";
                } else {
                    boolean solo = members.size() == 1, three = members.size() == KyudichiMatchmaker.TEAM_SIZE;
                    can = (solo || three && leader.equals(me)) && !busy;
                    note = busy ? "경기 중이에요." : solo ? "혼자 신청하면 랜덤 팀으로 매칭돼요." : !three ? "큐디치는 3인 파티 또는 혼자만 신청할 수 있어요." : leader.equals(me) ? "" : "파티장만 시작할 수 있습니다.";
                }
                modes.add(new ModeInfo(Math.min(season.number(), 65535), left, rating, tier, db.floor(tier), db.nextFloor(tier),
                        st == null ? 0 : st.wins(), st == null ? 0 : st.losses(), st == null ? 0 : st.draws(), Math.min(65535, queuedPlayers(m)), can, note));
            }
            var list = new ArrayList<Member>();
            var qst = both.standings().get(0);
            for (UUID id : members) {
                if (list.size() >= 3) break;
                var st = qst.get(id);
                list.add(new Member(id, host.name(id), host.skin(id), host.house(id), st == null ? 1000 : st.rating(), members.size() > 1 && id.equals(leader)));
            }
            toClient(p, new Lobby(open, tb, md, modes, list));
        }));
    }

    /** 진행 중인 경기 of one mode, with each fighter's rating and tier. */
    private void board(Player p, int mode) {
        final int md = clampMode(mode);
        long now = System.currentTimeMillis();
        var mine = now - liveAt < LIVE_FRESH_MS ? live.stream().filter(l -> l.live().mode() == md).limit(24).toList() : List.<LiveMatch>of();
        var ids = new ArrayList<UUID>(); for (var lm : mine) { ids.addAll(lm.teamA()); ids.addAll(lm.teamB()); }
        db.standings(ids, KyudichiDb.mode(md), st -> host.enqueue(() -> {
            if (online(p.getUniqueId()) != p) return;
            var cards = new ArrayList<Card>();
            for (var lm : mine) {
                var l = lm.live();
                var a = fighters(lm.teamA(), st); var b = fighters(lm.teamB(), st);
                String na = md == KyudichiQueueProtocol.MODE_DUEL ? first(a) : first(a).isEmpty() ? "" : first(a) + " 파티";
                String nb = md == KyudichiQueueProtocol.MODE_DUEL ? first(b) : first(b).isEmpty() ? "" : first(b) + " 파티";
                cards.add(new Card(l.arena(), l.phase(), l.scoreA(), l.scoreB(), l.timeLeftMs(), Math.min(255, l.spectators()), l.houseA(), l.houseB(), na, nb, a, b));
            }
            toClient(p, new Board(md, cards));
        }));
    }
    private List<Fighter> fighters(List<UUID> ids, Map<UUID, KyudichiDb.Standing> st) {
        var out = new ArrayList<Fighter>();
        for (UUID id : ids) {
            if (out.size() >= 3) break;
            var s = st.get(id); int rating = s == null ? 1000 : s.rating();
            out.add(new Fighter(id, host.name(id), host.skin(id), rating, db.tier(rating, 0)));
        }
        return out;
    }
    private static String first(List<Fighter> f) { return f.isEmpty() ? "" : f.getFirst().nick(); }

    void rememberName(UUID id,String name){db.rememberName(id,name);}

    private void ranks(Player p, int mode, int page, String search) {
        final int md = clampMode(mode);
        db.ranks(p.getUniqueId(), KyudichiDb.mode(md), Math.max(1, page), search, KyudichiQueueProtocol.RANK_PAGE, pg -> host.enqueue(() -> {
            if (online(p.getUniqueId()) != p) return;
            var rows = pg.rows().stream().map(this::skinned).toList();
            toClient(p, new Ranks(md, pg.page(), pg.pages(), rows, skinned(pg.mine()), pg.highlight(), pg.notice()));
        }));
    }
    private Row skinned(Row r) { return r == null ? null : new Row(r.rank(), r.id(), r.nick(), host.skin(r.id()), r.rating(), r.tier(), r.wins(), r.losses(), r.draws()); }

    private void profile(Player p, int mode, String nick) {
        final int md = clampMode(mode);
        String q = nick == null ? "" : nick.strip();
        UUID id = q.isEmpty() ? p.getUniqueId() : null;
        db.profile(id, q, KyudichiDb.mode(md), found -> host.enqueue(() -> {
            if (online(p.getUniqueId()) != p) return;
            Profile out = null;
            if (found != null) {
                var f = found.profile();
                var games = new ArrayList<Game>();
                for (int i = 0; i < f.recent().size(); i++) {
                    var g = f.recent().get(i); UUID opp = found.opponents().get(i);
                    games.add(new Game(g.endedAt(), g.outcome(), g.delta(), g.ratingAfter(), g.goals(), g.kills(), g.deaths(), g.damage(), g.myScore(), g.theirScore(),
                            opp != null && g.opponent().isEmpty() ? host.name(opp) : g.opponent(), opp != null ? host.skin(opp) : "", g.opponentHouse()));
                }
                out = new Profile(f.id(), f.nick(), host.skin(f.id()), f.rating(), f.tier(), f.rank(), f.games(), f.wins(), f.losses(), f.draws(), f.goals(), f.kills(),
                        f.deaths(), f.snitch(), f.deflectHits(), f.damage(), f.heal(), games);
            }
            toClient(p, new ProfileReply(md, q, out));
        }));
    }

    // =============================================================== events
    @Subscribe public void queueMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(QUEUE)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof Player p)) return; // only the client talks on this channel
        FromClient req;
        try { req = KyudichiQueueProtocol.decodeFromClient(event.getData().clone()); } catch (IllegalArgumentException e) { return; }
        host.enqueue(() -> {
            if (online(p.getUniqueId()) != p) return;
            switch (req) {
                case ClientHello h -> { modded.add(p.getUniqueId()); status(System.currentTimeMillis()); }
                case Join j -> join(p);
                case Leave l -> leave(p, null);
                case Accept a -> accept(p);
                case Decline d -> decline(p);
                case DashRequest d -> dash(p);
                case RankingRequest r -> ranking(p);
                case RecordRequest r -> record(p, r.nick());
                case SpectateRequest s -> spectate(p, s.arena());
                case PracticeRequest x -> practice(p);
                case ApplyRequest x -> apply(p);
                case JoinMode j -> join(p, j.mode());
                case LobbyRequest l -> lobby(p, l.tab(), l.mode(), false);
                case BoardRequest b -> board(p, b.mode());
                case RanksRequest r -> ranks(p, r.mode(), r.page(), r.search());
                case ProfileRequest r -> profile(p, r.mode(), r.nick());
                case PracticeMode x -> practice(p, x.mode());
            }
        });
    }

    @Subscribe public void linkMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(LINK)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // never forwarded either way
        if (!(event.getSource() instanceof ServerConnection source) || !source.getServerInfo().getName().equals(arenaServer)) return;
        ToProxy msg;
        try { msg = KyudichiQueueProtocol.decodeToProxy(event.getData().clone()); } catch (IllegalArgumentException e) { return; }
        Player via = source.getPlayer();
        host.enqueue(() -> {
            switch (msg) {
                case Hello h -> {
                    if (!h.player().equals(via.getUniqueId())) return;
                    UUID me = via.getUniqueId();
                    UUID match = playing.get(me);
                    var a = match == null ? null : matches.get(match);
                    // a match the arena already ran: its own rejoin (Matches.joined) puts them back; never re-assign it
                    // (after an arena restart that would start the lost match again as a new one — the tick voids it instead)
                    if (a != null) { if (!a.seenLive) toArena(via, a.assign); }
                    else if (pendingSpectate.containsKey(me)) toArena(via, new Spectate(me, pendingSpectate.remove(me)));
                    else if (pendingPractice.remove(me)) { Integer pm = practiceMode.remove(me); toArena(via, new Practice(me, pm == null ? 0 : pm)); }
                }
                case LiveList l -> {
                    live = l.matches(); liveFree = l.freeArenas(); liveFreeDuel = l.freeDuelArenas(); liveAt = System.currentTimeMillis();
                    for (var lm : live) { var a = matches.get(lm.match()); if (a != null) { a.lastSeen = liveAt; a.seenLive = true; } }
                    // the arena lists at most 16: when full, an unlisted match may still be running — don't let it go stale
                    if (live.size() >= 16) for (var a : matches.values()) if (a.seenLive) a.lastSeen = liveAt;
                }
                case Return r -> { for (UUID id : r.players()) sendHome(id); }
                case Ended e -> {
                    var a = matches.remove(e.match());
                    if (a != null) { var all = new ArrayList<>(a.assign.teamA()); all.addAll(a.assign.teamB()); for (UUID id : all) playing.remove(id, e.match()); }
                }
                case AdminRequest r -> {
                    AdminReply reply;
                    try { reply = admin(r); }
                    catch (IllegalArgumentException e) { reply = new AdminReply(r.request(), false, e.getMessage(), List.of()); }
                    catch (IOException | RuntimeException e) {
                        log.error("큐디치·파티 관리 요청 실패 (" + r.action() + " " + r.target() + ")", e);
                        reply = new AdminReply(r.request(), false, "명령어 사용에 실패했습니다", List.of());
                    }
                    reply(source, reply);
                }
                case Custom c -> {
                    AdminReply reply;
                    try { reply = new AdminReply(c.request(), true, custom(c), List.of()); }
                    catch (IllegalArgumentException e) { reply = new AdminReply(c.request(), false, e.getMessage(), List.of()); }
                    catch (RuntimeException e) {
                        log.error("큐디치 커스텀 경기 요청 실패", e);
                        reply = new AdminReply(c.request(), false, "명령어 사용에 실패했습니다", List.of());
                    }
                    reply(source, reply);
                }
            }
        });
    }

    private void reply(ServerConnection source, AdminReply reply) {
        try { source.sendPluginMessage(LINK, KyudichiQueueProtocol.encode(reply)); }
        catch (RuntimeException e) { log.warn("관리 요청 답을 보내지 못함: " + e); }
    }

    // =============================================================== custom match (/큐디치관리 경기 커스텀 on the arena server)
    /**
     * 커스텀 경기 (#62, 2026-10-25): the two teams the admin named, no queue, no rating. Same accept check (10 s) and
     * transfer as a found match. @return message for the admin; IllegalArgumentException = refusal shown to the admin.
     */
    private String custom(Custom c) {
        int md = clampMode(c.mode()), size = teamSize(md);
        if (c.teamA().size() > size || c.teamB().size() > size) throw new IllegalArgumentException(modeName(md) + " 커스텀 경기는 팀마다 " + size + "명까지예요");
        var all = new ArrayList<UUID>(c.teamA()); all.addAll(c.teamB());
        if (new HashSet<>(all).size() != all.size()) throw new IllegalArgumentException("같은 사람이 두 번 들어 있어요");
        for (UUID m : all) {
            Player mp = online(m);
            if (mp == null) throw new IllegalArgumentException(host.name(m) + " 님이 접속 중이 아니에요");
            String s = serverOf(mp);
            if (!host.trusted(s) && !s.equals(arenaServer)) throw new IllegalArgumentException(host.name(m) + " 님이 있는 곳에서는 경기에 부를 수 없어요");
            if (queuedBy.containsKey(m) || offerOf.containsKey(m) || playing.containsKey(m) || joining.contains(m))
                throw new IllegalArgumentException(host.name(m) + " 님은 대기열·매칭·경기 중이에요 (/유저관리 " + host.name(m) + " " + (md == KyudichiQueueProtocol.MODE_DUEL ? "결투" : "큐디치") + " 매칭상태초기화)");
            if (pendingSpectate.containsKey(m) || pendingPractice.contains(m)) throw new IllegalArgumentException(host.name(m) + " 님이 경기장으로 이동하는 중이에요");
        }
        long now = System.currentTimeMillis();
        if (proxy.getServer(arenaServer).isEmpty()) throw new IllegalArgumentException("Velocity 에 경기장 서버 '" + arenaServer + "' 가 없어요");
        if (freeArenas(now, md) <= 0) throw new IllegalArgumentException("비어 있는 " + (md == KyudichiQueueProtocol.MODE_DUEL ? "1:1 결투장" : "큐디치 경기장") + "이 없어요");
        var a = new KyudichiMatchmaker.Entry(UUID.randomUUID(), List.copyOf(c.teamA()), 0, now);
        var b = new KyudichiMatchmaker.Entry(UUID.randomUUID(), List.copyOf(c.teamB()), 0, now);
        offer(new KyudichiMatchmaker.Pair(KyudichiMatchmaker.Team.of(List.of(a), size), KyudichiMatchmaker.Team.of(List.of(b), size)), now, md, false);
        log.info(modeName(md) + " 커스텀 경기 초대: " + all.stream().map(host::name).toList());
        return modeName(md) + " 커스텀 경기에 " + all.size() + "명을 초대했어요 · 모두 수락하면 경기장으로 와요 (레이팅 미반영)";
    }

    // =============================================================== admin (/유저관리 <닉네임> 큐디치|결투|파티 on the arena server)
    private AdminReply admin(AdminRequest r) throws IOException {
        UUID target = r.target(); int mode = clampMode(r.mode());
        String who = host.name(target);
        if (r.action() == KyudichiQueueProtocol.ADMIN_PARTY_VIEW) return new AdminReply(r.request(), true, "", host.partyView(target));
        String done = switch (r.action()) {
            case KyudichiQueueProtocol.ADMIN_QUEUE_REMOVE -> {
                if (!removeQueued(target, mode, "관리자가 " + who + " 님을 " + modeName(mode) + " 대기열에서 뺐어요."))
                    throw new IllegalArgumentException(who + " 님은 " + modeName(mode) + " 대기열에 없어요");
                yield who + " 님을 " + modeName(mode) + " 대기열에서 뺐어요";
            }
            case KyudichiQueueProtocol.ADMIN_MATCH_RESET -> {
                var cleared = new ArrayList<String>();
                if (removeQueued(target, mode, "관리자가 " + who + " 님의 매칭 상태를 초기화했어요.")) cleared.add("대기열·수락");
                UUID match = playing.get(target);
                if (match != null && (!matches.containsKey(match) || matches.get(match).assign.mode() == mode)) { playing.remove(target); cleared.add("경기 배정"); }
                if (joining.remove(target)) cleared.add("신청 중");
                if (pendingSpectate.remove(target) != null) cleared.add("관전 이동");
                if (pendingPractice.remove(target)) cleared.add("연습장 이동");
                practiceMode.remove(target);
                if (!playing.containsKey(target) && origin.remove(target) != null) cleared.add("돌아갈 서버");
                var p = online(target); if (p != null) toClient(p, idle());
                yield who + " 님의 " + modeName(mode) + " 매칭 상태를 초기화했어요" + (cleared.isEmpty() ? " (남은 상태 없음)" : " (" + String.join(" · ", cleared) + ")");
            }
            case KyudichiQueueProtocol.ADMIN_PARTY_REMOVE -> host.partyRemove(target);
            case KyudichiQueueProtocol.ADMIN_PARTY_DISBAND -> host.partyDisband(target);
            default -> throw new IllegalArgumentException("모르는 관리 요청이에요");
        };
        return new AdminReply(r.request(), true, done, List.of());
    }

    /** Takes the player's queue entry (whole party) or match offer of this mode away. @return whether anything was removed */
    private boolean removeQueued(UUID target, int mode, String why) {
        boolean removed = false;
        UUID eid = queuedBy.get(target);
        var q = eid == null ? null : queue.get(eid);
        if (q != null && q.mode() == mode) {
            queue.remove(eid);
            for (UUID m : q.entry().members()) { queuedBy.remove(m); var mp = online(m); if (mp != null) toClient(mp, idle()); }
            sayAll(q.entry().members(), why);
            removed = true;
        }
        var o = offerOf.get(target);
        if (o != null && o.mode == mode) { failOffer(o, Set.of(target), why); removed = true; }
        if (removed) status(System.currentTimeMillis());
        return removed;
    }

    @Subscribe public void initialServer(PlayerChooseInitialServerEvent event) {
        // Concurrent read from the event thread; a stale read just means "lobby as usual".
        if (playing.containsKey(event.getPlayer().getUniqueId())) proxy.getServer(arenaServer).ifPresent(event::setInitialServer);
    }

    /**
     * #75 (2026-10-25): a match player who lands on another server while their match is still on (kicked off the arena by a
     * connection problem — Velocity moves them to the fallback server) is sent back once a minute at most; the arena's
     * 60 s reconnect window decides whether they still play. A full reconnect is handled by initialServer.
     */
    @Subscribe public void serverConnected(ServerPostConnectEvent event) {
        Player p = event.getPlayer(); UUID me = p.getUniqueId();
        if (!playing.containsKey(me)) return; // concurrent read; the worker re-checks
        host.enqueue(() -> {
            if (online(me) != p || onArena(p)) return;
            UUID match = playing.get(me);
            var a = match == null ? null : matches.get(match);
            long now = System.currentTimeMillis();
            if (a == null || a.seenLive && now - a.lastSeen > LIVE_FRESH_MS) return; // not running on the arena now (the tick voids it)
            if (now - rejoinAt.getOrDefault(me, 0L) < REJOIN_RETRY_MS) return;
            var arena = proxy.getServer(arenaServer).orElse(null);
            if (arena == null) return;
            rejoinAt.put(me, now);
            host.notice(p, modeName(a.assign.mode()) + " 경기가 아직 진행 중이에요. 경기장으로 다시 들어가요.");
            p.createConnectionRequest(arena).connect().whenComplete((r, err) -> host.enqueue(() -> {
                if (err == null && r.isSuccessful()) return;
                log.warn("큐디치 경기장 재입장 실패: " + p.getUsername() + (err != null ? " " + err : " " + r.getStatus()));
                host.notice(p, "경기장으로 돌아가지 못했어요. 다시 접속하면 경기장으로 들어가요.");
            }));
        });
    }

    @Subscribe public void disconnect(DisconnectEvent event) {
        Player p = event.getPlayer(); UUID me = p.getUniqueId();
        host.enqueue(() -> {
            modded.remove(me); rejoinAt.remove(me);
            if (queuedBy.containsKey(me)) leave(p, host.name(me) + " 님이 접속을 끊어서 " + modeName(modeOf(me)) + " 매칭이 취소됐어요.");
            var o = offerOf.get(me);
            if (o != null) failOffer(o, Set.of(me), host.name(me) + " 님이 접속을 끊었어요.");
            pendingSpectate.remove(me); pendingPractice.remove(me); practiceMode.remove(me);
            if (!playing.containsKey(me)) origin.remove(me);
        });
    }

    // =============================================================== command
    /**
     * /큐디치 · /결투: 로비 창만 연다 (신청·취소·관전·랭킹·전적·연습은 전부 창에서, 포기·준비는 경기 창에서).
     * MagicCodex 모드가 없는 사람에게는 짧은 안내만.
     */
    private LiteralArgumentBuilder<CommandSource> lobbyCommand(String name, int mode) {
        return LiteralArgumentBuilder.<CommandSource>literal(name).executes(ctx -> {
            if (!(ctx.getSource() instanceof Player p)) { ctx.getSource().sendMessage(Component.text("플레이어 전용 명령입니다.")); return 0; }
            if (!host.enqueue(() -> {
                if (online(p.getUniqueId()) != p) return;
                if (!modded.contains(p.getUniqueId())) { host.notice(p, name + " 창은 MagicCodex 모드가 있어야 열려요."); return; }
                lobby(p, KyudichiQueueProtocol.TAB_MATCH, mode, true);
            })) host.notice(p, "요청이 많아요. 잠시 후 다시 시도해 주세요.");
            return Command.SINGLE_SUCCESS;
        });
    }
}
