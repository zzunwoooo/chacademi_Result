package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.KyudichiProtocol;

/** End of a match: MVP, rating changes (rated only), DB write, result screen / chat summary. */
final class Results {
    private final KyudichiPlugin plugin;
    private final ResultOutbox outbox;
    /** Matches whose record is in flight (outbox replays skip them). */
    private final java.util.Set<UUID> inFlight = new java.util.HashSet<>();
    /** Ended matches still waiting for their rating write: the result screen goes to whoever is still here once it is recorded. */
    private final Map<UUID, Match> awaiting = new java.util.HashMap<>();
    Results(KyudichiPlugin plugin) { this.plugin = plugin; this.outbox = new ResultOutbox(plugin.getDataFolder().toPath().resolve("outbox"), plugin.getLogger()); }

    void finish(Match match, Team winner, Match.EndReason reason) {
        Rules rules = match.rules;
        boolean voided = reason == Match.EndReason.VOID;
        // MVP per team (tie: fewer deaths)
        Map<Team, Double> totals = new EnumMap<>(Team.class);
        for (Team t : Team.values()) totals.put(t, 0.0);
        for (var m : match.players.values()) { m.mvpPoints = m.mvpScore(rules.mvp); totals.merge(m.team, m.mvpPoints, Double::sum); }
        for (Team t : Team.values()) {
            match.players.values().stream().filter(m -> m.team == t && m.mvpPoints > 0)
                    .max(Comparator.<MatchPlayer>comparingDouble(m -> m.mvpPoints).thenComparing(m -> -m.deaths)).ifPresent(m -> m.mvp = true);
        }
        double[] avg = new double[2]; int[] count = new int[2];
        for (var m : match.players.values()) { avg[m.team.ordinal()] += m.ratingBefore; count[m.team.ordinal()]++; }
        for (int i = 0; i < 2; i++) avg[i] = count[i] == 0 ? rules.rating.start() : avg[i] / count[i];

        var deltas = new ArrayList<RatingStore.Delta>();
        Map<UUID, Integer> change = new java.util.HashMap<>();
        for (var m : match.players.values()) {
            RatingMath.Result res = winner == null ? RatingMath.Result.DRAW : winner == m.team ? RatingMath.Result.WIN : RatingMath.Result.LOSS;
            double total = totals.get(m.team);
            int d = 0;
            // never reached the arena (network match, moving failed): not a leaver — no rating change, nothing recorded
            if (match.opt.rated() && !voided && !m.neverArrived) {
                d = rules.rating.change(avg[m.team.ordinal()], avg[m.team.other().ordinal()], res, total <= 0 ? Double.NaN : m.mvpPoints / total, count[m.team.ordinal()], m.left);
                deltas.add(new RatingStore.Delta(m.id, m.nick, (int) m.ratingBefore, res == RatingMath.Result.WIN && !m.left, res == RatingMath.Result.DRAW && !m.left,
                        res == RatingMath.Result.LOSS && !m.left, m.left, m.goals, m.kills, m.deaths, m.assists, m.damage, m.heal, m.snitch, m.deflectHits, m.mvp,
                        m.team.ordinal(), d));
            }
            change.put(m.id, d);
        }
        int win = winner == null ? -1 : winner.ordinal();
        // 서버 통계 (메모 #96): 경기장에 실제로 온 선수만, 무효 경기 제외. 결투/큐디치 참가·승리·무승부·나감.
        if (!voided) for (var m : match.players.values()) {
            if (m.neverArrived) continue;
            String mode = match.duel ? "결투" : "큐디치";
            ChacaLink.stat(m.id, mode + "참가", 1);
            if (m.left) ChacaLink.stat(m.id, mode + "나감", 1);
            else if (winner == null) ChacaLink.stat(m.id, mode + "무승부", 1);
            else if (winner == m.team) ChacaLink.stat(m.id, mode + "승리", 1);
        }
        if (!deltas.isEmpty()) {
            // A09: persisted before the DB write; deleted only once the DB says recorded (or already recorded)
            var entry = new ResultOutbox.Entry(match.duel ? RatingStore.DUEL : RatingStore.MODE, match.id, match.arena.name, match.score[0], match.score[1], win, reason.name(), teams(match), deltas);
            outbox.save(entry);
            awaiting.put(match.id, match);
            record(entry, () -> {
                for (var m : match.players.values()) {
                    Player p = Bukkit.getPlayer(m.id);
                    if (p != null) ChacaLink.notify(p, "warning", "kyudichi:saving", "경기 결과를 저장하는 중이에요", "잠시 뒤 자동으로 반영돼요", 4000);
                }
            });
        }
        else for (var m : match.players.values()) {
            Player p = Bukkit.getPlayer(m.id);
            if (p != null) sendResult(match, p, m, winner, voided, 0, (int) m.ratingBefore);
        }
        plugin.getLogger().info((match.duel ? "1:1 대전 종료 " : "큐디치 경기 종료 ") + match.arena.name + " " + match.score[0] + ":" + match.score[1] + " 승리=" + (winner == null ? "무승부" : winner.key) + " 사유=" + reason
                + (match.opt.rated() ? "" : " (레이팅 미반영)"));
    }

