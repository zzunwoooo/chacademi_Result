package kr.kkaegul.core.item;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 우편함: 아이템을 보관했다가 받기. 인벤토리가 꽉 찼을 때의 안전망도 겸한다. */
public final class MailService {
    private final KkaegulCore plugin;

    public MailService(KkaegulCore plugin) { this.plugin = plugin; }

    public static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }

    public static ItemStack decode(String s) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 우편함으로 보낸다 (온라인이면 알림) */
    public void send(UUID to, ItemStack item, String reason) {
        if (item == null || item.getAmount() <= 0) return;
        PlayerData d = plugin.data().get(to);
        d.mail.add(encode(item));
        d.markDirty();
        Player p = Bukkit.getPlayer(to);
        if (p != null) {
            plugin.msg(p, "mail.arrived", "reason", reason == null ? "" : reason);
            plugin.ui().update(p, "mail", Json.obj().put("count", d.mail.size()));
        }
    }

    /** 인벤토리에 넣고 남는 것은 우편함으로 */
    public void giveOrMail(Player p, ItemStack item) {
        if (item == null || item.getAmount() <= 0) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack l : left.values()) send(p.getUniqueId(), l, "인벤토리가 가득 차서 우편함으로 보냈어요");
    }

    public int count(UUID id) { return plugin.data().get(id).mail.size(); }

    public List<ItemStack> list(UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : plugin.data().get(id).mail) {
            ItemStack it = decode(s);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** 들어가는 만큼 받기. 받은 개수 반환 */
    public int claimAll(Player p) {
        PlayerData d = plugin.data().get(p);
        int got = 0;
        Iterator<String> itr = d.mail.iterator();
        List<String> keep = new ArrayList<>();
        while (itr.hasNext()) {
            String s = itr.next();
            ItemStack it = decode(s);
            itr.remove();
            if (it == null) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (left.isEmpty()) { got++; continue; }
            for (ItemStack l : left.values()) keep.add(encode(l));
            // 인벤 꽉 참: 남은 것은 그대로 둔다
            while (itr.hasNext()) { keep.add(itr.next()); itr.remove(); }
            break;
        }
        d.mail.addAll(keep);
        d.markDirty();
        if (got > 0) plugin.msg(p, "mail.claimed", "count", got);
        if (!keep.isEmpty()) plugin.ui().error(p, "&c인벤토리가 가득 차서 " + keep.size() + "개는 우편함에 남았어요.");
        if (got == 0 && keep.isEmpty()) plugin.msg(p, "mail.empty");
        return got;
    }

    public Json snapshot(UUID id) {
        List<Object> items = new ArrayList<>();
        for (ItemStack it : list(id)) {
            String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? Txt.strip(it.getItemMeta().getDisplayName()) : it.getType().name();
            items.add(Json.obj().put("name", name).put("amount", it.getAmount()).put("material", it.getType().name()));
        }
        return Json.obj().put("items", items);
    }
}
