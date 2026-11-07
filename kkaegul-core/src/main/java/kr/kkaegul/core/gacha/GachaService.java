package kr.kkaegul.core.gacha;

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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 곡괭이 가챠샵 (깨굴코인) — 3칸 슬롯.
 * 기계 1/2/3 = "555" / "999" / "151515": 행운·효율·내구성이 각각 1~5 / 1~9 / 1~15 중에서 뽑힌다.
 * 돌릴 때마다 세 칸이 기계 범위(1번 1~5, 2번 1~9, 3번 1~15) 안의 숫자로 새로 정해진다 (더하지 않고 바뀜).
 * 세 칸 모두 그 기계 최대(5/5/5, 9/9/9, 15/15/15)면 잭팟(개구리 축하). 숫자별 확률은 machines.N.levels 가중치.
 * 파괴: 2·3번 기계. 가챠 파괴 방지권이 있으면 1장 자동 소모. 운빨 게이지 52+ 면 파괴확률 감소,
 * 92+ 면 높은 숫자가 더 잘 나온다.
 */
public final class GachaService {
    private final KkaegulCore plugin;
    private final Pickaxe pick;

    public GachaService(KkaegulCore plugin) {
        this.plugin = plugin;
        this.pick = new Pickaxe(plugin);
    }

    public Pickaxe pickaxe() { return pick; }

    public ConfigurationSection machine(String id) { return plugin.getConfig().getConfigurationSection("gacha.machines." + id); }

    public Json info(Player p, String id) {
        ConfigurationSection m = machine(id);
        ItemStack hand = p.getInventory().getItemInMainHand();
        Json stats = Json.obj();
        for (String s : Pickaxe.STATS) stats.put(s, pick.get(hand, s));
        PlayerData d = plugin.data().get(p);
        return Json.obj().put("machine", id).put("label", m == null ? id : m.getString("label", id))
                .put("max", m == null ? 0 : m.getInt("max")).put("cost", m == null ? 0 : m.getInt("cost"))
                .put("destroyChance", m == null ? 0 : m.getDouble("destroy-chance") * plugin.gauge().destroyFactor(d))
                .put("coins", d.coins).put("holdingPickaxe", Pickaxe.isPickaxe(hand)).put("stats", stats)
                .put("protectTickets", countProtect(p))
                .put("tips", Json.obj()
                        .put("fortune", Txt.color("&a행운\n&f다이아 원석에서 나오는 다이아가 늘어요"))
                        .put("efficiency", Txt.color("&e효율\n&f채굴 속도가 빨라져요"))
                        .put("unbreaking", Txt.color("&b내구성\n&f곡괭이가 잘 닳지 않아요\n&7(파괴 방지와는 달라요)"))
                        .put("destroy", Txt.color("&c파괴확률\n&f돌릴 때 곡괭이가 부서질 확률\n&7운빨 게이지 52 이상이면 줄어요"))
                        .put("protect", Txt.color("&d파괴 방지권\n&f부서지는 순간 1장 자동 사용\n&7고급상자 등에서 획득"))
                        .put("cost", Txt.color("&e1회 비용 · 깨굴코인")));
    }

