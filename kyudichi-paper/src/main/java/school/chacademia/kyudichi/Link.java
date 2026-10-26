package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol.*;

/**
 * Velocity 대기열과의 연결 ({@value KyudichiQueueProtocol#LINK}). 프록시가 보낸 사람만 "네트워크 참가자"로 기억하고,
 * 그 사람의 경기·관전·연습이 끝나면 프록시에 RETURN 을 보내 원래 서버로 돌려보낸다.
 * 이 서버에 직접 들어온 관리자(테스트)는 건드리지 않는다.
 *
 * 매칭 경기: 배정된 6명이 모두 오면 바로, 아니면 처음 도착 후 {@link #GATHER_TICKS} 뒤에 온 사람끼리 시작한다
 * (안 온 사람은 접속 끊김으로 처리 → 60초 안에 들어오면 합류). 한 팀이 아무도 없으면 경기 무효.
 */
final class Link implements PluginMessageListener {
    static final int GATHER_TICKS = 30 * 20, RETURN_DELAY_TICKS = 6 * 20, LIVE_EVERY_TICKS = 40;

    private final KyudichiPlugin plugin;
    private static final class Gather { final Assign assign; final long firstAt; Gather(Assign a, long t) { assign = a; firstAt = t; } }
    private final Map<UUID, Gather> gathering = new LinkedHashMap<>();   // assign id → waiting room
    private final Map<UUID, UUID> started = new HashMap<>();              // assign id → Match.id
    private final Set<UUID> networked = new HashSet<>();
    private final Set<UUID> keepOnLeave = new HashSet<>();               // spectate left because a match was assigned
    private long tick;
    /** 관리 요청 (/유저관리 … 대기열에서빼기 · 매칭상태초기화 · 파티) → 요청한 사람, 답을 기다리는 동안. */
    private record Pending(CommandSender to, long sentAt) {}
    private final Map<UUID, Pending> adminPending = new HashMap<>();
    static final int ADMIN_TIMEOUT_TICKS = 5 * 20;

    Link(KyudichiPlugin plugin) { this.plugin = plugin; }

