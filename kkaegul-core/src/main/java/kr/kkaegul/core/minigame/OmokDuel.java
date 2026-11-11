package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.casino.CasinoSupport;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 오목 자유 대전 (NPC·/오목): 원할 때 상대를 골라 다이아를 걸고 1:1.
 *  /오목 신청 <닉네임> <다이아> → 상대가 /오목 수락 (60초) → 그 자리에서 오목판 창으로 시작.
 *  시작할 때 두 사람 다 건 다이아를 맡겨 두고, 이긴 사람이 상대 다이아를 가져감. 수수료는 양쪽에서 fee-percent(5%)씩.
 *  무승부 = 맡긴 다이아 그대로 돌려줌. 나가거나 시간 초과 2번 = 패배. 규칙은 일정 오목(OmokGame)과 같음.
 */
public final class OmokDuel {
    private final KkaegulCore plugin;

    private record Offer(UUID from, UUID to, long stake, long at) {}

    private final class Match {
        final UUID black, white;
        final long stake;
        final int[] board = new int[OmokGame.N * OmokGame.N];
        int turn = 1, moves = 0, last = -1, token = 0;
        final int[] strikes = new int[3];
        boolean done = false;
        Match(UUID black, UUID white, long stake) { this.black = black; this.white = white; this.stake = stake; }
        UUID who(int color) { return color == 1 ? black : white; }
        int color(UUID u) { return u.equals(black) ? 1 : u.equals(white) ? 2 : 0; }
    }

    private final Map<UUID, Offer> offers = new HashMap<>();   // 받는 사람 → 신청
    private final Map<UUID, Match> playing = new HashMap<>();

    public OmokDuel(KkaegulCore plugin) { this.plugin = plugin; }

    private String cfg(String k) { return "minigame.games.omok.duel." + k; }
    private int feePercent() { return Math.max(0, Math.min(100, plugin.getConfig().getInt(cfg("fee-percent"), 5))); }
    private int turnSeconds() { return Math.max(5, plugin.getConfig().getInt("minigame.games.omok.turn-seconds", 20)); }

    public boolean playing(Player p) { return playing.containsKey(p.getUniqueId()); }

    /** NPC 클릭 / 인자 없는 /오목: 하는 중이면 판 다시 열기, 아니면 사용법 */
    public void help(Player p) {
        Match m = playing.get(p.getUniqueId());
        if (m != null && !m.done) { send(m, true); return; }
        p.sendMessage(Txt.color(Txt.notice("&f[깨굴 오목] &e/오목 신청 <닉네임> <다이아> &7· 받은 신청은 &e/오목 수락 &7또는 &e/오목 거절")));
        p.sendMessage(Txt.color(Txt.notice("&7이긴 사람이 상대가 건 다이아를 가져가요. 수수료는 양쪽에서 " + feePercent() + "%씩 떼요.")));
    }

    private String busyReason(Player p) {
        if (playing(p)) return "이미 오목 중이에요.";
        if (plugin.minigames().busy(p)) return "다른 게임 중이에요.";
        if (plugin.data().get(p).inJail) return "감옥에서는 할 수 없어요.";
        return null;
    }

