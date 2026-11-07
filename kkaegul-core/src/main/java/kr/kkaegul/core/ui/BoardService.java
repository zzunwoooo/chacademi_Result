package kr.kkaegul.core.ui;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.title.RankingService;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 월드에 세워두는 글자 전광판 (UI 창 대신).
 * /kg setloc board.<종류> 로 위치를 저장하면 그 자리에 떠 있는 글자판이 생기고 30초마다 갱신된다.
 * 종류: donation(기부 TOP10, 제출소 옆) | farm(작물 시세) | increased | breaks | deaths | remaining | donated | jail (명예의 전당 각 항목)
 */
public final class BoardService {
    private final KkaegulCore plugin;
    private final Map<String, Entity> spawned = new LinkedHashMap<>();

    public BoardService(KkaegulCore plugin) {
        this.plugin = plugin;
        long period = Math.max(100, plugin.getConfig().getLong("boards.refresh-ticks", 600));
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, period);
    }

    public void refresh() {
        for (String name : plugin.places().names()) {
            if (!name.startsWith("board.")) continue;
            Location l = plugin.places().get(name);
            if (l == null || l.getWorld() == null) continue;
            if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            String text = render(name.substring("board.".length()));
            if (text == null) continue;
            Entity e = spawned.get(name);
            if (e == null || !e.isValid()) {
                e = l.getWorld().spawnEntity(l, EntityType.TEXT_DISPLAY);
                e.setPersistent(false);
                e.addScoreboardTag("kkaegul_board");
                if (e instanceof TextDisplay td) td.setBillboard(Display.Billboard.CENTER);
                spawned.put(name, e);
            }
            if (e instanceof TextDisplay td) td.setText(Txt.color(text));
        }
        // 위치가 지워진 판은 제거
        spawned.entrySet().removeIf(en -> {
            if (plugin.places().get(en.getKey()) != null) return false;
            if (en.getValue().isValid()) en.getValue().remove();
            return true;
        });
    }

    private String render(String type) {
        int size = plugin.getConfig().getInt("ranking.size", 10);
        if (type.equals("donation")) {
            StringBuilder sb = new StringBuilder("&d랜덤 제출 기부 TOP" + size + "\n&7누적 기부 " + Txt.num(plugin.global().totalDonatedAll) + "개\n");
            int r = 1;
            for (PlayerData d : plugin.submit().donors(size)) sb.append("\n&f").append(r++).append(". ").append(plugin.nickname().display(d)).append(" &d").append(Txt.num(d.totalDonated));
            if (r == 1) sb.append("\n&7아직 기부자가 없어요");
            return sb.toString();
        }
        if (type.equals("farm")) {
            StringBuilder sb = new StringBuilder("&2작물 시세\n");
            plugin.global().farmPrices.forEach((k, v) -> sb.append("\n&f").append(k).append(" &b").append(kr.kkaegul.core.economy.EconomyService.fmt(v)).append("다이아"));
            long next = plugin.global().farmUpdatedAt + plugin.getConfig().getLong("economy.farm-reroll-minutes", 60) * 60_000L - System.currentTimeMillis();
            sb.append("\n\n&7다음 변동까지 ").append(Txt.duration(Math.max(0, next / 1000)));
            return sb.toString();
        }
        for (RankingService.Board b : plugin.ranking().boards()) {
            if (!b.id().equals(type)) continue;
            StringBuilder sb = new StringBuilder("&6" + b.label() + "\n");
            int r = 1;
            for (PlayerData d : plugin.ranking().top(b, size)) {
                String t = plugin.titles().display(d);
                sb.append("\n&f").append(r++).append(". ").append(t.isEmpty() ? "" : t + " ").append("&f").append(plugin.nickname().display(d))
                        .append(" &e").append(Txt.num(b.value().applyAsLong(d)));
            }
            if (r == 1) sb.append("\n&7기록 없음");
            return sb.toString();
        }
        return null;
    }

    public void removeAll() {
        for (Entity e : spawned.values()) if (e.isValid()) e.remove();
        spawned.clear();
    }
}
