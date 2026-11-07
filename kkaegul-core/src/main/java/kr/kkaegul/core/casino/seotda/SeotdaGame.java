package kr.kkaegul.core.casino.seotda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.casino.CasinoSupport;
import kr.kkaegul.core.casino.seotda.Seotda.Hand;
import kr.kkaegul.core.casino.seotda.Seotda.Outcome;
import kr.kkaegul.core.casino.seotda.SeotdaTable.Move;
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
 * 섯다 미니게임 — 엘시 경제서버 섯다 플레이어 대전(주작 없음)을 그대로 옮김.
 * 방·준비·AI 없음: 뽑힌 유저를 3인(또는 2인) 테이블로 나누고 한 명씩 앉혀 바로 시작.
 * 입장 칩(판돈 × buyin-ante, 다이아)으로 앉고, 칩이 판돈보다 적어지면 빠짐. 정해진 시간이 지나면
 * 진행 중인 턴까지만 하고 남은 칩을 다이아로 돌려준다. 이긴 사람 판돈에서 수수료(rake-percent)를 뗀다.
 */
public final class SeotdaGame extends Minigame {
    public static final String GAME = "seotda";

    private enum Phase { WAIT, BET, BREAK }

    private static final class Room {
        final int no;
        long ante;
        final UUID[] seat = new UUID[3];
        final String[] names = {"", "", ""};
        final String[] avatar = {"", "", ""};
        Phase phase = Phase.WAIT;
        PvpTable table;
        final long[] paid = new long[3];
        long turnStarted, rematchUntil, breakUntil;
        boolean session, closed;
        final boolean[] inSession = new boolean[3];
        final boolean[] hadSession = new boolean[3];
        final long[] stack = new long[3];
        final long[] buyIn = new long[3];
        final long[] sessNet = new long[3];
        int hands, summaryId, firstSeat, expected;
        String summaryReason = "", log = "";
        int resultId, winner = -1;
        Hand winHand;
        long resultPot, resultPayout;
        boolean wonByFold;
        String rematchReason = "";

        Room(int no) { this.no = no; }

        int count() { int n = 0; for (UUID u : seat) if (u != null) n++; return n; }
        int seatOf(UUID u) { for (int i = 0; i < 3; i++) if (u.equals(seat[i])) return i; return -1; }
        boolean playing() { return phase == Phase.BET; }
        int sessionCount() { int n = 0; for (int i = 0; i < 3; i++) if (inSession[i] && seat[i] != null) n++; return n; }
    }

    private final List<Room> rooms = new ArrayList<>();
    private final Map<UUID, Room> memberRoom = new HashMap<>();
    private long endsAt;
    private boolean timeUp;
    private BukkitTask task;
    private int tickN;

    public SeotdaGame(KkaegulCore plugin) { super(plugin, "seotda"); }

    @Override public String label() { return "섯다"; }
    @Override public boolean timed() { return true; }
    @Override public int minPlayers() { return Math.max(2, super.minPlayers()); }
    @Override public String readiness() { return null; }

    private long anteCfg() { return Math.max(1, cfgInt("ante", 1)); }
    /** 입장 칩: buyin-all 이면 가진 다이아 전부 (제한 없음) */
    private long buyInCfg() { return cfgBool("buyin-all", true) ? Long.MAX_VALUE : anteCfg() * Math.max(5, cfgInt("buyin-ante", 40)); }
    /** 초대장 받는 최소 보유 다이아 */
    private long minBuyIn() { return Math.max(anteCfg() * 2, plugin.getConfig().getLong("minigame.casino.min-held", 50)); }
    private long turnLimit() { return cfgInt("turn-seconds", 20) * 1000L; }
    private long breakMs() { return cfgInt("break-seconds", 4) * 1000L; }

    @Override public boolean eligible(Player p) { return CasinoSupport.available(plugin, p) >= minBuyIn(); }

