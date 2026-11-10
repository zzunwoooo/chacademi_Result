package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.casino.CasinoSupport;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 오목 (1:1). 30분마다 접속 인원 25%를 판 수로(내림) 뽑아 2명씩 짝지음 — 10명 접속이면 2판 4명.
 * 로비로 데려가 10초 뒤 시작, 화면은 모드의 오목판 창(omok). 흑 랜덤 선공, 다섯 이상 이으면 승리,
 * 흑만 쌍삼 금지, 차례 20초 (넘기면 차례가 넘어가고 두 번 넘기면 패배), 나가면 패배.
 * 진 사람 보유 다이아에서 stake(50)개를 떼서 이긴 사람에게. 보유 다이아가 stake 이상인 사람만 뽑힘.
 */
public final class OmokGame extends Minigame {
    static final int N = 15;

    /** 한 판 (두 사람) */
    private final class Match {
        final UUID black, white;
        final int[] board = new int[N * N];   // 0 빈칸 1 흑 2 백
        int turn = 1, moves = 0, last = -1, token = 0;
        final int[] strikes = new int[3];
        boolean done = false;
        Match(UUID black, UUID white) { this.black = black; this.white = white; }
        UUID who(int color) { return color == 1 ? black : white; }
        int color(UUID u) { return u.equals(black) ? 1 : u.equals(white) ? 2 : 0; }
    }

    private final List<Match> matches = new ArrayList<>();

    public OmokGame(KkaegulCore plugin) { super(plugin, "omok"); }

    @Override public String label() { return "깨굴 오목"; }

    long stake() { return Math.max(0, cfgInt("stake", 50)); }

    @Override public int minPlayers() { return 2; }

    @Override public boolean eligible(Player p) { return CasinoSupport.available(plugin, p) >= stake(); }

    @Override public String readiness() { return place() == null ? "위치 omok 또는 lobby 필요" : null; }

    /** 보낼 인원: 접속 인원의 percent% 를 판 수로 내림 × 2 (최소 1판) */
    public int wanted(int online) {
        int games = (int) Math.floor(online * cfgDouble("invite-percent", 25) / 100.0);
        return Math.max(1, games) * 2;
    }

    private Location place() {
        Location l = plugin.places().get("omok");
        return l != null ? l : plugin.places().get("lobby");
    }