    void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, KyudichiQueueProtocol.LINK);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, KyudichiQueueProtocol.LINK, this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }
    void unregister() {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, KyudichiQueueProtocol.LINK);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, KyudichiQueueProtocol.LINK, this);
    }

    private void send(Player via, ToProxy m) {
        if (via == null || !via.isOnline()) return;
        try { via.sendPluginMessage(plugin, KyudichiQueueProtocol.LINK, KyudichiQueueProtocol.encode(m)); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("큐디치 링크 인코딩 실패: " + e.getMessage()); }
    }
    static void notice(Player p, String kind, String title, String body) { ChacaLink.notify(p, kind, "kyudichi:" + title, title, body, 1500); }
    private Player anyone() { for (Player p : Bukkit.getOnlinePlayers()) return p; return null; }

    // ================================================================== join / quit
    /** A second after joining (the proxy connection is ready by then) ask the proxy what this player is here for. */
    void joined(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p, new Hello(p.getUniqueId())); }, 20);
    }
    void quit(Player p) {
        if (plugin.matches().of(p.getUniqueId()) == null && !inGather(p.getUniqueId())) networked.remove(p.getUniqueId());
    }
    private boolean inGather(UUID id) {
        for (var g : gathering.values()) if (g.assign.teamA().contains(id) || g.assign.teamB().contains(id)) return true;
        return false;
    }

    // ================================================================== from the proxy
    @Override public void onPluginMessageReceived(String channel, Player via, byte[] bytes) {
        if (!KyudichiQueueProtocol.LINK.equals(channel)) return;
        ToArena msg;
        try { msg = KyudichiQueueProtocol.decodeToArena(bytes); } catch (IllegalArgumentException e) { return; }
        switch (msg) {
            case Assign a -> assign(a);
            case KyudichiQueueProtocol.Spectate s -> spectate(s);
            case Practice x -> practice(x);
            case AdminReply r -> adminReply(r);
        }
    }

    // ================================================================== admin requests (state on the proxy)
    /** Sends an admin request through any online player (plugin messages need a connection); the reply comes back as AdminReply. */
    void admin(CommandSender to, int action, UUID target, int mode) {
        Player via = anyone();
        if (via == null) { ChacaLink.fail(to, "이 서버에 접속한 사람이 없어 프록시에 요청을 보낼 수 없어요", null); return; }
        UUID request = UUID.randomUUID();
        adminPending.put(request, new Pending(to, tick));
        send(via, new AdminRequest(request, action, target, mode));
    }

    /** /큐디치관리 경기 커스텀: the proxy checks everyone, asks them to accept and assigns an unrated match here (#62). */
    void custom(CommandSender to, int mode, List<UUID> a, List<UUID> b) {
        Player via = anyone();
        if (via == null) { ChacaLink.fail(to, "이 서버에 접속한 사람이 없어 프록시에 요청을 보낼 수 없어요", null); return; }
        UUID request = UUID.randomUUID();
        adminPending.put(request, new Pending(to, tick));
        send(via, new Custom(request, mode, a, b));
    }

    private void adminReply(AdminReply r) {
        var p = adminPending.remove(r.request());
        if (p == null) return;
        if (!r.ok()) { ChacaLink.fail(p.to(), r.message().isBlank() ? "명령어 사용에 실패했습니다" : r.message(), null); return; }
        if (!r.lines().isEmpty()) ChacaLink.lines(p.to(), r.lines());
        else ChacaLink.ok(p.to(), r.message());
    }

    private void assign(Assign a) {
        if (started.containsKey(a.match())) return; // late arrival: Matches.joined() puts them back in
        var all = new ArrayList<>(a.teamA()); all.addAll(a.teamB());
        networked.addAll(all);
        gathering.computeIfAbsent(a.match(), k -> new Gather(a, tick));
        for (UUID id : all) {
            plugin.store().loadAll(id, plugin.hooks().nick(Bukkit.getOfflinePlayer(id)));
            Player p = Bukkit.getPlayer(id);
            if (p != null && plugin.spectate().spectating(id)) { keepOnLeave.add(id); plugin.spectate().leave(p); keepOnLeave.remove(id); }
            if (p != null) notice(p, "system", "경기 준비 중", "모두 모이면 시작해요");
        }
        tryStart(a.match(), false);
    }

    private void spectate(KyudichiQueueProtocol.Spectate s) {
        Player p = Bukkit.getPlayer(s.player()); if (p == null) return;
        networked.add(p.getUniqueId());
        Match m = s.arena().isBlank() ? plugin.matches().all().stream().filter(x -> !x.opt.test()).findFirst().orElse(null) : plugin.matches().inArena(s.arena());
        String err = m == null ? "관전할 경기가 없어요." : plugin.spectate().enter(p, m);
        if (err != null) { notice(p, "warning", "관전할 수 없어요", err); done(p.getUniqueId()); }
    }

    private void practice(Practice x) {
        Player p = Bukkit.getPlayer(x.player()); if (p == null) return;
        networked.add(p.getUniqueId());
        boolean duel = x.mode() == KyudichiQueueProtocol.MODE_DUEL;
        Arena ar = pick(duel, true);
        String err;
        if (ar == null) err = duel ? "비어 있는 1:1 연습장이 없어요." : "비어 있는 연습장이 없어요.";
        else {
            var teams = new EnumMap<Team, List<Player>>(Team.class);
            teams.put(Team.A, List.of(p)); teams.put(Team.B, List.of());
            err = plugin.matches().start(ar, teams, new Match.Options(false, true, 0, plugin.rules().playTicks, 0, true, Set.of()));
        }
        if (err != null) { notice(p, "warning", "연습장에 들어갈 수 없어요", err); done(p.getUniqueId()); }
        else notice(p, "system", duel ? "1:1 연습장" : "큐디치 연습장", duel ? "마법을 자유롭게 연습해 보세요. 끝내려면 경기 창에서 포기"
                : "F로 퀘이플 잡기·던지기, 블러저 쳐내기, 스니치 잡기를 해 보세요. 끝내려면 경기 창에서 포기");
    }

    // ================================================================== starting assigned matches
    private void tryStart(UUID id, boolean timeUp) {
        var g = gathering.get(id); if (g == null) return;
        var present = new EnumMap<Team, List<Player>>(Team.class); var absent = new EnumMap<Team, List<UUID>>(Team.class);
        int here = 0;
        for (Team t : Team.values()) {
            present.put(t, new ArrayList<>()); absent.put(t, new ArrayList<>());
            for (UUID u : t == Team.A ? g.assign.teamA() : g.assign.teamB()) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline() && plugin.matches().of(u) == null) { present.get(t).add(p); here++; } else absent.get(t).add(u);
            }
        }
        int total = g.assign.teamA().size() + g.assign.teamB().size();
        if (here < total && !timeUp) return;
        gathering.remove(id);
        if (present.get(Team.A).isEmpty() || present.get(Team.B).isEmpty()) {
            plugin.getLogger().info("큐디치 매칭 경기 무효: 한 팀이 오지 않음");
            for (var l : present.values()) for (Player p : l) { notice(p, "warning", "경기 취소", "상대가 오지 않아 경기가 취소됐어요"); done(p.getUniqueId()); }
            send(anyone(), new Ended(id));
            return;
        }
        boolean duel = g.assign.mode() == KyudichiQueueProtocol.MODE_DUEL;
        Arena ar = pick(duel, false);
        var r = plugin.rules();
        String err = ar == null ? (duel ? "비어 있는 1:1 결투장이 없어요." : "비어 있는 경기장이 없어요.") : plugin.matches().start(ar, present, new Match.Options(g.assign.rated(), false,
                duel ? r.duelPrepareTicks : r.prepareTicks, duel ? r.duelRoundTicks : r.playTicks, r.snitchAtTicks, !duel, Set.of()), absent);
        if (err != null) {
            plugin.getLogger().warning("큐디치 매칭 경기 시작 실패: " + err);
            for (var l : present.values()) for (Player p : l) { notice(p, "warning", "경기를 시작하지 못했어요", "원래 서버로 돌아가요"); done(p.getUniqueId()); }
            send(anyone(), new Ended(id));
            return;
        }
        started.put(id, plugin.matches().inArena(ar.name).id);
    }

    /** A free enabled arena of the kind (1:1 or 큐디치), chosen at random (owner: 등록된 맵 중 랜덤). */
    private Arena pick(boolean duel, boolean practice) {
        var free = plugin.arenas().all().stream().filter(a -> a.duel == duel && (practice ? a.practice : a.enabled && !a.practice) && a.problems().isEmpty()
                && plugin.matches().inArena(a.name) == null).toList();
        return free.isEmpty() ? null : free.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(free.size()));
    }
    private int free(boolean duel) {
        return (int) plugin.arenas().all().stream().filter(a -> a.duel == duel && a.enabled && !a.practice && a.problems().isEmpty() && plugin.matches().inArena(a.name) == null).count();
    }

    // ================================================================== ends
    /** A networked player has nothing more to do here: back to their own server shortly (result card stays readable). */
    void done(UUID id) {
        if (!networked.contains(id) || keepOnLeave.contains(id)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Match mm = plugin.matches().of(id);
            if ((mm != null && mm.playing(id)) || plugin.spectate().spectating(id) || inGather(id)) return;
            Player p = Bukkit.getPlayer(id);
            if (p == null) { networked.remove(id); return; }
            networked.remove(id);
            send(p, new Return(List.of(id)));
        }, RETURN_DELAY_TICKS);
    }

    void matchEnded(Match m) {
        for (var e : started.entrySet()) if (e.getValue().equals(m.id)) { send(anyone(), new Ended(e.getKey())); started.remove(e.getKey()); break; }
        for (UUID id : m.players.keySet()) done(id);
    }

    // ================================================================== periodic
    private void tick() {
        tick += 20;
        for (var it = adminPending.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (tick - e.getValue().sentAt() < ADMIN_TIMEOUT_TICKS) continue;
            it.remove();
            plugin.getLogger().warning("큐디치 관리 요청에 프록시 답이 없음 (Velocity chacademi-party 버전 확인)");
            ChacaLink.fail(e.getValue().to(), "프록시가 답하지 않아요", null);
        }
        for (UUID id : List.copyOf(gathering.keySet())) {
            var g = gathering.get(id);
            if (g != null) tryStart(id, tick - g.firstAt >= GATHER_TICKS);
        }
        if (tick % LIVE_EVERY_TICKS == 0) live();
    }

    private void live() {
        Player via = anyone(); if (via == null) return;
        long gatherDuel = gathering.values().stream().filter(g -> g.assign.mode() == KyudichiQueueProtocol.MODE_DUEL).count();
        int free = Math.max(0, free(false) - (int) (gathering.size() - gatherDuel)), freeDuel = Math.max(0, free(true) - (int) gatherDuel);
        var list = new ArrayList<LiveMatch>();
        var byMatch = new HashMap<UUID, UUID>(); for (var e : started.entrySet()) byMatch.put(e.getValue(), e.getKey());
        for (Match m : plugin.matches().all()) {
            if (m.opt.test() || list.size() >= 16) continue;
            var msg = m.matchMessage(2);
            var a = new ArrayList<UUID>(); var b = new ArrayList<UUID>(); var na = new ArrayList<String>(); var nb = new ArrayList<String>();
            int ha = -1, hb = -1;
            for (var mp : m.players.values()) {
                if (mp.team == Team.A) { a.add(mp.id); na.add(mp.nick); if (mp.slot == 0) ha = mp.house; } else { b.add(mp.id); nb.add(mp.nick); if (mp.slot == 0) hb = mp.house; }
            }
            UUID key = byMatch.getOrDefault(m.id, m.id);
            list.add(new LiveMatch(key, new Live(m.arena.name, msg.phase(), msg.scoreA(), msg.scoreB(), msg.timeLeftMs(), m.spectators.size(),
                    String.join(" · ", na), String.join(" · ", nb), m.duel ? KyudichiQueueProtocol.MODE_DUEL : KyudichiQueueProtocol.MODE_KYUDICHI, ha, hb),
                    a.subList(0, Math.min(6, a.size())), b.subList(0, Math.min(6, b.size()))));
        }
        send(via, new LiveList(free, list, freeDuel));
    }
}