    public void spin(Player p, String id) {
        ConfigurationSection m = machine(id);
        if (m == null) { plugin.ui().error(p, "&c가챠 기계가 없어요: " + id); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!Pickaxe.isPickaxe(hand)) { plugin.msg(p, "gacha.need-pickaxe"); return; }
        PlayerData d = plugin.data().get(p);
        int cost = m.getInt("cost", 5);
        if (d.coins < cost) { plugin.msg(p, "gacha.no-coins", "cost", cost, "coins", d.coins); return; }
        int max = Math.max(1, m.getInt("max", 5));
        // 세 칸이 모두 이 기계 최대면 더 돌려도 내려가기만 함 → 막음. (max 를 "넘는" 칸이 있는 곡괭이는 돌릴 수 있어야 함:
        //  예전엔 9/15/9 처럼 max 이상이면 창에서 막혀서 영영 못 돌렸다)
        if (pick.get(hand, "fortune") == max && pick.get(hand, "efficiency") == max && pick.get(hand, "unbreaking") == max) {
            plugin.ui().error(p, "&c이미 이 기계 최대(" + max + "/" + max + "/" + max + ")예요. 다른 기계를 써 주세요.");
            return;
        }

        d.coins -= cost;
        d.gachaSpins++;
        d.markDirty();

        // 파괴 판정
        double destroy = m.getDouble("destroy-chance", 0) * plugin.gauge().destroyFactor(d);
        if (destroy > 0 && Rng.chance(destroy)) {
            if (consumeProtect(p)) {
                plugin.ui().fx(p, "gacha_protect", Json.obj().put("machine", id));
                if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&d파괴 방지권 발동!", "&f곡괭이를 지켰어요");
                plugin.ui().update(p, "gacha", info(p, id));
            } else {
                p.getInventory().setItemInMainHand(null);
                d.pickaxeBreaks++;
                d.markDirty();
                plugin.ui().fx(p, "gacha_destroy", Json.obj().put("machine", id));
                if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&4곡괭이 파괴!", "&f" + m.getString("label", id));
                plugin.ui().update(p, "gacha", info(p, id));
                p.playSound(p.getLocation(), "minecraft:entity.item.break", 1f, 0.8f);
                if (plugin.getConfig().getBoolean("gacha.broadcast-destroy", true))
                    plugin.announce("&4[가챠] &f" + plugin.displayName(p) + "님의 곡괭이가 파괴되었습니다...", false);
                plugin.titles().check(d);
                plugin.hud().refresh(p);
                return;
            }
        }

        // 세 칸 뽑기 → 나온 숫자로 바로 바뀜 (더하지 않음). 기계마다 1 ~ max 까지만
        int[] before = new int[3], after = new int[3], delta = new int[3];
        for (int i = 0; i < 3; i++) {
            before[i] = pick.get(hand, Pickaxe.STATS[i]);
            after[i] = rollLevel(m, max, d);
            delta[i] = after[i] - before[i];
            pick.set(hand, Pickaxe.STATS[i], after[i]);
        }
        p.getInventory().setItemInMainHand(hand);
        boolean jackpot = after[0] == max && after[1] == max && after[2] == max;   // 세 칸 모두 그 기계 최대
        String result = after[0] + "/" + after[1] + "/" + after[2];

        Json fx = Json.obj().put("machine", id).put("max", max).put("before", before).put("after", after).put("delta", delta)
                .put("fortune", after[0]).put("efficiency", after[1]).put("unbreaking", after[2]).put("jackpot", jackpot);
        plugin.ui().fx(p, jackpot ? "gacha_jackpot" : "gacha_result", fx);
        if (jackpot) {
            if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&6잭팟!!! " + result, "&f행운·효율·내구성 모두 최대 &a(깨굴이 축하중)");
            plugin.announce("&6[잭팟] &f" + plugin.displayName(p) + "님이 " + m.getString("label", id) + "에서 &e" + result + "&f 달성!", true);
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
        } else {
            if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&e" + result, "&7행운 / 효율 / 내구성");
            p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 1f, 1.5f);
        }
        plugin.titles().onGachaRoll(d, after[0], after[1], after[2]);
        plugin.ui().update(p, "gacha", info(p, id));
        plugin.hud().refresh(p);
    }

    /**
     * 한 칸 뽑기. config levels: {"1": 30, "2": 25, ...} 가 있으면 그 가중치, 없으면 1~max 균등.
     * 게이지 92+ 면 위쪽 절반 숫자의 가중치 ×high-roll-factor.
     */
    private static String sign(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /**
     * 한 칸 변화량: -max ~ +max (0 제외). config delta-weights: {"-5": 10, ..., "5": 10} 가 있으면 그 가중치, 없으면 균등.
     * 게이지 92+ 면 플러스 쪽 가중치 ×high-roll-factor.
     */
    private int rollDelta(ConfigurationSection m, int max, PlayerData d, int before, int cap) {
        ConfigurationSection dw = m.getConfigurationSection("delta-weights");
        double hf = plugin.gauge().highRollFactor(d);
        // 졸업 난이도: 올라간 뒤 단계가 soft 를 넘는 만큼 decay 를 거듭 곱함 (높을수록 더 안 오름)
        int soft = plugin.getConfig().getInt("gacha.climb.soft", 10);
        double decay = plugin.getConfig().getDouble("gacha.climb.decay", 0.9);
        Map<Integer, Double> w = new LinkedHashMap<>();
        for (int v = -max; v <= max; v++) {
            if (v == 0) continue;
            double weight = dw == null ? 1.0 : dw.getDouble(String.valueOf(v), 0);
            if (v > 0) weight *= hf * Math.pow(decay, Math.max(0, Math.min(cap, before + v) - soft));
            if (weight > 0) w.put(v, weight);
        }
        Integer pickV = Rng.weighted(w);
        return pickV == null ? 1 : pickV;
    }

    private int rollLevel(ConfigurationSection m, int max, PlayerData d) {
        ConfigurationSection lv = m.getConfigurationSection("levels");
        double hf = plugin.gauge().highRollFactor(d);
        Map<Integer, Double> w = new LinkedHashMap<>();
        for (int v = 1; v <= max; v++) {
            double weight = lv == null ? 1.0 : lv.getDouble(String.valueOf(v), 0);
            if (v > max / 2.0 && v < max) weight *= hf;   // 게이지 잭팟: 높은 숫자↑ (최대값 자체는 그대로)
            if (weight > 0) w.put(v, weight);
        }
        Integer pickV = Rng.weighted(w);
        return pickV == null ? 1 : pickV;
    }

    private int countProtect(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (plugin.items().is(it, CustomItem.GACHA_PROTECT)) n += it.getAmount();
        return n;
    }

    public boolean consumeProtect(Player p) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (!plugin.items().is(it, CustomItem.GACHA_PROTECT)) continue;
            if (it.getAmount() > 1) { it.setAmount(it.getAmount() - 1); p.getInventory().setItem(i, it); }
            else p.getInventory().setItem(i, null);
            return true;
        }
        return false;
    }
}
