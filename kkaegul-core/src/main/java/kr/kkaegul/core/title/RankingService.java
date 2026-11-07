package kr.kkaegul.core.title;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 명예의 전당: 다이아 누적 개수 / 곡괭이 파괴 횟수 / 즉사 횟수 / 제출까지 남은 다이아 / 기부.
 * 렌더러에는 board 화면 데이터로, 없으면 채팅으로.
 */
public final class RankingService {
    private final KkaegulCore plugin;

    public RankingService(KkaegulCore plugin) { this.plugin = plugin; }

    public record Board(String id, String label, ToLongFunction<PlayerData> value, boolean ascending) {}

    public List<Board> boards() {
        return List.of(
                new Board("increased", "다이아 누적 개수", d -> d.totalIncreased, false),
                new Board("breaks", "곡괭이 파괴 횟수", d -> d.pickaxeBreaks, false),
                new Board("deaths", "즉사 횟수", d -> d.instantDeaths, false),
                new Board("remaining", "제출까지 남은 다이아", d -> d.debt, plugin.getConfig().getBoolean("ranking.remaining-ascending", true)),
                new Board("donated", "기부 다이아", d -> d.totalDonated, false),
                new Board("jail", "깜빵 횟수", d -> d.jailCount, false));
    }

    public List<PlayerData> top(Board b, int limit) {
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData d : plugin.data().all()) {
            if (d.name == null || d.name.isEmpty()) continue;
            if (b.id().equals("remaining") && d.graduated && !plugin.getConfig().getBoolean("ranking.show-graduated", false)) continue;
            list.add(d);
        }
        Comparator<PlayerData> c = Comparator.comparingLong(b.value());
        list.sort(b.ascending() ? c : c.reversed());
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    public Json snapshot() {
        int limit = plugin.getConfig().getInt("ranking.size", 10);
        List<Object> boards = new ArrayList<>();
        for (Board b : boards()) {
            List<Object> rows = new ArrayList<>();
            int r = 1;
            for (PlayerData d : top(b, limit)) rows.add(Json.obj().put("rank", r++).put("name", plugin.nickname().display(d)).put("value", b.value().applyAsLong(d))
                    .put("title", plugin.titles().display(d)));
            boards.add(Json.obj().put("id", b.id()).put("label", b.label()).put("rows", rows));
        }
        return Json.obj().put("boards", boards);
    }

    public void open(Player p) {
        if (plugin.menu().open(p, "hall")) return;
        int limit = Math.min(5, plugin.getConfig().getInt("ranking.size", 10));
        p.sendMessage(Txt.color(Txt.notice("&6─── 명예의 전당 ───")));
        for (Board b : boards()) {
            StringBuilder sb = new StringBuilder("&e" + b.label() + " &7: ");
            int r = 1;
            for (PlayerData d : top(b, limit)) sb.append("&f").append(r++).append(". ").append(plugin.nickname().display(d)).append(" &7(").append(Txt.num(b.value().applyAsLong(d))).append(") ");
            p.sendMessage(Txt.color(sb.toString()));
        }
    }
}
