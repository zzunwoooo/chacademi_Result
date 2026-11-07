package kr.kkaegul.core.ui;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import org.bukkit.entity.Player;

/**
 * 메뉴 창 하나에 탭 4개: me(내 정보) / playtime(접속 보상) / titles(칭호) / hall(명예의 전당).
 * 렌더러가 menu 화면을 지원하면 여기로, 아니면 각 기능의 채팅 출력으로 대체된다.
 */
public final class MenuService {
    private final KkaegulCore plugin;

    public MenuService(KkaegulCore plugin) { this.plugin = plugin; }

    public Json me(Player p) {
        PlayerData d = plugin.data().get(p);
        return plugin.hud().snapshot(p).put("totalIncreased", d.totalIncreased).put("totalSubmitted", d.totalSubmitted)
                .put("totalDonated", d.totalDonated).put("jailCount", d.jailCount).put("pickaxeBreaks", d.pickaxeBreaks)
                .put("instantDeaths", d.instantDeaths).put("bestPickaxe", d.bestPickaxe)
                .put("name", plugin.nickname().display(d)).put("account", p.getName())
                .put("pickaxe", pickaxe(p))
                .put("fortuneBonus", plugin.npcs().fortune().snapshot(p.getUniqueId()));
    }

    private Json pickaxe(Player p) {
        Json j = Json.obj();
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        for (String s : kr.kkaegul.core.gacha.Pickaxe.STATS) j.put(s, plugin.gacha().pickaxe().get(hand, s));
        return j;
    }

    /** @return 렌더러로 열었으면 true */
    public boolean open(Player p, String tab) {
        PlayerData d = plugin.data().get(p);
        Json data = Json.obj().put("tab", tab)
                .put("me", me(p))
                .put("playtime", plugin.rewards().snapshot(d))
                .put("titles", plugin.titles().snapshot(d))
                .put("hall", plugin.ranking().snapshot());
        return plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "menu").put("data", data));
    }
}
