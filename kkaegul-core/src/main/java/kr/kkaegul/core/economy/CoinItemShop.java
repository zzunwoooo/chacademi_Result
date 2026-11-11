package kr.kkaegul.core.economy;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 상점 "아이템" 탭: 깨굴코인으로 귀환권·인벤세이브권 같은 자주 쓰는 아이템 사기.
 * config economy.coin-items: [{ id: return_ticket, price: 15 }, ...] (id = 깨굴 아이템 id, price = 코인)
 */
public final class CoinItemShop {
    private final KkaegulCore plugin;

    CoinItemShop(KkaegulCore plugin) { this.plugin = plugin; }

    private record Offer(CustomItem type, long price) {}

    private List<Offer> offers() {
        List<Offer> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("economy.coin-items")) {
            CustomItem t = CustomItem.byId(String.valueOf(m.get("id")));
            Object pr = m.get("price");
            if (t == null || !(pr instanceof Number n) || n.longValue() <= 0) continue;
            out.add(new Offer(t, n.longValue()));
        }
        return out;
    }

    /** 상점 창 데이터 coinItems: [{id,name,icon,price,amount}] */
    public List<Object> info() {
        List<Object> out = new ArrayList<>();
        for (Offer o : offers()) {
            String[] l = CropShop.describe(plugin.items().create(o.type(), 1), o.type().id);
            out.add(Json.obj().put("id", o.type().id).put("name", l[0]).put("icon", l[1]).put("price", o.price()).put("amount", 1));
        }
        return out;
    }

    public void buy(Player p, String id, long amount) {
        if (amount <= 0) return;
        Offer o = null;
        for (Offer x : offers()) if (x.type().id.equals(id)) o = x;
        if (o == null) { plugin.ui().error(p, "&c상점에 없는 아이템이에요."); return; }
        amount = Math.min(64, amount);
        long cost = o.price() * amount;
        var d = plugin.data().get(p);
        if (d.coins < cost) { plugin.ui().error(p, "&c깨굴코인이 부족해요. &7(필요 " + Txt.num(cost) + "개, 보유 " + Txt.num(d.coins) + "개)"); return; }
        d.coins -= cost;
        d.markDirty();
        ItemStack it = plugin.items().create(o.type(), (int) amount);
        plugin.mail().giveOrMail(p, it);
        plugin.hud().refresh(p);
        p.sendMessage(Txt.color(Txt.notice("&a" + CropShop.describe(it, o.type().id)[0] + " " + amount + "개 구매 &7(깨굴코인 -" + Txt.num(cost) + ")")));
        p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.2f);
    }
}
