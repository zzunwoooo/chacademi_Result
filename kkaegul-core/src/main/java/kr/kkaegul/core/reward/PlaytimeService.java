package kr.kkaegul.core.reward;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 매일 접속 보상: 누적 접속 30분 / 3시간 / 8시간마다 수선 뽑기 (직접 확인하고 받는 방식).
 * 꽝이면 꽝 종이. 꽝 종이 5개 → 고급상자 교환.
 */
public final class PlaytimeService {
    private final KkaegulCore plugin;

    public PlaytimeService(KkaegulCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 60, 20L * 60);
    }

    private void tick() {
        String day = plugin.clock().dayKey();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(p);
            rollDay(d, day);
            d.playSecondsToday += 60;
            d.playSecondsTotal += 60;
            d.markDirty();
            int ready = readyCount(d);
            if (ready > 0 && d.playSecondsToday % 600 == 0) plugin.msg(p, "playtime.ready", "count", ready);
        }
    }

    private void rollDay(PlayerData d, String day) {
        if (!day.equals(d.playDay)) {
            d.playDay = day;
            d.playSecondsToday = 0;
            d.claimedMilestones.clear();
            d.markDirty();
        }
    }

    private ConfigurationSection milestones() { return plugin.getConfig().getConfigurationSection("playtime.milestones"); }

    public int readyCount(PlayerData d) {
        int n = 0;
        ConfigurationSection ms = milestones();
        if (ms == null) return 0;
        for (String k : ms.getKeys(false))
            if (!d.claimedMilestones.contains(k) && d.playSecondsToday >= ms.getLong(k + ".minutes") * 60) n++;
        return n;
    }

    public Json snapshot(PlayerData d) {
        List<Object> rows = new ArrayList<>();
        ConfigurationSection ms = milestones();
        if (ms != null) for (String k : ms.getKeys(false)) {
            long need = ms.getLong(k + ".minutes") * 60;
            rows.add(Json.obj().put("id", k).put("label", ms.getString(k + ".label", k)).put("needSeconds", need)
                    .put("claimed", d.claimedMilestones.contains(k)).put("ready", !d.claimedMilestones.contains(k) && d.playSecondsToday >= need));
        }
        return Json.obj().put("todaySeconds", d.playSecondsToday).put("milestones", rows)
                .put("failPapers", countFail(Bukkit.getPlayer(d.uuid))).put("exchangeNeed", plugin.getConfig().getInt("playtime.fail-exchange", 5));
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        rollDay(d, plugin.clock().dayKey());
        if (!plugin.menu().open(p, "playtime")) {
            p.sendMessage(Txt.color(Txt.notice("&e[접속 보상] &f오늘 접속 &a" + Txt.duration(d.playSecondsToday))));
            ConfigurationSection ms = milestones();
            if (ms != null) for (String k : ms.getKeys(false)) {
                long need = ms.getLong(k + ".minutes") * 60;
                String st = d.claimedMilestones.contains(k) ? "&8받음" : d.playSecondsToday >= need ? "&a받기 가능" : "&7" + Txt.duration(need - d.playSecondsToday) + " 남음";
                p.sendMessage(Txt.color(" &f" + ms.getString(k + ".label", k) + " " + st));
            }
            p.sendMessage(Txt.color(Txt.notice("&7/접속보상 받기 · /접속보상 교환 (꽝 종이 " + plugin.getConfig().getInt("playtime.fail-exchange", 5) + "개 → 고급상자)")));
        }
    }

    /** 받을 수 있는 것 전부 받기 */
    public void claim(Player p) {
        PlayerData d = plugin.data().get(p);
        rollDay(d, plugin.clock().dayKey());
        ConfigurationSection ms = milestones();
        if (ms == null) return;
        int got = 0;
        for (String k : ms.getKeys(false)) {
            if (d.claimedMilestones.contains(k) || d.playSecondsToday < ms.getLong(k + ".minutes") * 60) continue;
            d.claimedMilestones.add(k);
            got++;
            boolean win = Rng.chance(ms.getDouble(k + ".mending-chance", plugin.getConfig().getDouble("playtime.mending-chance", 30)));
            if (win) {
                plugin.mail().giveOrMail(p, plugin.items().mendingBook());
                if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&b수선 당첨!", "&f" + ms.getString(k + ".label", k));
            } else {
                plugin.mail().giveOrMail(p, plugin.items().create(CustomItem.FAIL_PAPER, 1));
                if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&8꽝...", "&f꽝 종이 1장 (5장 → 고급상자)");
            }
            plugin.ui().fx(p, "mending_draw", Json.obj().put("win", win).put("milestone", k));
        }
        d.markDirty();
        if (got == 0) plugin.msg(p, "playtime.nothing");
    }

    private int countFail(Player p) {
        if (p == null) return 0;
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (plugin.items().is(it, CustomItem.FAIL_PAPER)) n += it.getAmount();
        return n;
    }

    public void exchange(Player p) {
        int need = plugin.getConfig().getInt("playtime.fail-exchange", 5);
        if (countFail(p) < need) { plugin.msg(p, "playtime.not-enough", "need", need); return; }
        int left = need;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            if (!plugin.items().is(c[i], CustomItem.FAIL_PAPER)) continue;
            int take = Math.min(left, c[i].getAmount());
            left -= take;
            if (take >= c[i].getAmount()) p.getInventory().setItem(i, null);
            else { c[i].setAmount(c[i].getAmount() - take); p.getInventory().setItem(i, c[i]); }
        }
        plugin.boxes().giveBox(p.getUniqueId(), "premium", 1);
    }
}
