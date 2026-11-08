package kr.kkaegul.core.diamond;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 다이아 장부.
 *  - debt = 제출까지 남은 다이아. 증가 = 늘어남(나쁨), 감소 = 줄어듦(좋음), 0 = 졸업.
 *  - 초반 보호: 1~2일차에 남은 다이아가 threshold(500) 미만이면 감소가 적용되지 않고,
 *    threshold 이상이면 threshold 아래로 내려가지 않는다. (강제졸업 방지)
 */
public final class DiamondService {
    private final KkaegulCore plugin;

    public DiamondService(KkaegulCore plugin) { this.plugin = plugin; }

    public long threshold() { return plugin.getConfig().getLong("protection.threshold", 500); }

    public long increase(PlayerData d, long amount, String reason) {
        if (amount <= 0) return 0;
        // 졸업한 사람은 증가가 더 이상 붙지 않음 (revoke-on-increase 면 예전처럼 졸업 취소)
        if (d.graduated && !plugin.getConfig().getBoolean("graduation.revoke-on-increase", false)
                && plugin.getConfig().getBoolean("graduation.ignore-increase", true)) return 0;
        d.debt += amount;
        d.totalIncreased += amount;
        if (d.graduated && plugin.getConfig().getBoolean("graduation.revoke-on-increase", false)) d.graduated = false;
        cancelGrace(d, true);   // 졸업 유예 중에 증가가 오면 졸업 취소
        d.markDirty();
        push(d, +amount, reason);
        return amount;
    }

    /** @return 실제로 감소된 양 (보호로 막히면 0) */
    public long decrease(PlayerData d, long amount, String reason) {
        if (amount <= 0) return 0;
        long applied = amount;
        if (plugin.clock().protectionActive()) {
            long floor = threshold();
            if (d.debt < floor) {
                Player p = Bukkit.getPlayer(d.uuid);
                if (p != null) plugin.msg(p, "diamond.protected", "threshold", Txt.num(floor));
                push(d, 0, reason + " (초반 보호)");
                return 0;
            }
            applied = Math.min(applied, d.debt - floor);
        }
        applied = Math.min(applied, d.debt);
        if (applied <= 0) { push(d, 0, reason); return 0; }
        d.debt -= applied;
        d.totalDecreased += applied;
        d.markDirty();
        push(d, -applied, reason);
        checkGraduate(d);
        return applied;
    }

    /** 제출로 줄이기 (초반 보호 미적용: 본인이 직접 캔 다이아를 내는 것) */
    public long submitReduce(PlayerData d, long amount) {
        long applied = Math.min(Math.max(0, amount), d.debt);
        d.debt -= applied;
        d.totalSubmitted += applied;
        d.markDirty();
        push(d, -applied, "제출");
        checkGraduate(d);
        return applied;
    }

    public void set(PlayerData d, long value) {
        d.debt = Math.max(0, value);
        d.markDirty();
        push(d, 0, "관리자 설정");
    }

    /** 졸업 유예 중인 사람 → 유예가 끝나면 졸업하는 예약 */
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> grace = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean inGrace(PlayerData d) { return grace.containsKey(d.uuid); }

    private void cancelGrace(PlayerData d, boolean byIncrease) {
        org.bukkit.scheduler.BukkitTask t = grace.remove(d.uuid);
        if (t == null) return;
        t.cancel();
        if (!byIncrease) return;
        plugin.announce("&c[졸업 취소] &f" + plugin.nickname().display(d) + "님, 졸업 직전에 다이아가 늘어났어요!", true);
        Player p = Bukkit.getPlayer(d.uuid);
        if (p != null) plugin.ui().taunt(p, "&c졸업 취소!", "&f유예 시간 안에 다이아가 늘어났어요");
    }

    /**
     * 남은 다이아 0 → graduation.grace-seconds(기본 60초) 유예. 그 안에 증가가 오면 졸업 취소,
     * 끝까지 0이면 졸업하고 kick-after-seconds 뒤 퇴장.
     */
    private void checkGraduate(PlayerData d) {
        if (d.graduated || d.debt > 0 || inGrace(d)) return;
        if (!plugin.getConfig().getBoolean("graduation.enabled", true)) return;
        if (d.totalIncreased <= 0) return; // 시작부터 0인 사람은 졸업 아님
        int sec = plugin.getConfig().getInt("graduation.grace-seconds", 60);
        if (sec <= 0) { graduate(d); return; }
        java.util.UUID id = d.uuid;
        plugin.announce("&6[졸업 대기] &e" + plugin.nickname().display(d) + "&f님 다이아 0개! &7" + sec + "초 안에 다이아가 늘어나지 않으면 졸업", true);
        Player p0 = Bukkit.getPlayer(id);
        if (p0 != null) plugin.ui().center(p0, "&6졸업 대기 " + sec + "초", "&f이 시간 동안 다이아가 늘어나면 졸업이 취소돼요");
        int[] left = {sec};
        grace.put(id, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            left[0]--;
            Player p = Bukkit.getPlayer(id);
            if (left[0] > 0) { if (p != null) p.sendActionBar(Txt.color("&6졸업까지 &f" + left[0] + "초 &7(다이아가 늘어나면 취소)")); return; }
            org.bukkit.scheduler.BukkitTask t = grace.remove(id);
            if (t != null) t.cancel();
            if (d.debt <= 0 && !d.graduated) graduate(d);
        }, 20L, 20L));
    }

    private void graduate(PlayerData d) {
        d.graduated = true;
        d.graduatedAt = System.currentTimeMillis();
        d.markDirty();
        String msg = plugin.getConfig().getString("graduation.broadcast", "&6[졸업] &e{name}&f님이 다이아를 모두 제출하고 졸업했습니다!");
        plugin.announce(Txt.fmt(msg, "name", plugin.nickname().display(d)), true);
        Player p = Bukkit.getPlayer(d.uuid);
        if (p != null) {
            plugin.ui().center(p, "&6졸업!", "&f다이아를 모두 제출했어요");
            plugin.ui().fx(p, "graduate", Json.obj().put("name", plugin.nickname().display(d)));
        }
        List<String> cmds = plugin.getConfig().getStringList("graduation.commands");
        for (String c : cmds) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", d.name));
        plugin.titles().check(d);
        // 졸업하면 N초 뒤 퇴장
        int kick = plugin.getConfig().getInt("graduation.kick-after-seconds", 10);
        if (kick > 0 && p != null) {
            java.util.UUID id = d.uuid;
            p.sendMessage(Txt.color(Txt.notice("&f졸업을 축하해요! " + kick + "초 뒤 서버에서 퇴장합니다.")));
            for (int t : new int[]{10, 5, 3, 2, 1}) if (t < kick)
                Bukkit.getScheduler().runTaskLater(plugin, () -> { Player now = Bukkit.getPlayer(id); if (now != null) now.sendActionBar(Txt.color("&6졸업 퇴장까지 &f" + t + "초")); }, (kick - t) * 20L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player now = Bukkit.getPlayer(id);
                if (now != null && plugin.data().get(now).graduated)
                    now.kickPlayer(Txt.color(plugin.getConfig().getString("graduation.kick-message", "&6졸업을 축하합니다!")));
            }, kick * 20L);
        }
    }

    private void push(PlayerData d, long delta, String reason) {
        Player p = Bukkit.getPlayer(d.uuid);
        if (p == null) return;
        plugin.ui().update(p, "diamond", Json.obj().put("debt", d.debt).put("delta", delta).put("reason", reason)
                .put("protected", plugin.clock().protectionActive() && d.debt < threshold()));
        plugin.hud().refresh(p);
    }
}
