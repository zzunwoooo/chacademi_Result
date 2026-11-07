package kr.kkaegul.core.casino.holdem;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.casino.CasinoSupport;
import kr.kkaegul.core.casino.holdem.HoldemTable.Move;
import kr.kkaegul.core.minigame.Minigame;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static kr.kkaegul.core.casino.CasinoSupport.num;

/**
 * 텍사스 홀덤 (노리밋) 미니게임 — 엘시 경제서버 홀덤 플레이어 대전을 그대로 옮김.
 * 방 만들기·준비·AI 대전 없음: 미니게임이 시작되면 뽑힌 유저를 3인(또는 2인) 테이블로 고르게 나누고,
 * 한 명씩 순서대로 자리에 앉힌 뒤 바로 시작. 정해진 시간(/깨굴 게임 시간 poker 1h)이 지나면
 * 진행 중인 판까지만 하고 남은 칩을 다이아로 돌려준다. 이긴 칩에서 수수료(rake-percent)를 뗀다.
 */
public final class PokerGame extends Minigame {
    public static final String GAME = "holdem";

    private enum Phase { WAIT, BET, BREAK }

    private static final class Room {
        final int no;
        long bb;
        final UUID[] seat = new UUID[3];
        final String[] names = {"", "", ""};
        final String[] avatar = {"", "", ""};
        Phase phase = Phase.WAIT;
        HoldemTable table;
        long turnStarted, breakUntil;
        String log = "";
        int dealer, expected, hands, summaryId, lastStreet;
        boolean session, closed;
        final boolean[] inSession = new boolean[3];
        final boolean[] hadSession = new boolean[3];
        final long[] stack = new long[3];
        final long[] buyIn = new long[3];
        final long[] sessNet = new long[3];
        final long[] rake = new long[3];
        String summaryReason = "";
        final JsonObject[] results = new JsonObject[3];

        Room(int no) { this.no = no; }

        int count() { int n = 0; for (UUID u : seat) if (u != null) n++; return n; }
        int seatOf(UUID u) { for (int i = 0; i < 3; i++) if (u.equals(seat[i])) return i; return -1; }
        boolean playing() { return phase == Phase.BET; }
        int sessionCount() { int n = 0; for (int i = 0; i < 3; i++) if (inSession[i] && seat[i] != null && stack[i] > 0) n++; return n; }
    }

    private final List<Room> rooms = new ArrayList<>();
    private final Map<UUID, Room> memberRoom = new HashMap<>();
    private long endsAt;
    private boolean timeUp;
    private BukkitTask task;
    private int tickN;

    public PokerGame(KkaegulCore plugin) { super(plugin, "poker"); }

    @Override public String label() { return "포커"; }
    @Override public boolean timed() { return true; }
    @Override public int minPlayers() { return Math.max(2, super.minPlayers()); }
    @Override public String readiness() { return null; }

    private long bbCfg() { return Math.max(1, cfgInt("big-blind", 1)); }
    /** 입장 칩: buyin-all 이면 가진 다이아 전부 (제한 없음) */
    private long buyInCfg() { return cfgBool("buyin-all", true) ? Long.MAX_VALUE : bbCfg() * Math.max(10, cfgInt("buyin-bb", 50)); }
    /** 초대장 받는 최소 보유 다이아 */
    private long minBuyIn() { return Math.max(bbCfg() * 2, plugin.getConfig().getLong("minigame.casino.min-held", 50)); }
    private long turnLimit() { return cfgInt("turn-seconds", 20) * 1000L; }
    private long breakMs() { return cfgInt("break-seconds", 5) * 1000L; }

    /** 입장 칩(최소)만큼 다이아가 있어야 뽑힘 */
    @Override public boolean eligible(Player p) { return CasinoSupport.available(plugin, p) >= minBuyIn(); }