    /** DB write of one outbox entry; on success the file goes and the result screen is shown (if the match is still in memory). */
    private void record(ResultOutbox.Entry e, Runnable failed) {
        if (!inFlight.add(e.match())) return;
        plugin.store().record(e.mode(), e.match(), e.arena(), e.scoreA(), e.scoreB(), e.winner(), e.reason(), e.teams(), e.deltas(), rows -> {
            inFlight.remove(e.match());
            if (rows == null) { plugin.getLogger().warning("큐디치 결과 저장 실패, outbox 에 남겨 1분 뒤 다시 시도: " + e.match()); if (failed != null) failed.run(); return; }
            outbox.delete(e.match());
            Match match = awaiting.remove(e.match());
            if (match == null) { plugin.getLogger().info("큐디치 결과 다시 저장 완료: " + e.match()); return; }
            Team winner = e.winner() < 0 ? null : Team.values()[e.winner()];
            for (var d : e.deltas()) {
                Player p = Bukkit.getPlayer(d.id()); MatchPlayer m = match.players.get(d.id()); var row = rows.get(d.id());
                if (p != null && m != null && row != null) sendResult(match, p, m, winner, false, d.delta(), row.rating());
            }
            // players without a delta (never arrived) still see the outcome
            for (var m : match.players.values()) {
                if (rows.containsKey(m.id)) continue;
                Player p = Bukkit.getPlayer(m.id);
                if (p != null) sendResult(match, p, m, winner, false, 0, (int) m.ratingBefore);
            }
        });
    }

    /** Start-up and every minute: replays results the DB did not confirm (idempotent per match ID). */
    void retryOutbox() {
        for (var e : outbox.all()) if (!inFlight.contains(e.match())) record(e, null);
    }

    /** 전적 table: each team as "<first player> 파티" with that player's house (matches have no team names). */
    private static RatingStore.Teams teams(Match match) {
        MatchPlayer a = null, b = null;
        for (var m : match.players.values()) { if (m.team == Team.A && (a == null || m.slot < a.slot)) a = m; if (m.team == Team.B && (b == null || m.slot < b.slot)) b = m; }
        String suffix = match.duel ? "" : " 파티";
        return new RatingStore.Teams(a == null ? "" : a.nick + suffix, b == null ? "" : b.nick + suffix, a == null ? -1 : a.house, b == null ? -1 : b.house);
    }

    private void sendResult(Match match, Player p, MatchPlayer m, Team winner, boolean voided, int delta, int ratingAfter) {
        var r = match.rules.rating;
        int outcome = voided ? 3 : winner == null ? 2 : winner == m.team ? 1 : 0;
        var before = r.tier(m.ratingBefore, 0); var after = r.tier(ratingAfter, plugin.store().rank(m.id, match.duel ? RatingStore.DUEL : RatingStore.MODE));
        if (plugin.net().hasMod(p)) {
            var lines = new ArrayList<KyudichiProtocol.Line>();
            for (var o : match.players.values()) lines.add(new KyudichiProtocol.Line(o.id, o.nick, o.team.ordinal(), o.goals, o.kills, o.deaths, o.snitch, o.mvp));
            plugin.net().send(p, new KyudichiProtocol.Result(outcome, match.score[0], match.score[1], delta, ratingAfter, before.ordinal(), after.ordinal(), lines));
            return;
        }
        String head = switch (outcome) { case 1 -> "승리"; case 0 -> "패배"; case 2 -> "무승부"; default -> "경기 무효"; };
        NamedTextColor color = outcome == 1 ? NamedTextColor.GOLD : outcome == 0 ? NamedTextColor.RED : NamedTextColor.GRAY;
        p.showTitle(Title.title(Component.text(head, color), Component.text(match.score[0] + " : " + match.score[1])));
        if (match.duel) {
            var names = teams(match);
            p.sendMessage(Component.text("── 1:1 대전 " + head + " " + names.nameA() + " " + match.score[0] + " : " + match.score[1] + " " + names.nameB() + " ──", color));
            for (Team t : Team.values()) for (var o : match.players.values()) if (o.team == t)
                p.sendMessage(Component.text("  " + o.nick + "  라운드 " + match.score[t.ordinal()] + " · 처치 " + o.kills + " · 데스 " + o.deaths, t.color));
        } else {
            p.sendMessage(Component.text("── 큐디치 " + head + " " + Team.A.label + " " + match.score[0] + " : " + match.score[1] + " " + Team.B.label + " ──", color));
            for (Team t : Team.values()) for (var o : match.players.values()) if (o.team == t)
                p.sendMessage(Component.text((o.mvp ? "♛ " : "  ") + o.nick + "  골 " + o.goals + " · 처치 " + o.kills + " · 데스 " + o.deaths + (o.snitch > 0 ? " · 스니치" : ""), t.color));
        }
        if (match.opt.rated() && !voided)
            p.sendMessage(Component.text("레이팅 " + (int) m.ratingBefore + " → " + ratingAfter + " (" + (delta >= 0 ? "+" : "") + delta + ")  " + after.label, NamedTextColor.AQUA));
        else if (!voided) p.sendMessage(Component.text((match.custom() ? "커스텀" : "테스트") + " 경기라 레이팅은 바뀌지 않았어요.", NamedTextColor.GRAY));
    }

    static List<String> describeTiers(RatingMath r) {
        int[] f = r.tierFloors();
        return List.of("브론즈 ~" + (f[0] - 1), "실버 " + f[0] + "~", "골드 " + f[1] + "~", "플래티넘 " + f[2] + "~", "다이아몬드 " + f[3] + "~", "마스터 " + f[4] + "~");
    }
}
