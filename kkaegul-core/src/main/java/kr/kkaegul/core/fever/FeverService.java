package kr.kkaegul.core.fever;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 피버 2종.
 *  - 다이아 증감 피버(섭주 1000): 터질 때 2배 또는 3배(triple-chance %)로 정해져 피버 내내 유지.
 *    룰렛 한 칸의 범위에 배수를 곱한다 (10~189 → 2배 20~378 / 3배 30~567). 1+1·1+2 는 운빨 게이지가 정함.
 *  - 상자 피버(섭주 1289): 상자 오픈 시 내용물 1+1.
 */
public final class FeverService {
    private final KkaegulCore plugin;
    private long diamondUntil = 0, tripleUntil = 0, boxUntil = 0;
    private BukkitTask ticker;

    public FeverService(KkaegulCore plugin) {
        this.plugin = plugin;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public boolean diamondFeverActive() { return System.currentTimeMillis() < diamondUntil; }
    public boolean tripleActive() { return diamondFeverActive() && System.currentTimeMillis() < tripleUntil; }
    public boolean boxFeverActive() { return System.currentTimeMillis() < boxUntil; }

    /** 룰렛 범위 배수: 피버 2, 3배 피버 3, 평소 1 */
    public int diamondMultiplier() {
        if (tripleActive()) return plugin.getConfig().getInt("fever.diamond.triple-multiplier", 3);
        if (diamondFeverActive()) return plugin.getConfig().getInt("fever.diamond.multiplier", 2);
        return 1;
    }

    /** 룰렛 연출 시간 배율 (피버 중 빨라짐) */
    public double spinSpeedFactor() {
        if (tripleActive()) return plugin.getConfig().getDouble("fever.diamond.triple-spin-factor", 0.35);
        if (diamondFeverActive()) return plugin.getConfig().getDouble("fever.diamond.spin-factor", 0.5);
        return 1.0;
    }

    /** 상자 내용물 배수 */
    public int boxMultiplier() { return boxFeverActive() ? plugin.getConfig().getInt("fever.box.multiplier", 2) : 1; }

    public void startDiamond(int seconds) {
        if (seconds <= 0) seconds = plugin.getConfig().getInt("fever.diamond.seconds", 300);
        long now = System.currentTimeMillis();
        diamondUntil = Math.max(diamondUntil, now) + seconds * 1000L;
        int left = (int) ((diamondUntil - now) / 1000);
        if (Rng.chance(plugin.getConfig().getDouble("fever.diamond.triple-chance", 15))) {
            tripleUntil = diamondUntil;   // 3배는 이번 피버 끝까지
            announce("&d3배 피버 터짐!!", "&f" + Txt.duration(left) + " 동안 룰렛 범위 3배", "fever_triple", left);
        } else {
            announce("&c2배 피버!", "&f" + Txt.duration(left) + " 동안 룰렛 범위 2배", "fever_diamond", left);
        }
    }

    public void startBox(int seconds) {
        if (seconds <= 0) seconds = plugin.getConfig().getInt("fever.box.seconds", 300);
        long now = System.currentTimeMillis();
        boxUntil = Math.max(boxUntil, now) + seconds * 1000L;
        announce("&6상자 피버!", "&f" + Txt.duration(seconds) + " 동안 상자 내용물 1+1", "fever_box", seconds);
    }

    public void stopAll() { diamondUntil = tripleUntil = boxUntil = 0; pushAll(); }

    private int tickCount = 0;

    private void tick() {
        tickCount++;
        if (tickCount % 5 == 0) pushAll();
    }

    private void announce(String title, String sub, String fx, int seconds) {
        plugin.announce("&8[&e피버&8] " + title + " &7" + Txt.strip(Txt.color(sub)), true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.ui().center(p, title, sub);
            plugin.ui().fx(p, fx, Json.obj().put("seconds", seconds));
            p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1f, 1.2f);
        }
        pushAll();
    }

    public Json state() {
        long now = System.currentTimeMillis();
        return Json.obj()
                .put("diamond", Math.max(0, (diamondUntil - now) / 1000))
                .put("triple", tripleActive() ? Math.max(0, (tripleUntil - now) / 1000) : 0)
                .put("box", Math.max(0, (boxUntil - now) / 1000));
    }

    private void pushAll() {
        Json s = state();
        for (Player p : Bukkit.getOnlinePlayers()) plugin.ui().update(p, "fever", s);
    }

    public void shutdown() { if (ticker != null) ticker.cancel(); }
}
