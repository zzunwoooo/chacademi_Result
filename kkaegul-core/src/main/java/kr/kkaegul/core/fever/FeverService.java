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
 *  - 다이아 증감 피버(섭주 1000): 룰렛이 빨라지고 1+1. 피버 중 일정 주기로 확률 체크해 3배 피버(1+2) 구간이 터진다.
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

    /** 룰렛 추가 회전 수: 피버 1, 3배 피버 2 */
    public int diamondExtra() {
        if (tripleActive()) return plugin.getConfig().getInt("fever.diamond.triple-extra", 2);
        if (diamondFeverActive()) return plugin.getConfig().getInt("fever.diamond.extra", 1);
        return 0;
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
        announce("&c다이아 증감 피버!", "&f" + Txt.duration(seconds) + " 동안 룰렛이 빨라지고 1+1", "fever_diamond", seconds);
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
        if (diamondFeverActive() && !tripleActive()) {
            int every = Math.max(1, plugin.getConfig().getInt("fever.diamond.triple-check-seconds", 30));
            if (tickCount % every == 0 && Rng.chance(plugin.getConfig().getDouble("fever.diamond.triple-chance", 15))) {
                int sec = plugin.getConfig().getInt("fever.diamond.triple-seconds", 30);
                tripleUntil = Math.min(diamondUntil, System.currentTimeMillis() + sec * 1000L);
                announce("&d3배 피버 터짐!!", "&f" + sec + "초 동안 룰렛 1+2", "fever_triple", sec);
            }
        }
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
