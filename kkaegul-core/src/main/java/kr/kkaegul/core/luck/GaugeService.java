package kr.kkaegul.core.luck;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * 운빨 게이지 0~99 — 서버 공용 (모두가 같은 게이지, global.yml 에 저장).
 * 누가 올리거나 내리면 모든 접속자 HUD 에 바로 반영된다.
 * 구간(config gauge.tiers):
 *   0~40  : 다이아 원석 채굴 시 다이아 1~2개 추가 드랍
 *   41~51 : 다이아 블록 드랍(확률)
 *   52~91 : 가챠 파괴확률 하락 + 증가룰렛 1+1
 *   92~99 : 가챠 파괴확률 하락 + 높은 인챈트 확률 증가 + 증가룰렛 1+2
 * PlayerData 를 받는 메서드는 예전 호출부 호환용 (값은 공용).
 */
public final class GaugeService {
    private final KkaegulCore plugin;

    public GaugeService(KkaegulCore plugin) { this.plugin = plugin; }

    public int max() { return plugin.getConfig().getInt("gauge.max", 99); }

    /** 현재 공용 게이지 */
    public int value() {
        if (plugin.global().gauge < 0) { plugin.global().gauge = Math.max(0, Math.min(max(), plugin.getConfig().getInt("gauge.start", 50))); plugin.global().dirty = true; }
        return plugin.global().gauge;
    }

    public int get(PlayerData d) { return value(); }

    public int set(int v) {
        int old = value();
        int nv = Math.max(0, Math.min(max(), v));
        plugin.global().gauge = nv;
        plugin.global().dirty = true;
        if (nv != old) {
            Json snap = snapshot();
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.ui().update(p, "gauge", snap);
                plugin.hud().refresh(p);
            }
        }
        return nv;
    }

    public int set(PlayerData d, int v) { return set(v); }

    public int add(int delta) { return set(value() + delta); }

    public int add(PlayerData d, int delta) { return add(delta); }

    /** 누가 게이지를 바꿨는지 모두에게 알림 (공용 게이지라서) */
    public void announce(Player by, int delta) {
        String msg = "&6[운빨 게이지] &f" + plugin.displayName(by) + "&7님이 " + (delta >= 0 ? "&a+" : "&c") + delta + " &7→ 현재 &e" + value();
        for (Player p : Bukkit.getOnlinePlayers()) plugin.ui().actionbar(p, msg);
    }

    /** 현재 구간 섹션 */
    public ConfigurationSection tier() {
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("gauge.tiers");
        if (tiers == null) return null;
        int g = value();
        for (String k : tiers.getKeys(false)) {
            ConfigurationSection t = tiers.getConfigurationSection(k);
            if (t == null) continue;
            if (g >= t.getInt("from", 0) && g <= t.getInt("to", 99)) return t;
        }
        return null;
    }

    public ConfigurationSection tier(PlayerData d) { return tier(); }

    public String tierName() { ConfigurationSection t = tier(); return t == null ? "" : t.getName(); }
    public String tierName(PlayerData d) { return tierName(); }

    public int rouletteExtra(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 0 : t.getInt("roulette-extra", 0); }
    /** 가챠 파괴확률 배율 (1.0 = 그대로) */
    public double destroyFactor(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 1.0 : t.getDouble("destroy-factor", 1.0); }
    /** 높은 인챈트 수치 가중치 배율 */
    public double highRollFactor(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 1.0 : t.getDouble("high-roll-factor", 1.0); }
    public int bonusDiamondMin(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 0 : t.getInt("bonus-diamond-min", 0); }
    public int bonusDiamondMax(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 0 : t.getInt("bonus-diamond-max", 0); }
    public double blockChance(PlayerData d) { ConfigurationSection t = tier(); return t == null ? 0 : t.getDouble("diamond-block-chance", 0); }

    public Json snapshot() {
        return Json.obj().put("value", value()).put("max", max()).put("tier", tierName()).put("shared", true);
    }

    public Json snapshot(PlayerData d) { return snapshot(); }
}
