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

    private void checkGraduate(PlayerData d) {
        if (d.graduated || d.debt > 0) return;
        if (!plugin.getConfig().getBoolean("graduation.enabled", true)) return;
        if (d.totalIncreased <= 0) return; // 시작부터 0인 사람은 졸업 아님
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
        int kick = plugin.getConfig().getInt("graduation.kick-after-seconds", 30);
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
