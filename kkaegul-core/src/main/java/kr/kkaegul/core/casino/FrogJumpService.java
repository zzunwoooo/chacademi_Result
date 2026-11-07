package kr.kkaegul.core.casino;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 개구리 점프 (혼자 하는 버티기 게임).
 * 다이아를 걸고 시작 → "점프"를 누를 때마다 다음 연잎으로. 성공하면 배수가 오르고, 실패하면 물에 빠져 건 다이아를 잃는다.
 * 언제든 "그만"을 누르면 (건 다이아 x 지금 배수) 를 받는다. 멀리 갈수록 성공 확률은 내려가고 배수는 크게 오른다.
 *
 * 한 사람당 한 판씩 따로 돌아가므로, 기계(frog-jump-machine.N 블록)나 NPC(frog-jump) 여러 개를 두면 여러 명이 동시에 한다.
 * 결과는 서버가 정한다 (클라이언트는 그림만).
 */
public final class FrogJumpService {
    private final KkaegulCore plugin;

    private static final class Game { long bet; int step; long startedAt; }
    private final Map<UUID, Game> games = new HashMap<>();
    public record Step(double chance, double multiplier) {}

    public FrogJumpService(KkaegulCore plugin) { this.plugin = plugin; }

    private String cfg(String k) { return "casino.frog-jump." + k; }
    public boolean enabled() { return plugin.getConfig().getBoolean(cfg("enabled"), true); }

    /** 점프 단계표: steps 아래 순서대로 {chance, multiplier}. multiplier 는 그 칸까지 갔을 때 받는 총 배수 */
    public List<Step> steps() {
        List<Step> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(cfg("steps"));
        if (s != null) for (String k : s.getKeys(false)) out.add(new Step(s.getDouble(k + ".chance", 50), s.getDouble(k + ".multiplier", 1)));
        if (out.isEmpty()) {
            double[][] def = {{90, 1.1}, {85, 1.25}, {80, 1.5}, {75, 1.9}, {70, 2.5}, {65, 3.4}, {60, 4.8}, {55, 7}, {50, 11}, {45, 18}};
            for (double[] d : def) out.add(new Step(d[0], d[1]));
        }
        return out;
    }

    private long minBet() { return Math.max(1, plugin.getConfig().getLong(cfg("min-bet"), 1)); }
    private long maxBet() { return Math.max(minBet(), plugin.getConfig().getLong(cfg("max-bet"), 500)); }

    public boolean playing(Player p) { return games.containsKey(p.getUniqueId()); }

    /** 창 데이터 */
    public Json info(Player p) {
        Game g = games.get(p.getUniqueId());
        List<Step> st = steps();
        List<Object> table = new ArrayList<>();
        for (Step s : st) table.add(Json.obj().put("chance", s.chance()).put("multiplier", s.multiplier()));
        Json j = Json.obj().put("available", plugin.submit().available(p)).put("minBet", minBet()).put("maxBet", maxBet())
                .put("steps", table).put("playing", g != null);
        if (g != null) {
            double mult = g.step == 0 ? 1 : st.get(g.step - 1).multiplier();
            j.put("bet", g.bet).put("step", g.step).put("multiplier", mult).put("cashout", payout(g.bet, mult));
            if (g.step < st.size()) j.put("nextChance", st.get(g.step).chance()).put("nextMultiplier", st.get(g.step).multiplier());
        }
        return j;
    }

    private static long payout(long bet, double mult) { return (long) Math.floor(bet * mult + 1e-9); }

    /** 기계/NPC 클릭: 렌더러 있으면 창, 없으면 채팅 안내 */
    public void open(Player p) {
        if (!enabled()) { plugin.msg(p, "shop.disabled"); return; }
        if (plugin.ui().hasRenderer(p)) { plugin.ui().open(p, "frog_jump", info(p)); return; }
        Game g = games.get(p.getUniqueId());
        if (g == null) p.sendMessage(Txt.color(Txt.notice("&a[개구리 점프] &f/점프 시작 <다이아> &7로 시작, &f/점프 &7로 한 칸, &f/점프 그만 &7으로 받기")));
        else status(p, g);
    }

