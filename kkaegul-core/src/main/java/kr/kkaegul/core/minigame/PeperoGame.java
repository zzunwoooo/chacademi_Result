package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 빼빼로 게임 (11/11 근처).
 * 짝꿍 2명이 양쪽에서 클릭(팔 휘두르기). 둘의 클릭 합이 target-clicks 가 되면 성공.
 * 클릭할 때마다 break-chance-per-click% 로 "이유 없이" 부러짐 → 실패. (기본값이면 성공률 약 45%)
 * 성공: 고급상자 1개 / 실패: 빅증가 1개.
 */
public final class PeperoGame extends Minigame {
    private final Map<UUID, Pair> pairOf = new HashMap<>();
    private final List<Pair> pairs = new ArrayList<>();

    private static final class Pair {
        final List<UUID> members = new ArrayList<>();
        int clicks = 0;
        boolean done = false;
    }

    public PeperoGame(KkaegulCore plugin) { super(plugin, "pepero"); }

    @Override public String label() { return "빼빼로 게임"; }

    @Override public int minPlayers() { return cfgInt("min-players", 2); }

    @Override public String readiness() { return plugin.places().get("pepero.1.a") == null && plugin.places().get("pepero") == null ? "위치 pepero 또는 pepero.1.a / pepero.1.b 필요" : null; }

    @Override
    protected void start(List<Player> list) {
        pairs.clear();
        pairOf.clear();
        List<Player> shuffled = new ArrayList<>(list);
        java.util.Collections.shuffle(shuffled);
        for (int i = 0; i < shuffled.size(); i += 2) {
            Pair pr = new Pair();
            pr.members.add(shuffled.get(i).getUniqueId());
            if (i + 1 < shuffled.size()) pr.members.add(shuffled.get(i + 1).getUniqueId());
            pairs.add(pr);
            for (UUID u : pr.members) pairOf.put(u, pr);
            int n = pairs.size();
            place(shuffled.get(i), n, "a");
            if (i + 1 < shuffled.size()) place(shuffled.get(i + 1), n, "b");
        }
        int seconds = cfgInt("seconds", 15);
        int target = cfgInt("target-clicks", 60);
        for (Player p : online()) {
            Pair pr = pairOf.get(p.getUniqueId());
            String partner = "", account = "";
            for (UUID u : pr.members) if (!u.equals(p.getUniqueId())) { Player o = plugin.getServer().getPlayer(u); if (o != null) { partner = plugin.displayName(o); account = o.getName(); } }
            hudOpen(p, Json.obj().put("partner", Json.obj().put("name", partner).put("account", account)).put("target", target).put("progress", 0).put("seconds", seconds));
            if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&6빼빼로 게임!", "&f짝꿍 " + (partner.isEmpty() ? "없음(혼자)" : partner) + " · 연타해서 " + target + "번!");
            else plugin.ui().actionbar(p, "&6짝꿍 " + (partner.isEmpty() ? "없음(혼자)" : partner) + " &f· 연타해서 " + target + "번!");
        }
        later(seconds * 20L, () -> {
            for (Pair pr : pairs) if (!pr.done) finish(pr, false, "시간 초과");
            end();
        });
    }

    private void place(Player p, int pairIndex, String side) {
        Location l = plugin.places().get("pepero." + pairIndex + "." + side);
        if (l == null) {
            Location base = plugin.places().get("pepero");
            if (base == null) return;
            l = base.clone().add(pairIndex * 4.0, 0, side.equals("a") ? -1.5 : 1.5);
            l.setYaw(side.equals("a") ? 0 : 180);
        }
        p.teleport(l);
    }

    @Override
    public void onSwing(Player p) {
        Pair pr = pairOf.get(p.getUniqueId());
        if (pr == null || pr.done) return;
        pr.clicks++;
        if (Rng.chance(cfgDouble("break-chance-per-click", 1.3))) { finish(pr, false, "빼빼로가 이유 없이 부러졌다!"); checkAll(); return; }
        int target = cfgInt("target-clicks", 60);
        for (UUID u : pr.members) {
            Player m = plugin.getServer().getPlayer(u);
            if (m != null) plugin.ui().update(m, "minigame", Json.obj().put("progress", pr.clicks).put("target", target));
            if (m != null && !plugin.ui().hasRenderer(m)) plugin.ui().actionbar(m, "&6빼빼로 &f" + pr.clicks + " / " + target);
        }
        if (pr.clicks >= target) { finish(pr, true, "성공!"); checkAll(); }
    }

    private void finish(Pair pr, boolean ok, String why) {
        pr.done = true;
        for (UUID u : pr.members) {
            Player m = plugin.getServer().getPlayer(u);
            if (!ok && m != null) plugin.ui().update(m, "minigame", Json.obj().put("broken", true));
            setState(u, ok ? "success" : "out");
            if (m == null) continue;
            resultFx(m, ok, ok ? "짝꿍과 빼빼로 " + pr.clicks + "번 성공!" : why, ok ? "고급상자" : "빅증가 룰렛");
            if (ok) rewardSuccess(m); else rewardFail(m);
        }
    }

    private void checkAll() {
        for (Pair pr : pairs) if (!pr.done) return;
        later(40, this::end);
    }

    @Override protected void cleanup() { pairs.clear(); pairOf.clear(); }
}
