package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카드뽑기 (규칙 미정 → 임시 버전, 기본 꺼짐).
 * NPC 를 누르면 뒤집힌 카드 N장 화면이 열리고, 한 장 고르면 cards.outcomes 가중치로 결과가 정해진다.
 * 고르기 전에 결과를 미리 섞어 두므로 고르지 않은 카드도 공개할 수 있다(연출용).
 */
public final class CardDrawService {
    private final KkaegulCore plugin;
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();

    public CardDrawService(KkaegulCore plugin) { this.plugin = plugin; }

    private ConfigurationSection cfg() { return plugin.getConfig().getConfigurationSection("cards"); }

    public void open(Player p) {
        ConfigurationSection c = cfg();
        if (c == null || !c.getBoolean("enabled", false)) { p.sendMessage(Txt.color(Txt.notice("&7카드뽑기는 아직 준비 중이에요."))); return; }
        PlayerData d = plugin.data().get(p);
        int cost = c.getInt("cost-coins", 3);
        if (d.coins < cost) { plugin.msg(p, "gacha.no-coins", "cost", cost, "coins", d.coins); return; }
        d.coins -= cost;
        d.markDirty();
        int n = Math.max(2, Math.min(7, c.getInt("cards", 5)));
        Map<String, Double> w = new LinkedHashMap<>();
        ConfigurationSection oc = c.getConfigurationSection("outcomes");
        if (oc != null) for (String k : oc.getKeys(false)) w.put(k, oc.getDouble(k + ".weight", 1));
        List<String> deal = new ArrayList<>();
        for (int i = 0; i < n; i++) { String o = Rng.weighted(w); deal.add(o == null ? "miss" : o); }
        pending.put(p.getUniqueId(), deal);
        if (!plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "card_draw").put("data", Json.obj().put("cards", n).put("cost", cost)))) {
            pick(p, Rng.range(0, n - 1)); // 렌더러 없으면 바로 랜덤
        }
    }

    public void pick(Player p, int index) {
        List<String> deal = pending.remove(p.getUniqueId());
        if (deal == null) return;
        index = Math.max(0, Math.min(deal.size() - 1, index));
        String o = deal.get(index);
        ConfigurationSection oc = cfg() == null ? null : cfg().getConfigurationSection("outcomes." + o);
        String label = oc == null ? "꽝" : oc.getString("label", o);
        List<Object> reveal = new ArrayList<>();
        for (String s : deal) reveal.add(cfg() == null ? s : cfg().getString("outcomes." + s + ".label", s));
        plugin.ui().fx(p, "card_reveal", Json.obj().put("picked", index).put("label", label).put("all", reveal));
        plugin.ui().center(p, "&d" + label, "&f" + (index + 1) + "번 카드");
        if (oc != null) plugin.actions().runAll(p.getUniqueId(), oc.getStringList("actions"), "카드뽑기");
        if (oc != null && oc.getBoolean("rare", false))
            plugin.announce("&d[카드뽑기] &f" + plugin.displayName(p) + "님이 &e" + label + "&f 카드를 뽑았습니다!", true);
    }
}