    // ───────── 시작 ─────────
    @Override
    protected void start(List<Player> list) {
        back.clear();
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
            r.ante = anteCfg();
            r.expected = size;
            r.firstSeat = ThreadLocalRandom.current().nextInt(3);
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
        for (int i = k; i < pool.size(); i++) {
            players.remove(pool.get(i).getUniqueId());
            pool.get(i).sendMessage(Txt.color(Txt.notice("&7[섯다] 테이블 인원이 맞지 않아 이번 판은 빠졌어요.")));
        }
        if (rooms.isEmpty()) { end(); return; }
        plugin.announce("&6[섯다] &f" + k + "명 · 테이블 " + rooms.size() + "개 · 게임 시간 &e" + CasinoSupport.left(endsAt - System.currentTimeMillis()), false);
        plugin.discord().tables(id, label(), groups);   // 디스코드: 같은 테이블끼리 음성 채널로
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void seatIn(Room r, Player p, int s, String avatar) {
        if (memberRoom.get(p.getUniqueId()) != r) return;
        if (!p.isOnline() || r.closed) { dropMember(p.getUniqueId()); return; }
        long have = CasinoSupport.available(plugin, p);
        long buy = Math.min(buyInCfg(), have);
        if (buy < anteCfg() * 2) {
            plugin.ui().error(p, "&c[섯다] 다이아가 부족해서 앉지 못했어요. &7(최소 " + num(anteCfg() * 2) + "개)");
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
        if (r.session || r.closed || r.count() < r.expected) return;
        if (r.count() < 2) { closeRoom(r, "상대가 없어서 테이블이 닫혔어요"); return; }
        r.session = true;
        r.log = "모두 앉았어요! 곧 시작해요";
        broadcast(r);
        later(40, () -> startHand(r));
    }

    private void dropMember(UUID u) { memberRoom.remove(u); players.remove(u); }

    // ───────── 턴 진행 (엘시 SeotdaRooms 그대로, 돈 대신 테이블 칩) ─────────
    private void startHand(Room r) {
        if (r.closed) return;
        for (int i = 0; i < 3; i++) if (r.seat[i] == null) { r.names[i] = ""; r.avatar[i] = ""; }
        for (int i = 0; i < 3; i++) {
            if (!r.inSession[i]) continue;
            if (r.seat[i] == null || r.stack[i] < r.ante) {
                cashOut(r, i);
                r.log = r.names[i] + " 님이 칩이 모자라서 빠졌어요";
            }
        }
        if (timeUp) { closeRoom(r, "게임 시간이 끝났어요"); return; }
        if (r.sessionCount() < 2) { closeRoom(r, r.log.endsWith("빠졌어요") ? r.log + " · 테이블이 끝났어요" : "테이블이 끝났어요"); return; }
        boolean[] in = new boolean[3];
        long[] banks = new long[3];
        for (int i = 0; i < 3; i++) {
            if (!r.inSession[i]) continue;
            r.stack[i] -= r.ante;
            in[i] = true;
            banks[i] = r.stack[i];
        }
        r.firstSeat = nextSeat(r.firstSeat + 1, in);
        r.table = new PvpTable(ThreadLocalRandom.current(), r.ante, in, banks, r.firstSeat, cfgInt("max-raise-mult", 4), cfgInt("max-raises", 2));
        for (int i = 0; i < 3; i++) r.paid[i] = in[i] ? r.ante : 0;
        r.phase = Phase.BET;
        r.rematchUntil = 0;
        r.winner = -1;
        r.hands++;
        r.log = r.hands == 1 ? "판돈 " + num(r.ante) + " 다이아씩 걸고 시작!" : r.hands + "번째 턴 · 판돈 " + num(r.ante) + " 다이아씩";
        r.turnStarted = System.currentTimeMillis();
        broadcast(r);
    }

    private static int nextSeat(int from, boolean[] in) {
        for (int k = 0; k < 3; k++) { int i = (from + k) % 3; if (in[i]) return i; }
        return 0;
    }

    private void act(Room r, int s, Move m) {
        PvpTable t = r.table;
        if (!r.playing() || t == null || t.turn != s || r.rematchUntil > 0) return;
        if (m.act() == SeotdaTable.Act.RAISE && !t.canRaise(s)) return;
        long need = t.cost(s, m);
        boolean broke = false;
        if (need > 0) {
            if (r.stack[s] < need) { broke = true; m = Move.die(); }
            else r.stack[s] -= need;
        }
        long paid = t.apply(s, m);
        if (!broke && need > paid) r.stack[s] += need - paid; // 실제로 낸 만큼만
        r.paid[s] += paid;
        String n = r.names[s];
        r.log = broke ? n + " 님이 칩이 부족해서 다이했어요" : switch (m.act()) {
            case DIE -> n + " 님이 다이했어요";
            case CALL -> paid > 0 ? n + " 님이 " + num(paid) + " 콜했어요" : n + " 님이 체크했어요";
            case RAISE -> n + " 님이 " + num(paid) + " 걸었어요 (레이즈)";
        };
        afterAction(r);
    }

    private void afterAction(Room r) {
        PvpTable t = r.table;
        if (!t.bettingOver()) { r.turnStarted = System.currentTimeMillis(); broadcast(r); return; }
        if (t.activeCount() == 1) { finish(r, t.lastStanding(), true); return; }
        Outcome o = t.showdown();
        if (o.rematch()) {
            t.rematch();
            r.rematchReason = o.reason();
            r.log = "재경기! (" + o.reason() + ") 판돈은 그대로 다시 패를 받아요";
            r.rematchUntil = System.currentTimeMillis() + 2500;
            broadcast(r);
            return;
        }
        finish(r, o.winner(), false);
    }

    private void finish(Room r, int w, boolean byFold) {
        PvpTable t = r.table;
        long pot = t.pot();
        double rake = Math.max(0, Math.min(50, cfgDouble("rake-percent", 5))) / 100.0;
        long payout = pot - (long) Math.floor(pot * rake);
        if (w >= 0 && r.seat[w] != null) r.stack[w] += payout;
        r.winner = w;
        r.winHand = w >= 0 ? t.hands[w] : null;
        r.resultPot = pot;
        r.resultPayout = payout;
        r.wonByFold = byFold;
        r.resultId++;
        r.phase = Phase.BREAK;
        r.breakUntil = System.currentTimeMillis() + breakMs();
        r.log = w >= 0 ? r.names[w] + " 님이 " + num(payout) + " 다이아를 가져갔어요" : "턴이 끝났어요";
        broadcast(r);
    }

    private void cashOut(Room r, int s) {
        if (r.stack[s] > 0 && r.seat[s] != null) CasinoSupport.give(plugin, r.seat[s], r.stack[s]);
        r.sessNet[s] = r.stack[s] - r.buyIn[s];
        r.stack[s] = 0;
        r.inSession[s] = false;
    }

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

    private void leave(UUID u, boolean sendClose) {
        Room r = memberRoom.get(u);
        if (r == null) return;
        int s = r.seatOf(u);
        Player p = Bukkit.getPlayer(u);
        if (s >= 0) {
            String name = r.names[s];
            if (r.playing() && r.table != null && r.table.present[s] && !r.table.folded[s]) {
                r.table.forceFold(s);
                r.log = name + " 님이 나가서 다이 처리됐어요";
            } else r.log = name + " 님이 나갔어요";
            if (r.inSession[s]) cashOut(r, s);
            r.seat[s] = null;
            if (p != null) p.sendMessage(Txt.color(Txt.notice("&6[섯다] &f칩을 정산하고 나왔어요. &7(" + (r.sessNet[s] >= 0 ? "+" : "") + num(r.sessNet[s]) + ")")));
        } else r.expected--;
        dropMember(u);
        if (sendClose && p != null) CasinoSupport.close(plugin, p, GAME);
        if (r.closed) return;
        if (r.count() == 0) { r.closed = true; rooms.remove(r); if (rooms.isEmpty()) end(); return; }
        if (r.playing() && r.table != null && r.rematchUntil == 0) afterAction(r);
        else if (r.session && r.phase == Phase.BREAK && r.sessionCount() < 2) closeRoom(r, "상대가 나가서 테이블이 끝났어요");
        else if (!r.session) maybeBegin(r);
        else broadcast(r);
    }

    private void tick() {
        if (!running()) { if (task != null) task.cancel(); return; }
        tickN++;
        long now = System.currentTimeMillis();
        if (!timeUp && now >= endsAt) {
            timeUp = true;
            for (Room r : List.copyOf(rooms)) { r.log = "게임 시간이 끝났어요 · 이번 턴까지만 해요"; broadcast(r); if (r.phase != Phase.BET && r.session) closeRoom(r, "게임 시간이 끝났어요"); }
        }
        for (Room r : List.copyOf(rooms)) {
            if (r.closed) continue;
            if (r.phase == Phase.BREAK && now >= r.breakUntil) { startHand(r); continue; }
            if (!r.playing() || r.table == null) continue;
            if (r.rematchUntil > 0) {
                if (now < r.rematchUntil) continue;
                r.rematchUntil = 0;
                r.turnStarted = now;
                broadcast(r);
                continue;
            }
            int s = r.table.turn;
            if (s >= 0 && now - r.turnStarted > turnLimit()) {
                act(r, s, Move.die());
                r.log = r.names[s] + " 님이 시간이 지나서 자동으로 다이했어요";
                broadcast(r);
            }
        }
        if (tickN % 300 == 0) for (Room r : rooms) if (!r.closed) broadcast(r);
    }

    // ───────── 요청 ─────────
    public void handle(Player p, String json) {
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); } catch (Exception e) { return; }
        String action = o.has("action") ? o.get("action").getAsString() : "";
        Room r = memberRoom.get(p.getUniqueId());
        if (r == null) { CasinoSupport.close(plugin, p, GAME); return; }
        int s = r.seatOf(p.getUniqueId());
        switch (action) {
            case "seotda_die" -> { if (s >= 0) act(r, s, Move.die()); }
            case "seotda_call" -> { if (s >= 0) act(r, s, Move.call()); }
            case "seotda_raise" -> { if (s >= 0) act(r, s, Move.raise(lng(o, "amount"))); }
            case "sdp_leave", "sdp_stop", "seotda_stop", "seotda_close" -> leave(p.getUniqueId(), !action.equals("seotda_close"));
            default -> sendRoom(r, p, false);
        }
    }

