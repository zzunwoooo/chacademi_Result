package kr.kkaegul.core.title;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 칭호.
 * config titles.<id>: { name, condition: "<stat> >= <값>", fortune-bonus, statue, first-only, statue-commands }
 *   stat: pickaxe(가챠 최고 수치) | jail | breaks | deaths | increased | submitted | donated | graduated
 * 행운 보너스는 보유한 칭호 중 가장 큰 값(bonus-mode: owned) 또는 선택한 칭호(selected).
 */
public final class TitleService {
    private final KkaegulCore plugin;

    public TitleService(KkaegulCore plugin) { this.plugin = plugin; }

    private ConfigurationSection defs() { return plugin.getConfig().getConfigurationSection("titles.list"); }

    /**
     * 가챠 결과(행운/효율/내구성). titles.list.<id>.gacha-roll: 7 이면 7/7/7 에 지급.
     * titles.gacha-match: exact(세 칸이 정확히 그 숫자) | at-least(세 칸 모두 그 이상)
     */
    public void onGachaRoll(PlayerData d, int f, int e, int u) {
        int low = Math.min(f, Math.min(e, u));
        if (low > d.bestPickaxe) { d.bestPickaxe = low; d.markDirty(); }
        ConfigurationSection defs = defs();
        if (defs != null) {
            boolean exact = !"at-least".equalsIgnoreCase(plugin.getConfig().getString("titles.gacha-match", "exact"));
            for (String id : defs.getKeys(false)) {
                int want = defs.getInt(id + ".gacha-roll", 0);
                if (want <= 0 || d.titles.contains(id)) continue;
                boolean hit = exact ? (f == want && e == want && u == want) : low >= want;
                if (hit) grant(d, id);
            }
        }
        check(d);
    }

    private long stat(PlayerData d, String s) {
        return switch (s) {
            case "pickaxe" -> d.bestPickaxe;
            case "jail" -> d.jailCount;
            case "breaks" -> d.pickaxeBreaks;
            case "deaths" -> d.instantDeaths;
            case "increased" -> d.totalIncreased;
            case "submitted" -> d.totalSubmitted;
            case "donated" -> d.totalDonated;
            case "graduated" -> d.graduated ? 1 : 0;
            case "boxes" -> d.boxesOpened;
            default -> 0;
        };
    }

    private boolean met(PlayerData d, String cond) {
        if (cond == null || cond.isBlank()) return false;
        String[] p = cond.replace(" ", "").split(">=");
        if (p.length != 2) return false;
        try {
            return stat(d, p[0]) >= Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 조건을 새로 만족한 칭호 지급 */
    public void check(PlayerData d) {
        ConfigurationSection defs = defs();
        if (defs == null) return;
        for (String id : defs.getKeys(false)) {
            if (d.titles.contains(id)) continue;
            ConfigurationSection t = defs.getConfigurationSection(id);
            if (t == null || !met(d, t.getString("condition"))) continue;
            grant(d, id);
        }
    }

    private void grant(PlayerData d, String id) {
        ConfigurationSection defs = defs();
        ConfigurationSection t = defs == null ? null : defs.getConfigurationSection(id);
        if (t == null || d.titles.contains(id)) return;
        {
            boolean serverFirst = !plugin.global().firsts.containsKey("title:" + id);
            if (t.getBoolean("first-only", false) && !serverFirst) return;
            d.titles.add(id);
            if (d.selectedTitle.isEmpty()) d.selectedTitle = id;
            d.markDirty();
            if (serverFirst) {
                plugin.global().firsts.put("title:" + id, d.uuid.toString());
                plugin.global().dirty = true;
                if (t.getBoolean("statue", false))
                    for (String c : t.getStringList("statue-commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", d.name));
            }
            String name = t.getString("name", id);
            plugin.announce("&6[칭호] &f" + plugin.nickname().display(d) + "님이 " + name + " &f칭호를 획득했습니다!" + (serverFirst ? " &e(서버 최초)" : ""), true);
            Player p = Bukkit.getPlayer(d.uuid);
            if (p != null) {
                plugin.ui().center(p, name, "&f칭호 획득!");
                plugin.ui().fx(p, "title_unlock", Json.obj().put("id", id).put("name", name).put("serverFirst", serverFirst));
                applyName(p);
            }
        }
    }

    public String display(PlayerData d) {
        ConfigurationSection defs = defs();
        if (defs == null || d.selectedTitle.isEmpty() || !d.titles.contains(d.selectedTitle)) return "";
        return defs.getString(d.selectedTitle + ".name", "");
    }

    public int fortuneBonus(PlayerData d) {
        ConfigurationSection defs = defs();
        if (defs == null) return 0;
        if ("selected".equalsIgnoreCase(plugin.getConfig().getString("titles.bonus-mode", "owned"))) {
            return d.titles.contains(d.selectedTitle) ? defs.getInt(d.selectedTitle + ".fortune-bonus", 0) : 0;
        }
        int best = 0;
        for (String id : d.titles) best = Math.max(best, defs.getInt(id + ".fortune-bonus", 0));
        return best;
    }

    public java.util.List<String> owned(Player p) {
        java.util.List<String> o = new java.util.ArrayList<>(plugin.data().get(p).titles);
        o.add("없음");
        return o;
    }

    public boolean select(Player p, String id) {
        PlayerData d = plugin.data().get(p);
        if (id.equalsIgnoreCase("none") || id.equals("없음")) { d.selectedTitle = ""; d.markDirty(); applyName(p); return true; }
        if (!d.titles.contains(id)) return false;
        d.selectedTitle = id;
        d.markDirty();
        applyName(p);
        return true;
    }

    /** 탭리스트 이름 = 칭호 + 닉네임 */
    public void applyName(Player p) {
        if (!plugin.getConfig().getBoolean("titles.tab-list", true)) return;
        PlayerData d = plugin.data().get(p);
        String nick = d.nickname.isEmpty() ? p.getName() : d.nickname;
        String t = display(d);
        p.setPlayerListName(Txt.color((t.isEmpty() ? "" : t + " ") + "&f" + nick));
        if (plugin.nickname() != null) plugin.nickname().pushAll();
    }

    public String chatName(Player p) {
        PlayerData d = plugin.data().get(p);
        String nick = d.nickname.isEmpty() ? p.getName() : d.nickname;
        String t = display(d);
        return Txt.color((t.isEmpty() ? "" : t + " ") + "&f" + nick);
    }

    public Json snapshot(PlayerData d) {
        ConfigurationSection defs = defs();
        List<Object> rows = new ArrayList<>();
        if (defs != null) for (String id : defs.getKeys(false))
            rows.add(Json.obj().put("id", id).put("name", defs.getString(id + ".name", id)).put("owned", d.titles.contains(id))
                    .put("selected", id.equals(d.selectedTitle)).put("condition", defs.getString(id + ".description", defs.getString(id + ".condition", ""))));
        return Json.obj().put("rows", rows);
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        ConfigurationSection defs = defs();
        if (!plugin.menu().open(p, "titles")) {
            p.sendMessage(Txt.color(Txt.notice("&6[칭호] &7/칭호 <id> 로 선택")));
            if (defs != null) for (String id : defs.getKeys(false))
                p.sendMessage(Txt.color(" " + (d.titles.contains(id) ? "&a■" : "&8□") + " &f" + id + " " + defs.getString(id + ".name", id)
                        + (id.equals(d.selectedTitle) ? " &e(사용 중)" : "") + " &7" + defs.getString(id + ".description", "")));
        }
    }
}