    public void offer(Player p, String targetName, long stake) {
        Player t = plugin.data().online(targetName);
        if (t == null) { plugin.ui().error(p, "&c접속 중인 사람이 아니에요: " + targetName); return; }
        if (t.equals(p)) { plugin.ui().error(p, "&c자기 자신에게는 신청할 수 없어요."); return; }
        if (stake <= 0) { plugin.ui().error(p, "&c걸 다이아 개수를 1 이상으로 적어 주세요."); return; }
        String why = busyReason(p);
        if (why != null) { plugin.ui().error(p, "&c" + why); return; }
        if ((why = busyReason(t)) != null) { plugin.ui().error(p, "&c상대가 " + why); return; }
        if (CasinoSupport.available(plugin, p) < stake) { plugin.ui().error(p, "&c보유 다이아가 부족해요. &7(필요 " + Txt.num(stake) + "개)"); return; }
        offers.put(t.getUniqueId(), new Offer(p.getUniqueId(), t.getUniqueId(), stake, System.currentTimeMillis()));
        p.sendMessage(Txt.color(Txt.notice("&f[깨굴 오목] " + plugin.displayName(t) + "님에게 다이아 &b" + Txt.num(stake) + "개 &f대전을 신청했어요. (60초)")));
        t.sendMessage(Txt.color(Txt.notice("&f[깨굴 오목] " + plugin.displayName(p) + "님이 다이아 &b" + Txt.num(stake) + "개 &f오목 대전을 신청했어요. &e/오목 수락 &7· &e/오목 거절")));
        t.playSound(t.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
    }

    public void deny(Player p) {
        Offer o = offers.remove(p.getUniqueId());
        if (o == null) { plugin.ui().error(p, "&c받은 오목 신청이 없어요."); return; }
        p.sendMessage(Txt.color(Txt.notice("&7오목 신청을 거절했어요.")));
        Player f = Bukkit.getPlayer(o.from());
        if (f != null) f.sendMessage(Txt.color(Txt.notice("&7" + plugin.displayName(p) + "님이 오목 신청을 거절했어요.")));
    }

    public void accept(Player p) {
        Offer o = offers.remove(p.getUniqueId());
        if (o == null || System.currentTimeMillis() - o.at() > 60_000) { plugin.ui().error(p, "&c받은 오목 신청이 없거나 시간이 지났어요."); return; }
        Player f = Bukkit.getPlayer(o.from());
        if (f == null) { plugin.ui().error(p, "&c신청한 사람이 나갔어요."); return; }
        String why = busyReason(p);
        if (why != null) { plugin.ui().error(p, "&c" + why); return; }
        if ((why = busyReason(f)) != null) { plugin.ui().error(p, "&c상대가 " + why); return; }
        long stake = o.stake();
        if (CasinoSupport.available(plugin, p) < stake) { plugin.ui().error(p, "&c보유 다이아가 부족해요. &7(필요 " + Txt.num(stake) + "개)"); return; }
        if (CasinoSupport.available(plugin, f) < stake) { plugin.ui().error(p, "&c상대의 보유 다이아가 부족해요."); return; }
        // 두 사람 다 맡겨 둠 (게임 중에 써 버리지 못하게)
        long a = CasinoSupport.take(plugin, f, stake), b = CasinoSupport.take(plugin, p, stake);
        if (a < stake || b < stake) {
            CasinoSupport.give(plugin, f.getUniqueId(), a);
            CasinoSupport.give(plugin, p.getUniqueId(), b);
            plugin.ui().error(p, "&c다이아를 맡기지 못해 취소했어요.");
            return;
        }
        boolean fBlack = Rng.chance(50);
        Match m = new Match((fBlack ? f : p).getUniqueId(), (fBlack ? p : f).getUniqueId(), stake);
        playing.put(m.black, m);
        playing.put(m.white, m);
        for (Player x : List.of(f, p))
            x.sendMessage(Txt.color(Txt.notice("&f[깨굴 오목] 대전 시작! 다이아 &b" + Txt.num(stake) + "개 &f· 흑만 쌍삼 금지")));
        send(m, true);
        turn(m);
    }

    // ───────── 진행 (OmokGame 과 같은 규칙) ─────────
    private void turn(Match m) {
        if (m.done) return;
        int tok = ++m.token;
        send(m, false);
        Player p = Bukkit.getPlayer(m.who(m.turn));
        if (p != null) p.playSound(p.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!m.done && m.token == tok) timeout(m); }, turnSeconds() * 20L);
    }

    private void timeout(Match m) {
        int c = m.turn;
        m.strikes[c]++;
        if (m.strikes[c] >= Math.max(1, plugin.getConfig().getInt("minigame.games.omok.max-timeouts", 2))) { finish(m, 3 - c, "시간 초과 " + m.strikes[c] + "번"); return; }
        Player p = Bukkit.getPlayer(m.who(c));
        if (p != null) plugin.ui().error(p, "&c시간이 지나 차례가 넘어갔어요. 한 번 더 넘기면 패배예요.");
        m.turn = 3 - c;
        turn(m);
    }

    /** 오목판 클릭. 자유 대전 중이면 true */
    public boolean place(Player p, int x, int y) {
        Match m = playing.get(p.getUniqueId());
        if (m == null) return false;
        if (m.done) return true;
        int c = m.color(p.getUniqueId()), n = OmokGame.N;
        if (c != m.turn) { plugin.ui().error(p, "&c상대 차례예요."); return true; }
        if (x < 0 || y < 0 || x >= n || y >= n || m.board[y * n + x] != 0) return true;
        if (c == 1 && OmokGame.forbidden(m.board, x, y)) { plugin.ui().error(p, "&c쌍삼 금지 자리예요."); return true; }
        m.board[y * n + x] = c;
        m.last = y * n + x;
        m.moves++;
        m.token++;
        for (UUID u : List.of(m.black, m.white)) { Player o = Bukkit.getPlayer(u); if (o != null) o.playSound(o.getLocation(), "minecraft:block.wood.place", 1f, 1.3f); }
        if (OmokGame.five(m.board, x, y, c)) { finish(m, c, "오목 완성"); return true; }
        if (m.moves >= n * n) { finish(m, 0, "판이 가득 찼어요"); return true; }
        m.turn = 3 - c;
        turn(m);
        return true;
    }

    /** winner 0 = 무승부 */
    private void finish(Match m, int winner, String why) {
        if (m.done) return;
        m.done = true;
        m.token++;
        long fee = m.stake * feePercent() / 100, prize = 0;
        if (winner == 0) {
            CasinoSupport.give(plugin, m.black, m.stake);
            CasinoSupport.give(plugin, m.white, m.stake);
        } else {
            prize = m.stake * 2 - fee * 2;   // 내 다이아 + 상대 다이아 − 양쪽 수수료
            CasinoSupport.give(plugin, m.who(winner), prize);
        }
        send(m, false);
        long net = prize - m.stake;
        for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            boolean win = winner != 0 && m.who(winner).equals(u);
            String sub = winner == 0 ? why + " · 무승부 (다이아 그대로)" : why + " · 다이아 " + (win ? "+" + net : "-" + m.stake) + " (수수료 " + feePercent() + "%)";
            plugin.ui().update(p, "omok", Json.obj().put("result", winner == 0 ? "draw" : win ? "win" : "lose").put("resultSub", sub));
            if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, winner == 0 ? "&7무승부" : win ? "&a승리!" : "&c패배…", "&f" + sub);
        }
        if (winner != 0) {
            Player w = Bukkit.getPlayer(m.who(winner)), l = Bukkit.getPlayer(m.who(3 - winner));
            plugin.announce("&f[깨굴 오목] " + (w != null ? plugin.displayName(w) : "?") + " 승리! " + (l != null ? plugin.displayName(l) : "?") + "에게서 다이아 " + m.stake + "개", false);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : List.of(m.black, m.white)) {
                playing.remove(u);
                Player p = Bukkit.getPlayer(u);
                if (p != null) plugin.ui().close(p, "omok");
            }
        }, Math.max(1, plugin.getConfig().getInt("minigame.games.omok.result-seconds", 5)) * 20L);
    }

    public void quit(Player p) {
        offers.remove(p.getUniqueId());
        Match m = playing.get(p.getUniqueId());
        if (m != null && !m.done) finish(m, 3 - m.color(p.getUniqueId()), "상대가 나갔어요");
    }

    /** 플러그인 꺼질 때: 진행 중인 판은 맡긴 다이아 돌려줌 */
    public void shutdown() {
        for (Match m : new ArrayList<>(playing.values())) {
            if (m.done) continue;
            m.done = true;
            CasinoSupport.give(plugin, m.black, m.stake);
            CasinoSupport.give(plugin, m.white, m.stake);
        }
        playing.clear();
    }

    private void send(Match m, boolean open) {
        int n = OmokGame.N;
        StringBuilder b = new StringBuilder(n * n);
        for (int v : m.board) b.append(v);
        List<Object> forb = new ArrayList<>();
        if (!m.done && m.turn == 1) for (int i = 0; i < n * n; i++) if (m.board[i] == 0 && OmokGame.forbidden(m.board, i % n, i / n)) forb.add(i);
        for (UUID u : List.of(m.black, m.white)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Json d = Json.obj().put("board", b.toString()).put("last", m.last).put("turn", m.turn).put("me", m.color(u))
                    .put("seconds", turnSeconds()).put("stake", m.stake).put("forbidden", forb).put("done", m.done)
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
}