    public boolean reopen(Player p) {
        Room r = memberRoom.get(p.getUniqueId());
        if (r == null || r.seatOf(p.getUniqueId()) < 0) return false;
        sendRoom(r, p, true);
        return true;
    }

    @Override public void quit(Player p) { leave(p.getUniqueId(), false); }

    @Override
    protected void cleanup() {
        if (task != null) { task.cancel(); task = null; }
        plugin.discord().end(id);
        for (Room r : List.copyOf(rooms)) {
            for (int i = 0; i < 3; i++) {
                UUID u = r.seat[i];
                if (u == null) continue;
                if (r.inSession[i]) {
                    if (r.playing()) r.stack[i] += r.paid[i];   // 진행 중인 턴에 낸 칩 환불
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
        PvpTable t = r.session && (r.phase == Phase.BET || r.phase == Phase.BREAK) ? r.table : null;
        long now = System.currentTimeMillis();
        long turnLimit = turnLimit(), breakMs = breakMs();
        boolean seating = !r.session && r.summaryId == 0;
        JsonObject o = new JsonObject();
        o.addProperty("type", "state");
        o.addProperty("mode", "pvp");
        o.addProperty("open", open);
        o.addProperty("ante", r.ante);
        o.addProperty("balance", r.stack[me]);
        String phase = seating ? "break" : switch (r.phase) {
            case WAIT -> "wait";
            case BREAK -> "break";
            case BET -> r.rematchUntil > 0 ? "rematch" : "bet";
        };
        o.addProperty("phase", phase);
        boolean betting = r.playing() && r.rematchUntil == 0 && t != null;
        int turn = betting && t.turn >= 0 ? (t.turn - me + 3) % 3 : -1;
        o.addProperty("turn", turn);
        long seatWait = (Math.max(0, r.expected - r.count()) * Math.max(4, cfgInt("seat-interval-ticks", 12)) + 40) * 50L;
        o.addProperty("timeLeft", seating ? seatWait : betting && t.turn >= 0 ? Math.max(0, turnLimit - (now - r.turnStarted))
                : r.phase == Phase.BREAK ? Math.max(0, r.breakUntil - now) : 0);
        o.addProperty("turnLimit", seating ? 5000 : r.phase == Phase.BREAK ? breakMs : turnLimit);
        o.addProperty("pot", t == null || r.phase == Phase.BREAK ? 0 : t.pot());
        boolean meIn = t != null && t.present[me];
        o.addProperty("toCall", t == null || !meIn ? 0 : t.toCall(me));
        o.addProperty("canRaise", betting && meIn && t.canRaise(me));
        o.addProperty("minRaise", t == null ? r.ante : t.minRaise());
        o.addProperty("maxRaise", t == null || !meIn ? 0 : Math.max(0, t.maxRaiseFor(me)));
        o.addProperty("raisesLeft", t == null ? 0 : t.raisesLeft);
        o.addProperty("paid", t == null ? 0 : r.paid[me]);
        o.addProperty("log", r.log);
        o.addProperty("rematches", t == null ? 0 : t.rematches);
        o.addProperty("hands", r.hands);

        JsonObject room = new JsonObject();
        room.addProperty("id", r.no);
        room.addProperty("title", r.no + "번 테이블 · " + (timeUp ? "마지막 턴" : "남은 시간 " + CasinoSupport.left(endsAt - now)));
        room.addProperty("isHost", false);
        room.addProperty("count", r.count());
        room.addProperty("allReady", false);
        room.addProperty("rake", cfgDouble("rake-percent", 5));
        room.addProperty("session", r.session || seating);
        room.addProperty("inSession", r.inSession[me]);
        o.add("room", room);

        boolean reveal = r.phase == Phase.BREAK && !r.wonByFold;
        JsonArray seats = new JsonArray();
        for (int v = 0; v < 3; v++) {
            int i = (me + v) % 3;
            JsonObject j = new JsonObject();
            boolean inHand = t != null && t.present[i];
            boolean empty = r.seat[i] == null && !inHand;
            j.addProperty("empty", empty);
            j.addProperty("id", r.seat[i] == null ? "" : r.seat[i].toString());
            j.addProperty("avatar", empty ? "" : r.avatar[i]);
            j.addProperty("name", empty ? "" : v == 0 ? "나 · " + r.names[i] : r.names[i]);
            j.addProperty("chips", empty ? 0 : r.stack[i]);
            j.addProperty("host", false);
            j.addProperty("ready", true);
            j.addProperty("inSession", r.inSession[i]);
            j.addProperty("out", r.session && !empty && !r.inSession[i] && !inHand);
            j.addProperty("status", inHand ? t.status[i] : r.session && !empty && !r.inSession[i] ? "구경" : "");
            j.addProperty("put", inHand ? t.put[i] : 0);
            j.addProperty("folded", inHand && t.folded[i]);
            JsonArray cards = new JsonArray();
            String rank = "";
            if (inHand && t.hands[i] != null) {
                boolean show = v == 0 || (reveal && !t.folded[i]);
                cards.add(show ? t.hands[i].a().id() : "back");
                cards.add(show ? t.hands[i].b().id() : "back");
                if (show) rank = t.hands[i].displayName();
            } else { cards.add(""); cards.add(""); }
            j.add("cards", cards);
            j.addProperty("rank", rank);
            seats.add(j);
        }
        o.add("seats", seats);

        if (r.phase == Phase.BET && r.rematchUntil > 0) {
            JsonObject res = new JsonObject();
            res.addProperty("kind", "rematch");
            res.addProperty("reason", r.rematchReason);
            o.add("result", res);
        } else if (r.phase == Phase.BREAK && r.winner >= 0) {
            JsonObject res = new JsonObject();
            res.addProperty("id", r.resultId);
            boolean won = r.winner == me;
            res.addProperty("kind", won ? "win" : "lose");
            res.addProperty("winner", (r.winner - me + 3) % 3);
            res.addProperty("winnerName", won ? "나" : r.names[r.winner].isEmpty() ? "상대" : r.names[r.winner]);
            res.addProperty("rank", r.wonByFold ? "모두 다이" : r.winHand == null ? "" : r.winHand.displayName());
            JsonArray cards = new JsonArray();
            if (r.winHand != null && (!r.wonByFold || won)) { cards.add(r.winHand.a().id()); cards.add(r.winHand.b().id()); }
            res.add("cards", cards);
            res.addProperty("pot", r.resultPot);
            res.addProperty("payout", won ? r.resultPayout : 0);
            res.addProperty("rakeAmount", r.resultPot - r.resultPayout);
            res.addProperty("net", (won ? r.resultPayout : 0) - r.paid[me]);
            res.addProperty("lost", r.paid[me]);
            res.addProperty("folded", r.wonByFold);
            o.add("result", res);
        }
        if (r.summaryId > 0 && !r.session && r.hadSession[me]) {
            JsonObject sm = new JsonObject();
            sm.addProperty("id", r.summaryId);
            sm.addProperty("reason", r.summaryReason);
            sm.addProperty("net", r.sessNet[me]);
            sm.addProperty("hands", r.hands);
            sm.addProperty("balance", CasinoSupport.available(plugin, p));
            o.add("summary", sm);
        }
        CasinoSupport.send(plugin, p, GAME, o.toString());
    }

    private static long lng(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (Exception e) { return 0; }
    }
}