    // ───────── 시작 ─────────
    @Override
    protected void start(List<Player> list) {
        matches.clear();
        List<Player> pool = new ArrayList<>(list);
        Collections.shuffle(pool);
        Location at = place();
        for (int i = 0; i + 1 < pool.size(); i += 2) {
            Player a = pool.get(i), b = pool.get(i + 1);
            boolean aBlack = Rng.chance(50);
            matches.add(new Match((aBlack ? a : b).getUniqueId(), (aBlack ? b : a).getUniqueId()));
        }
        if (pool.size() % 2 == 1) drop(pool.get(pool.size() - 1), "&7짝이 맞지 않아 이번 오목에서 빠졌어요.");
        if (matches.isEmpty()) { end(); return; }
        for (Match m : matches) for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (at != null) p.teleport(at);
            state.put(u, "wait");
            p.sendMessage(c(Txt.notice("&f{e:system} &f오목 1:1 · 이기면 상대 다이아 &b" + stake() + "개&f를 가져가요. 흑만 쌍삼 금지.")));
        }
        waitPhase(cfgInt("wait-seconds", 10), "오목 시작", () -> {
            for (Match m : matches) { send(m, true); turn(m); }
        });
    }

    /** 혼자 남은 사람 등 이번 판에서 빼기 */
    private void drop(Player p, String msg) {
        players.remove(p.getUniqueId());
        Location l = back.remove(p.getUniqueId());
        if (l != null) p.teleport(l);
        p.sendMessage(c(Txt.notice(msg)));
    }

    private Match of(UUID u) {
        for (Match m : matches) if (!m.done && m.color(u) != 0) return m;
        return null;
    }

    // ───────── 진행 ─────────
    private int turnSeconds() { return Math.max(5, cfgInt("turn-seconds", 20)); }

    private void turn(Match m) {
        if (m.done) return;
        int tok = ++m.token;
        send(m, false);
        Player p = Bukkit.getPlayer(m.who(m.turn));
        if (p != null) p.playSound(p.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
        later(turnSeconds() * 20L, () -> { if (!m.done && m.token == tok) timeout(m); });
    }

    private void timeout(Match m) {
        int c = m.turn;
        m.strikes[c]++;
        UUID u = m.who(c);
        if (m.strikes[c] >= Math.max(1, cfgInt("max-timeouts", 2))) { finish(m, 3 - c, "시간 초과 " + m.strikes[c] + "번"); return; }
        Player p = Bukkit.getPlayer(u);
        if (p != null) plugin.ui().error(p, "&c시간이 지나 차례가 넘어갔어요. 한 번 더 넘기면 패배예요.");
        m.turn = 3 - c;
        turn(m);
    }

    /** 모드 창에서 돌 놓기 (omok.place x y) */
    public void place(Player p, int x, int y) {
        Match m = of(p.getUniqueId());
        if (m == null || m.done) return;
        int c = m.color(p.getUniqueId());
        if (c != m.turn) { plugin.ui().error(p, "&c상대 차례예요."); return; }
        if (x < 0 || y < 0 || x >= N || y >= N || m.board[y * N + x] != 0) return;
        if (c == 1 && forbidden(m.board, x, y)) { plugin.ui().error(p, "&c쌍삼 금지 자리예요."); return; }
        m.board[y * N + x] = c;
        m.last = y * N + x;
        m.moves++;
        m.token++;
        for (UUID u : List.of(m.black, m.white)) { Player o = Bukkit.getPlayer(u); if (o != null) o.playSound(o.getLocation(), "minecraft:block.wood.place", 1f, 1.3f); }
        if (five(m.board, x, y, c)) { finish(m, c, "오목 완성"); return; }
        if (m.moves >= N * N) { finish(m, 0, "판이 가득 찼어요"); return; }
        m.turn = 3 - c;
        turn(m);
    }

    /** winner 0 = 무승부 */
    private void finish(Match m, int winner, String why) {
        if (m.done) return;
        m.done = true;
        m.token++;
        long got = 0;
        if (winner != 0) {
            UUID w = m.who(winner), l = m.who(3 - winner);
            Player lp = Bukkit.getPlayer(l);
            if (lp != null) got = CasinoSupport.take(plugin, lp, stake());
            else got = takeOffline(l, stake());
            CasinoSupport.give(plugin, w, got);
            state.put(w, "success");
            state.put(l, "out");
        }
        send(m, false);
        for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            boolean win = winner != 0 && m.who(winner).equals(u);
            String sub = winner == 0 ? why + " · 무승부" : why + " · 다이아 " + (win ? "+" : "-") + got;
            plugin.ui().update(p, "omok", Json.obj().put("result", winner == 0 ? "draw" : win ? "win" : "lose").put("resultSub", sub));
            if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, winner == 0 ? "&7무승부" : win ? "&a승리!" : "&c패배…", "&f" + sub);
        }
        if (winner != 0) {
            Player w = Bukkit.getPlayer(m.who(winner)), l = Bukkit.getPlayer(m.who(3 - winner));
            plugin.announce("&f[깨굴 오목] " + (w != null ? plugin.displayName(w) : "?") + " 승리! " + (l != null ? plugin.displayName(l) : "?") + "에게서 다이아 " + got + "개", false);
        }
        later(cfgInt("result-seconds", 5) * 20L, () -> release(m));
    }

    /** 접속이 끊긴 사람: 보유 다이아에서 바로 뗌 */
    private long takeOffline(UUID u, long n) {
        var d = plugin.data().get(u);
        if (d == null) return 0;
        long got = Math.min(n, Math.max(0, d.held));
        d.held -= got;
        d.markDirty();
        return got;
    }

    /** 끝난 판의 두 사람 돌려보내기. 모든 판이 끝나면 게임 종료 */
    private void release(Match m) {
        for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            players.remove(u);
            Location l = back.remove(u);
            if (p != null) { plugin.ui().close(p, "omok"); if (l != null) p.teleport(l); }
        }
        boolean all = true;
        for (Match o : matches) if (!o.done) all = false;
        if (all || players.isEmpty()) end();
    }

    // ───────── 화면 ─────────
    private void send(Match m, boolean open) {
        StringBuilder b = new StringBuilder(N * N);
        for (int v : m.board) b.append(v);
        List<Object> forb = new ArrayList<>();
        if (!m.done && m.turn == 1) for (int i = 0; i < N * N; i++) if (m.board[i] == 0 && forbidden(m.board, i % N, i / N)) forb.add(i);
        for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Json d = Json.obj().put("board", b.toString()).put("last", m.last).put("turn", m.turn).put("me", m.color(u))
                    .put("seconds", turnSeconds()).put("stake", stake()).put("forbidden", forb).put("done", m.done)
                    .put("black", side(m.black, m.strikes[1])).put("white", side(m.white, m.strikes[2]));
            if (open) plugin.ui().open(p, "omok", d);
            else plugin.ui().update(p, "omok", d);
        }
    }

    private Json side(UUID u, int strikes) {
        Player p = Bukkit.getPlayer(u);
        var d = plugin.data().get(u);
        String name = p != null ? plugin.displayName(p) : d != null && d.nickname != null ? d.nickname : "?";
        return Json.obj().put("name", name).put("account", p != null ? p.getName() : "").put("strikes", strikes);
    }

    // ───────── 규칙 ─────────
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    private static int at(int[] b, int x, int y) { return x < 0 || y < 0 || x >= N || y >= N ? -1 : b[y * N + x]; }

    /** (x,y) 를 지나는 한 방향 연속 개수 */
    private static int run(int[] b, int x, int y, int dx, int dy, int c) {
        int n = 1;
        for (int k = 1; at(b, x + dx * k, y + dy * k) == c; k++) n++;
        for (int k = 1; at(b, x - dx * k, y - dy * k) == c; k++) n++;
        return n;
    }

    /** 다섯 이상 (장목도 승리) */
    static boolean five(int[] b, int x, int y, int c) {
        for (int[] d : DIRS) if (run(b, x, y, d[0], d[1], c) >= 5) return true;
        return false;
    }

    /** 흑 쌍삼: 놓았을 때 열린 3 이 두 방향 이상 생김. 바로 오목이 되는 수는 허용 */
    static boolean forbidden(int[] b, int x, int y) {
        if (b[y * N + x] != 0) return false;
        b[y * N + x] = 1;
        try {
            if (five(b, x, y, 1)) return false;
            int threes = 0;
            for (int[] d : DIRS) if (openThree(b, x, y, d[0], d[1])) threes++;
            return threes >= 2;
        } finally { b[y * N + x] = 0; }
    }

    /** 이 방향에 한 수만 더 두면 열린 4(양쪽이 빈 정확히 4)가 되는 자리가 있으면 열린 3 */
    private static boolean openThree(int[] b, int x, int y, int dx, int dy) {
        for (int k = -4; k <= 4; k++) {
            int qx = x + dx * k, qy = y + dy * k;
            if (k == 0 || at(b, qx, qy) != 0) continue;
            b[qy * N + qx] = 1;
            boolean four = openFour(b, x, y, dx, dy);
            b[qy * N + qx] = 0;
            if (four) return true;
        }
        return false;
    }

    private static boolean openFour(int[] b, int x, int y, int dx, int dy) {
        int s = 0;
        while (at(b, x - dx * (s + 1), y - dy * (s + 1)) == 1) s++;
        int e = 0;
        while (at(b, x + dx * (e + 1), y + dy * (e + 1)) == 1) e++;
        if (s + e + 1 != 4) return false;
        return at(b, x - dx * (s + 1), y - dy * (s + 1)) == 0 && at(b, x + dx * (e + 1), y + dy * (e + 1)) == 0;
    }

    // ───────── 나감 / 정리 ─────────
    @Override
    public void quit(Player p) {
        Match m = of(p.getUniqueId());
        if (m != null) finish(m, 3 - m.color(p.getUniqueId()), "상대가 나갔어요");
        super.quit(p);
    }

    @Override
    protected void cleanup() {
        for (Match m : matches) {
            m.done = true;
            for (UUID u : List.of(m.black, m.white)) { Player p = Bukkit.getPlayer(u); if (p != null) plugin.ui().close(p, "omok"); }
        }
        matches.clear();
    }
}