    private void status(Player p, Game g) {
        List<Step> st = steps();
        double mult = g.step == 0 ? 1 : st.get(g.step - 1).multiplier();
        String next = g.step < st.size() ? " &7| 다음 칸 성공 " + fmt(st.get(g.step).chance()) + "% → x" + fmt(st.get(g.step).multiplier()) : "";
        p.sendMessage(Txt.color(Txt.notice("&a[개구리 점프] &f" + g.step + "칸 &ex" + fmt(mult) + " &f(지금 그만하면 &b" + Txt.num(payout(g.bet, mult)) + "&f)" + next)));
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    public void start(Player p, long bet) {
        if (!enabled()) return;
        if (games.containsKey(p.getUniqueId())) { open(p); return; }
        if (bet < minBet() || bet > maxBet()) { plugin.ui().error(p, "&c걸 수 있는 다이아는 " + minBet() + "~" + maxBet() + "개예요."); sync(p); return; }
        long have = plugin.submit().available(p);
        if (have < bet) { plugin.msg(p, "shop.no-diamond", "need", Txt.num(bet), "have", Txt.num(have)); sync(p); return; }
        plugin.submit().takeDiamonds(p, bet);
        Game g = new Game();
        g.bet = bet;
        g.startedAt = System.currentTimeMillis();
        games.put(p.getUniqueId(), g);
        plugin.hud().refresh(p);
        plugin.ui().fx(p, "jump_start", Json.obj().put("bet", bet));
        if (!plugin.ui().hasRenderer(p)) status(p, g);
        sync(p);
    }

    public void jump(Player p) {
        Game g = games.get(p.getUniqueId());
        if (g == null) { open(p); return; }
        List<Step> st = steps();
        if (g.step >= st.size()) { cashout(p); return; }
        Step s = st.get(g.step);
        if (Rng.chance(s.chance())) {
            g.step++;
            plugin.ui().fx(p, "jump_ok", Json.obj().put("step", g.step).put("multiplier", s.multiplier()).put("cashout", payout(g.bet, s.multiplier())));
            p.playSound(p.getLocation(), "minecraft:entity.frog.long_jump", 1f, 1f + g.step * .05f);
            if (g.step >= st.size()) { cashout(p); return; }   // 끝까지 가면 자동으로 받기
            if (!plugin.ui().hasRenderer(p)) status(p, g);
        } else {
            games.remove(p.getUniqueId());
            plugin.ui().fx(p, "jump_fail", Json.obj().put("step", g.step + 1).put("bet", g.bet));
            p.playSound(p.getLocation(), "minecraft:entity.player.splash", 1f, 1f);
            if (!plugin.ui().hasRenderer(p)) plugin.ui().error(p, "&c[개구리 점프] 풍덩! " + (g.step + 1) + "번째 칸에서 빠졌어요. 다이아 " + Txt.num(g.bet) + "개를 잃었어요.");
            broadcastBig(p, g.step, 0);
        }
        sync(p);
    }

    public void cashout(Player p) {
        Game g = games.remove(p.getUniqueId());
        if (g == null) { open(p); return; }
        List<Step> st = steps();
        double mult = g.step == 0 ? 1 : st.get(g.step - 1).multiplier();
        long got = payout(g.bet, mult);
        PlayerData d = plugin.data().get(p);
        plugin.wallet().deposit(d, got, p);
        plugin.ui().fx(p, "jump_cashout", Json.obj().put("step", g.step).put("multiplier", mult).put("got", got).put("bet", g.bet));
        p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1f, 1.2f);
        if (!plugin.ui().hasRenderer(p)) p.sendMessage(Txt.color(Txt.notice("&a[개구리 점프] " + g.step + "칸에서 멈춤! &bx" + fmt(mult) + " → 다이아 " + Txt.num(got) + "개")));
        broadcastBig(p, g.step, got);
        sync(p);
    }

    /** 멀리 간 사람은 전체 알림 */
    private void broadcastBig(Player p, int step, long got) {
        int at = plugin.getConfig().getInt(cfg("announce-step"), 7);
        if (at <= 0 || step < at) return;
        String msg = got > 0 ? "&a[개구리 점프] &f" + plugin.displayName(p) + "&f님이 &e" + step + "칸&f까지 뛰고 다이아 &b" + Txt.num(got) + "개&f를 챙겼어요!"
                : "&c[개구리 점프] &f" + plugin.displayName(p) + "&f님이 &e" + step + "칸&f까지 갔다가 풍덩!";
        plugin.announce(msg, true);
    }

    private void sync(Player p) { if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "frog_jump", info(p)); }

    /** 나가면 지금 배수로 자동 정산 (나가기로 손해를 피하는 건 '그만'과 같아서 문제 없음) */
    public void quit(Player p) { if (games.containsKey(p.getUniqueId())) cashout(p); }

    public void shutdown() {
        for (UUID id : new ArrayList<>(games.keySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) cashout(p);
        }
        games.clear();
    }

    /** 기계 블록 우클릭이면 true: frog-jump-machine.N 위치(그 기계 앞에 서서 /kg setloc 한 자리)에서 2칸 안의 블록 */
    public boolean isMachine(Block b) {
        if (b == null || b.getWorld() == null) return false;
        Location bl = b.getLocation().add(0.5, 0.5, 0.5);
        for (Location l : plugin.places().list("frog-jump-machine")) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(b.getWorld().getName())) continue;
            if (l.distanceSquared(bl) <= plugin.getConfig().getDouble(cfg("machine-radius"), 2.0) * plugin.getConfig().getDouble(cfg("machine-radius"), 2.0)) {
                String need = plugin.getConfig().getString(cfg("machine-block"), "");
                if (need.isBlank() || b.getType().name().equalsIgnoreCase(need)) return true;
            }
        }
        return false;
    }
}