    // ───────── 시작: 테이블 나누고 한 명씩 앉히기 ─────────
    @Override
    protected void start(List<Player> list) {
        back.clear(); // 이 게임은 자리 이동이 없음 (창에서 진행)
        rooms.clear();
        memberRoom.clear();
        timeUp = false;
        endsAt = System.currentTimeMillis() + durationMinutes() * 60_000L;
        List<Player> pool = new ArrayList<>(list);
        Collections.shuffle(pool);
        List<Integer> sizes = CasinoSupport.split(pool.size());
        int k = 0, no = 1;
        List<List<UUID>> groups = new ArrayList<>();
        long gap = Math.max(4, cfgInt("seat-interval-ticks", 12));
        long delay = 10;
        for (int size : sizes) {
            Room r = new Room(no++);
            r.bb = bbCfg();
            r.expected = size;
            r.dealer = ThreadLocalRandom.current().nextInt(3);
            rooms.add(r);
            List<String> av = new ArrayList<>(CasinoSupport.AVATARS);
            Collections.shuffle(av);
            List<UUID> group = new ArrayList<>();
            groups.add(group);
            for (int s = 0; s < size; s++) {
                Player p = pool.get(k++);
                group.add(p.getUniqueId());
                final int seatNo = s;
                final String avatar = av.get(s);
                memberRoom.put(p.getUniqueId(), r);
                later(delay, () -> seatIn(r, p, seatNo, avatar));
                delay += gap;
            }
        }
        // 혼자 남은 사람 (1명이면 split 이 빈 목록)
        for (int i = k; i < pool.size(); i++) {
            Player p = pool.get(i);
            players.remove(p.getUniqueId());
            p.sendMessage(Txt.color(Txt.notice("&7[포커] 테이블 인원이 맞지 않아 이번 판은 빠졌어요.")));
        }
        if (rooms.isEmpty()) { end(); return; }
        plugin.announce("&6[포커] &f" + (pool.size() - (pool.size() - k)) + "명 · 테이블 " + rooms.size() + "개 · 게임 시간 &e" + CasinoSupport.left(endsAt - System.currentTimeMillis()), false);
        plugin.discord().tables(id, label(), groups);   // 디스코드: 같은 테이블끼리 음성 채널로
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void seatIn(Room r, Player p, int s, String avatar) {
        if (memberRoom.get(p.getUniqueId()) != r) return;      // 그 사이 나감
        if (!p.isOnline() || r.closed) { dropMember(p.getUniqueId()); return; }
        long have = CasinoSupport.available(plugin, p);
        long buy = Math.min(buyInCfg(), have);
        if (buy < bbCfg() * 2) {
            plugin.ui().error(p, "&c[포커] 다이아가 부족해서 앉지 못했어요. &7(최소 " + num(bbCfg() * 2) + "개)");
            dropMember(p.getUniqueId());
            r.expected--;
            maybeBegin(r);
            return;
        }
        long got = CasinoSupport.take(plugin, p, buy);
        r.seat[s] = p.getUniqueId();
        r.names[s] = plugin.displayName(p);
        r.avatar[s] = avatar;
        r.stack[s] = got;
        r.buyIn[s] = got;
        r.inSession[s] = true;
        r.hadSession[s] = true;
        r.log = r.names[s] + " 님이 들어왔어요 · 입장 칩 " + num(got);
        sendRoom(r, p, true);
        broadcast(r);
        p.playSound(p.getLocation(), "minecraft:block.note_block.chime", 1f, 1.2f);
        maybeBegin(r);
    }

    private void maybeBegin(Room r) {
        if (r.session || r.closed) return;
        if (r.count() < r.expected) return;
        if (r.count() < 2) { closeRoom(r, "상대가 없어서 테이블이 닫혔어요"); return; }
        r.session = true;
        r.log = "모두 앉았어요! 곧 시작해요";
        broadcast(r);
        later(40, () -> startHand(r));
    }

    private void dropMember(UUID u) {
        memberRoom.remove(u);
        players.remove(u);
    }

    // ───────── 판 진행 (엘시 HoldemRooms 그대로) ─────────
    private void startHand(Room r) {
        if (r.closed) return;
        for (int i = 0; i < 3; i++) {
            if (r.seat[i] == null) { r.names[i] = ""; r.avatar[i] = ""; }
            if (r.inSession[i] && r.stack[i] <= 0) {
                r.inSession[i] = false;
                r.sessNet[i] = -r.buyIn[i];
                r.log = r.names[i] + " 님이 칩을 다 잃었어요";
            }
        }
        if (timeUp) { closeRoom(r, "게임 시간이 끝났어요"); return; }
        if (r.sessionCount() < 2) { closeRoom(r, r.log.endsWith("잃었어요") ? r.log + " · 테이블이 끝났어요" : "테이블이 끝났어요"); return; }
        long[] stacks = new long[3];
        for (int i = 0; i < 3; i++) stacks[i] = r.inSession[i] && r.seat[i] != null ? r.stack[i] : 0;
        long sb = Math.max(1, r.bb / 2);   // 빅 1 이면 스몰도 1
        r.table = new HoldemTable(ThreadLocalRandom.current(), stacks, r.dealer, sb, r.bb);
        r.dealer = (r.table.dealer + 1) % 3;
        r.phase = Phase.BET;
        r.hands++;
        r.lastStreet = 0;
        for (int i = 0; i < 3; i++) r.results[i] = null;
        r.log = r.hands + "번째 판 · 블라인드 " + num(sb) + "/" + num(r.bb);
        r.turnStarted = System.currentTimeMillis();
        if (r.table.over) finishHand(r);
        broadcast(r);
    }

    private void act(Room r, int s, Move m) {
        HoldemTable t = r.table;
        if (!r.playing() || t == null || t.over || t.turn != s) return;
        long paid = t.apply(s, m);
        r.log = actLog(r.names[s] + " 님이", t.status[s], paid, t.bet[s]);
        after(r);
    }

    private void after(Room r) {
        HoldemTable t = r.table;
        if (t.over) { finishHand(r); broadcast(r); return; }
        if (t.street != r.lastStreet) { r.lastStreet = t.street; r.log = HoldemTable.STREETS[t.street] + "!"; }
        r.turnStarted = System.currentTimeMillis();
        broadcast(r);
    }

    private void finishHand(Room r) {
        HoldemTable t = r.table;
        double rate = Math.max(0, Math.min(50, cfgDouble("rake-percent", 5))) / 100.0;
        for (int i = 0; i < 3; i++) {
            r.rake[i] = 0;
            if (!t.present[i]) continue;
            long profit = t.won[i] - t.total[i];
            if (profit > 0) r.rake[i] = (long) Math.floor(profit * rate);
            r.stack[i] = t.stack[i] - r.rake[i];
        }
        for (int i = 0; i < 3; i++) if (r.seat[i] != null) r.results[i] = HoldemView.result(t, i, r.hands, nameArr(r, i), r.rake);
        r.phase = Phase.BREAK;
        r.breakUntil = System.currentTimeMillis() + breakMs();
        int top = -1;
        for (int i = 0; i < 3; i++) if (t.present[i] && (top < 0 || t.won[i] > t.won[top])) top = i;
        r.log = top < 0 ? "판이 끝났어요" : r.names[top] + " 님이 " + num(t.won[top] - r.rake[top]) + " 가져갔어요";
    }

    private static String[] nameArr(Room r, int me) {
        String[] n = new String[3];
        for (int i = 0; i < 3; i++) n[i] = i == me ? "나" : r.names[i];
        return n;
    }

    static String actLog(String who, String status, long paid, long betTotal) {
        return switch (status) {
            case "폴드" -> who + " 폴드했어요";
            case "체크" -> who + " 체크했어요";
            case "콜" -> who + " " + num(paid) + " 콜했어요";
            case "베팅" -> who + " " + num(betTotal) + " 베팅했어요";
            case "레이즈" -> who + " 총 " + num(betTotal) + "으로 레이즈했어요";
            case "올인" -> who + " 올인! (" + num(betTotal) + ")";
            default -> who + " " + status;
        };
    }

    /** 칩 → 다이아 */
    private void cashOut(Room r, int s) {
        if (r.stack[s] > 0 && r.seat[s] != null) CasinoSupport.give(plugin, r.seat[s], r.stack[s]);
        r.sessNet[s] = r.stack[s] - r.buyIn[s];
        r.stack[s] = 0;
        r.inSession[s] = false;
    }

    /** 테이블 끝: 칩 정산, 결과 요약 보여 주고 잠시 뒤 창 닫기 */
    private void closeRoom(Room r, String reason) {
        if (r.closed) return;
        for (int i = 0; i < 3; i++) if (r.inSession[i] && r.seat[i] != null) cashOut(r, i);
        r.session = false;
        r.phase = Phase.WAIT;
        r.summaryReason = reason;
        r.summaryId++;
        r.log = reason;
        broadcast(r);
        r.closed = true;
        plugin.discord().tableEnd(id, r.no);
        List<UUID> members = new ArrayList<>();
        for (UUID u : r.seat) if (u != null) members.add(u);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : members) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) CasinoSupport.close(plugin, p, GAME);
                dropMember(u);
            }
            rooms.remove(r);
            if (running() && rooms.isEmpty()) end();
        }, 20L * Math.max(3, cfgInt("summary-seconds", 6)));
    }

    /** 나가기: 진행 중이면 폴드, 남은 칩은 다이아로 */
    private void leave(UUID u, boolean sendClose) {
        Room r = memberRoom.get(u);
        if (r == null) return;
        int s = r.seatOf(u);
        Player p = Bukkit.getPlayer(u);
        if (s >= 0) {
            String name = r.names[s];
            if (r.playing() && r.table != null && r.table.present[s] && !r.table.folded[s] && !r.table.over) {
                r.table.forceFold(s);
                r.stack[s] = r.table.stack[s];
                r.log = name + " 님이 나가서 폴드 처리됐어요";
            } else r.log = name + " 님이 나갔어요";
            if (r.inSession[s]) cashOut(r, s);
            r.seat[s] = null;
            if (p != null) p.sendMessage(Txt.color(Txt.notice("&6[포커] &f칩을 정산하고 나왔어요. &7(" + (r.sessNet[s] >= 0 ? "+" : "") + num(r.sessNet[s]) + ")")));
        }
        else { r.expected--; }                                   // 앉기 전에 나감
        dropMember(u);
        if (sendClose && p != null) CasinoSupport.close(plugin, p, GAME);
        if (r.closed) return;
        if (r.count() == 0) { r.closed = true; rooms.remove(r); if (rooms.isEmpty()) end(); return; }
        if (r.playing() && r.table != null) after(r);
        else if (r.session && r.phase == Phase.BREAK && r.sessionCount() < 2) closeRoom(r, "상대가 나가서 테이블이 끝났어요");
        else if (!r.session && r.count() >= 2 && r.count() >= r.expected) maybeBegin(r);
        else broadcast(r);
    }

    private void tick() {
        if (!running()) { if (task != null) task.cancel(); return; }
        tickN++;
        long now = System.currentTimeMillis();
        if (!timeUp && now >= endsAt) {
            timeUp = true;
            for (Room r : rooms) { r.log = "게임 시간이 끝났어요 · 이번 판까지만 해요"; broadcast(r); if (r.phase != Phase.BET && r.session) closeRoom(r, "게임 시간이 끝났어요"); }
        }
        for (Room r : List.copyOf(rooms)) {
            if (r.closed) continue;
            if (r.phase == Phase.BREAK && now >= r.breakUntil) { startHand(r); continue; }
            if (!r.playing() || r.table == null || r.table.over) continue;
            int s = r.table.turn;
            if (s >= 0 && now - r.turnStarted > turnLimit()) {
                boolean free = r.table.toCall(s) == 0;
                act(r, s, free ? Move.call() : Move.fold());
                r.log = r.names[s] + " 님이 시간이 지나서 자동으로 " + (free ? "체크" : "폴드") + "했어요";
                broadcast(r);
            }
        }
        // 남은 시간 표시 갱신 (30초마다)
        if (tickN % 300 == 0) for (Room r : rooms) if (!r.closed) broadcast(r);
    }

    // ───────── 클라이언트 요청 ─────────
    /** UI 액션 "casino" (game=holdem) */
    public void handle(Player p, String json) {
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (Exception e) { return; }
        String action = o.has("action") ? o.get("action").getAsString() : "";
        Room r = memberRoom.get(p.getUniqueId());
        if (r == null) { CasinoSupport.close(plugin, p, GAME); return; }
        int s = r.seatOf(p.getUniqueId());
        switch (action) {
            case "hd_fold" -> { if (s >= 0) act(r, s, Move.fold()); }
            case "hd_call" -> { if (s >= 0) act(r, s, Move.call()); }
            case "hd_raise" -> { if (s >= 0) act(r, s, Move.raiseTo(lng(o, "to"))); }
            case "hdp_leave", "hdp_stop", "hd_stop", "hd_close" -> leave(p.getUniqueId(), !action.equals("hd_close"));
            default -> sendRoom(r, p, false);
        }
    }

    /** 창이 닫혀 있으면 다시 열기 (/포커) */
    public boolean reopen(Player p) {
        Room r = memberRoom.get(p.getUniqueId());
        if (r == null || r.seatOf(p.getUniqueId()) < 0) return false;
        sendRoom(r, p, true);
        return true;
    }

    @Override
    public void quit(Player p) { leave(p.getUniqueId(), false); }

    @Override
    protected void cleanup() {
        if (task != null) { task.cancel(); task = null; }
        plugin.discord().end(id);
        // 강제 종료: 진행 중인 판에 낸 칩까지 모두 돌려줌
        for (Room r : List.copyOf(rooms)) {
            for (int i = 0; i < 3; i++) {
                UUID u = r.seat[i];
                if (u == null) continue;
                if (r.inSession[i]) {
                    if (r.playing() && r.table != null && r.table.present[i] && !r.table.over) r.stack[i] = r.table.stack[i] + r.table.total[i];
                    cashOut(r, i);
                }
                Player p = Bukkit.getPlayer(u);
                if (p != null) CasinoSupport.close(plugin, p, GAME);
            }
        }
        rooms.clear();
        memberRoom.clear();
    }

    // ───────── 전송 (엘시 형식) ─────────
    private void broadcast(Room r) {
        for (UUID u : r.seat) {
            if (u == null) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) sendRoom(r, p, false);
        }
    }

    private void sendRoom(Room r, Player p, boolean open) {
        int me = r.seatOf(p.getUniqueId());
        if (me < 0) return;
        long now = System.currentTimeMillis();
        HoldemTable t = r.session && (r.phase == Phase.BET || r.phase == Phase.BREAK) ? r.table : null;
        JsonObject o = new JsonObject();
        o.addProperty("type", "state");
        o.addProperty("mode", "pvp");
        o.addProperty("open", open);
        boolean seating = !r.session && r.summaryId == 0;      // 아직 자리 채우는 중 → "곧 시작" 대기로 보여 줌
        o.addProperty("phase", seating ? "break" : switch (r.phase) { case WAIT -> "wait"; case BET -> "bet"; case BREAK -> "break"; });
        o.addProperty("sb", Math.max(1, r.bb / 2));
        o.addProperty("bb", r.bb);
        o.addProperty("buyIn", r.buyIn[me]);
        o.addProperty("balance", CasinoSupport.available(plugin, p));
        o.addProperty("hands", r.hands);
        o.addProperty("log", r.log);
        if (seating) {
            o.addProperty("timeLeft", (Math.max(0, r.expected - r.count()) * Math.max(4, cfgInt("seat-interval-ticks", 12)) + 40) * 50L);
            o.addProperty("turnLimit", 5000);
        } else if (r.phase == Phase.BREAK) {
            o.addProperty("timeLeft", Math.max(0, r.breakUntil - now));
            o.addProperty("turnLimit", breakMs());
        }
        JsonObject room = new JsonObject();
        room.addProperty("id", r.no);
        room.addProperty("title", r.no + "번 테이블 · " + (timeUp ? "마지막 판" : "남은 시간 " + CasinoSupport.left(endsAt - now)));
        room.addProperty("isHost", false);
        room.addProperty("count", r.count());
        room.addProperty("allReady", false);
        room.addProperty("rake", cfgDouble("rake-percent", 5));
        room.addProperty("session", r.session || seating);
        room.addProperty("inSession", r.inSession[me]);
        o.add("room", room);

        HoldemView.SeatInfo[] seats = new HoldemView.SeatInfo[3];
        for (int i = 0; i < 3; i++) {
            HoldemView.SeatInfo si = new HoldemView.SeatInfo();
            boolean inHand = t != null && t.present[i];
            si.empty = r.seat[i] == null && !inHand;
            si.id = r.seat[i] == null ? "" : r.seat[i].toString();
            si.avatar = si.empty ? "" : r.avatar[i];
            si.name = si.empty ? "" : i == me ? "나 · " + r.names[i] : r.names[i];
            si.stack = r.stack[i];
            si.host = false;
            si.ready = true;
            si.inSession = r.inSession[i];
            si.out = r.session && !si.empty && !r.inSession[i];
            seats[i] = si;
        }
        HoldemView.fill(o, t, me, seats, r.playing(), r.phase == Phase.BREAK, now, r.turnStarted, turnLimit());
        if (r.phase == Phase.BREAK && r.results[me] != null) o.add("result", r.results[me]);
        if (r.summaryId > 0 && !r.session && r.hadSession[me]) {
            JsonObject sm = new JsonObject();
            sm.addProperty("id", r.summaryId);
            sm.addProperty("reason", r.summaryReason);
            sm.addProperty("net", r.sessNet[me]);
            sm.addProperty("hands", r.hands);
            sm.addProperty("cashOut", r.sessNet[me] + r.buyIn[me]);
            o.add("summary", sm);
        }
        CasinoSupport.send(plugin, p, GAME, o.toString());
    }

    private static long lng(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (Exception e) { return 0; }
    }
}
