package kr.kkaegul.core.npc;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 오늘의 운세 NPC + 임시 행운/효율 보너스.
 *  - 하루 3번, 10분 버프(+1~+5) 또는 디버프(-1~-3), 멘트와 함께.
 *  - 버프 합계 상한 15 (config fortune.max-total-bonus).
 *  - 운세 결과에 따라 운빨 게이지도 오르내림(config fortune.gauge-delta).
 *  - 효율 보너스는 성급함(Haste), 마이너스는 채굴 피로로 표현. 행운 보너스는 다이아 드랍 계산에 더해짐.
 */
public final class FortuneService {
    private final KkaegulCore plugin;
    private final Map<UUID, List<Bonus>> bonuses = new ConcurrentHashMap<>();

    record Bonus(String stat, int amount, long until) {}

    public FortuneService(KkaegulCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void use(Player p) {
        PlayerData d = plugin.data().get(p);
        String today = plugin.clock().dayKey();
        if (!today.equals(d.fortuneDay)) { d.fortuneDay = today; d.fortuneUses = 0; }
        int max = plugin.getConfig().getInt("fortune.daily-uses", 3);
        if (d.fortuneUses >= max) { plugin.msg(p, "fortune.no-more", "max", max); return; }
        d.fortuneUses++;
        d.markDirty();

        boolean good = Rng.chance(plugin.getConfig().getDouble("fortune.good-chance", 60));
        ConfigurationSection c = plugin.getConfig().getConfigurationSection(good ? "fortune.buff" : "fortune.debuff");
        String stat = Rng.chance(50) ? "fortune" : "efficiency";
        int amt = c == null ? 1 : Rng.range(c.getInt("min", 1), c.getInt("max", good ? 5 : 3));
        if (!good) amt = -amt;
        int seconds = plugin.getConfig().getInt("fortune.seconds", 600);
        int applied = addTempBonus(p, stat, amt, seconds);
        List<String> lines = c == null ? List.of() : c.getStringList("messages");
        String line = lines.isEmpty() ? (good ? "오늘은 운이 좋아요!" : "오늘은 조심하세요...") : Rng.pick(lines);
        int gd = plugin.getConfig().getInt("fortune.gauge-delta." + (good ? "good" : "bad"), 0);
        if (gd != 0) plugin.gauge().add(d, gd);

        String statName = stat.equals("fortune") ? "행운" : "효율";
        Json fx = Json.obj().put("good", good).put("stat", stat).put("amount", applied).put("seconds", seconds)
                .put("message", line).put("usesLeft", max - d.fortuneUses).put("gaugeDelta", gd);
        if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "fortune").put("data", fx))) {
            plugin.ui().center(p, (good ? "&a" : "&c") + line, "&f" + statName + " " + (applied >= 0 ? "+" : "") + applied + " (" + Txt.duration(seconds) + ")");
        }
    }

    /** @return 실제 적용된 양(상한 적용 후) */
    public int addTempBonus(Player p, String stat, int amount, int seconds) {
        List<Bonus> list = bonuses.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        int cap = plugin.getConfig().getInt("fortune.max-total-bonus", 15);
        if (amount > 0) {
            int current = Math.max(0, bonus(p.getUniqueId(), "fortune")) + Math.max(0, bonus(p.getUniqueId(), "efficiency"));
            amount = Math.max(0, Math.min(amount, cap - current));
            if (amount == 0) return 0;
        }
        list.add(new Bonus(stat, amount, System.currentTimeMillis() + seconds * 1000L));
        applyPotions(p);
        return amount;
    }

    /** 임시 보너스 + 칭호 보너스 */
    public int bonus(UUID id, String stat) {
        int sum = 0;
        List<Bonus> list = bonuses.get(id);
        long now = System.currentTimeMillis();
        if (list != null) for (Bonus b : list) if (b.stat.equals(stat) && b.until > now) sum += b.amount;
        if (stat.equals("fortune")) sum += plugin.titles().fortuneBonus(plugin.data().get(id));
        return sum;
    }

    private void applyPotions(Player p) {
        int eff = bonus(p.getUniqueId(), "efficiency");
        p.removePotionEffect(PotionEffectType.HASTE);
        if (eff > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 60 * 15, Math.min(eff, 10) - 1, true, false, true));
        else if (eff < 0 && !p.hasPotionEffect(PotionEffectType.MINING_FATIGUE))
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 20 * 60 * 15, Math.min(-eff, 3) - 1, true, false, true));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, List<Bonus>> e : bonuses.entrySet()) {
            boolean changed = false;
            Iterator<Bonus> it = e.getValue().iterator();
            while (it.hasNext()) if (it.next().until <= now) { it.remove(); changed = true; }
            if (changed) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    applyPotions(p);
                    if (bonus(p.getUniqueId(), "efficiency") >= 0) p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                    plugin.ui().actionbar(p, "&7운세 효과가 끝났어요");
                }
            }
        }
    }

    public Json snapshot(UUID id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fortune", bonus(id, "fortune"));
        m.put("efficiency", bonus(id, "efficiency"));
        return Json.obj().put("bonus", m);
    }
}
