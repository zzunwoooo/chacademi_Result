package kr.kkaegul.core.diamond;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 보유 다이아 (다이아 화폐화).
 *  - 바닥의 다이아(블록 = 9개)를 주우면 인벤토리가 아니라 보유 다이아로 들어간다.
 *  - /인출 [개수] : 보유 다이아를 아이템으로 꺼냄 (인벤토리 빈 공간만큼)
 *  - /입금 : 인벤토리의 다이아를 전부 보유 다이아로 넣음 (상자에서 꺼낸 다이아 등)
 *  - 제출은 보유 다이아에서 먼저 빠지고, 모자라면 인벤토리 다이아를 쓴다.
 */
public final class WalletService implements Listener {
    private final KkaegulCore plugin;

    public WalletService(KkaegulCore plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("wallet.enabled", true); }

    /** 화폐로 바꿀 수 있는 순수 다이아/블록인지 → 다이아 개수 (아니면 0) */
    public long valueOf(ItemStack it) {
        if (it == null || it.hasItemMeta()) return 0; // 이름/인챈트 붙은 건 제외
        if (it.getType() == Material.DIAMOND) return it.getAmount();
        if (it.getType() == Material.DIAMOND_BLOCK && plugin.getConfig().getBoolean("wallet.blocks", true)) return it.getAmount() * 9L;
        return 0;
    }

    public void deposit(PlayerData d, long n, Player p) {
        if (n <= 0) return;
        d.held += n;
        d.markDirty();
        if (p != null) {
            plugin.ui().actionbar(p, "&b다이아 +" + Txt.num(n) + " &7(보유 " + Txt.num(d.held) + ")");
            plugin.hud().refresh(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!enabled() || !(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        long n = valueOf(item.getItemStack());
        if (n <= 0) return;
        e.setCancelled(true);
        item.remove();
        p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 0.4f, 1.6f);
        deposit(plugin.data().get(p), n, p);
    }

    /** 인벤토리가 가득 차도 다이아는 주워지게 (Paper: 가득 찼을 때도 이 이벤트는 옴) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttemptPickup(org.bukkit.event.player.PlayerAttemptPickupItemEvent e) {
        if (!enabled()) return;
        Item item = e.getItem();
        if (item == null || !item.isValid()) return;
        long n = valueOf(item.getItemStack());
        if (n <= 0) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        item.remove();
        p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 0.4f, 1.6f);
        deposit(plugin.data().get(p), n, p);
    }

    /** 보유 다이아에서 꺼내기. @return 실제로 꺼낸 개수 */
    public long withdraw(Player p, long want) {
        PlayerData d = plugin.data().get(p);
        if (d.held <= 0) { plugin.ui().error(p, "&c보유 다이아가 없어요."); return 0; }
        long n = want <= 0 ? d.held : Math.min(want, d.held);
        long room = roomForDiamonds(p.getInventory());
        n = Math.min(n, room);
        if (n <= 0) { plugin.ui().error(p, "&c인벤토리에 빈 공간이 없어요."); return 0; }
        d.held -= n;
        d.markDirty();
        long left = n;
        while (left > 0) {
            int stack = (int) Math.min(64, left);
            p.getInventory().addItem(new ItemStack(Material.DIAMOND, stack));
            left -= stack;
        }
        plugin.ui().center(p, "&b다이아 " + Txt.num(n) + "개 인출", "&f남은 보유 " + Txt.num(d.held));
        plugin.hud().refresh(p);
        return n;
    }

    /** 인벤토리 다이아(블록 포함) 전부 입금 */
    public long depositInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        long total = 0;
        for (int i = 0; i < 36; i++) {
            long v = valueOf(inv.getItem(i));
            if (v <= 0) continue;
            total += v;
            inv.setItem(i, null);
        }
        if (total <= 0) { p.sendMessage(Txt.color(Txt.notice("&7입금할 다이아가 없어요."))); return 0; }
        PlayerData d = plugin.data().get(p);
        deposit(d, total, p);
        plugin.ui().center(p, "&b다이아 " + Txt.num(total) + "개 입금", "&f보유 " + Txt.num(d.held));
        return total;
    }

    /** 보유 다이아에서 최대 n 개 사용. @return 사용한 개수 */
    public long spend(PlayerData d, long n) {
        long use = Math.min(Math.max(0, n), d.held);
        d.held -= use;
        d.markDirty();
        return use;
    }

    private long roomForDiamonds(PlayerInventory inv) {
        long room = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) room += 64;
            else if (it.getType() == Material.DIAMOND && !it.hasItemMeta()) room += 64 - it.getAmount();
        }
        return room;
    }
}
