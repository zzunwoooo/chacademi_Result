package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 로켓 발사 뒤 물낙하 (11/23 오너: 겉날개 대신 양동이를 쥐여 주고 물낙하기).
 * 꼭대기에서 지금 들고 있는 칸에 "착지용 물 양동이"를 쥐여 준다 (원래 그 칸 물건은 맡아 둔다). 땅에 닿기 직전 물을 부으면 산다.
 * 착지하면 양동이를 거두고 원래 물건을 그 칸에 돌려주고, 부은 물은 2초 뒤 치운다. 못 부으면 낙하 피해 그대로.
 * 죽거나 나가도 원래 물건은 돌아온다. 서버가 갑자기 꺼지면 플레이어 자료에 적어 둔 것으로 접속 때 되돌린다.
 */
final class Mlg implements Listener {
    private static final class Run { int slot; ItemStack saved; Location water; boolean emptied; }

    private final DiamondPlugin pl;
    private final NamespacedKey bucketKey, savedKey, slotKey;
    private final Map<UUID, Run> runs = new HashMap<>();

    Mlg(DiamondPlugin pl) {
        this.pl = pl;
        this.bucketKey = new NamespacedKey(pl, "mlg_bucket");
        this.savedKey = new NamespacedKey(pl, "mlg_saved_item");
        this.slotKey = new NamespacedKey(pl, "mlg_saved_slot");
    }

    private boolean isOurs(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(bucketKey, PersistentDataType.BYTE);
    }

    /** 로켓 꼭대기에서 부른다 */
    void give(Player p) {
        UUID id = p.getUniqueId();
        if (runs.containsKey(id)) finish(p);
        Run r = new Run();
        r.slot = p.getInventory().getHeldItemSlot();
        ItemStack old = p.getInventory().getItem(r.slot);
        r.saved = old == null || old.getType().isAir() ? null : old.clone();
        runs.put(id, r);
        p.getPersistentDataContainer().set(slotKey, PersistentDataType.INTEGER, r.slot);
        p.getPersistentDataContainer().set(savedKey, PersistentDataType.BYTE_ARRAY, r.saved == null ? new byte[0] : r.saved.serializeAsBytes());

        ItemStack b = new ItemStack(Material.WATER_BUCKET);
        ItemMeta m = b.getItemMeta();
        m.displayName(Component.text("착지용 물 양동이", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(bucketKey, PersistentDataType.BYTE, (byte) 1);
        b.setItemMeta(m);
        p.getInventory().setItem(r.slot, b);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0.2, 0));
        pl.net.toast(p, "땅에 닿기 직전에 물 양동이를 부으세요!", true);

        new BukkitRunnable() {
            int t = 0, still = 0;
            @Override public void run() {
                Player q = Bukkit.getPlayer(id);
                if (q == null || runs.get(id) != r) { cancel(); return; }
                t++;
                boolean down = t > 20 && (q.isOnGround() || q.isInWater() || q.isInLava());
                still = down ? still + 1 : 0;
                if (still >= 10 || t > 20 * 120) { finish(q); cancel(); }      // 착지하고 0.5초 뒤 거둔다
            }
        }.runTaskTimer(pl, 1L, 1L);
    }

    /** 양동이를 거두고 원래 물건을 돌려준다 */
    void finish(Player p) {
        Run r = runs.remove(p.getUniqueId());
        if (r == null) return;
        p.getPersistentDataContainer().remove(savedKey);
        p.getPersistentDataContainer().remove(slotKey);
        clearBuckets(p, r);
        restore(p, r.slot, r.saved);
        if (r.water != null) {
            Location w = r.water;
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (w.getBlock().getType() == Material.WATER) w.getBlock().setType(Material.AIR); }, 40L);
        }
    }

    private void clearBuckets(Player p, Run r) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (isOurs(c[i])) p.getInventory().setItem(i, null);
        ItemStack in = p.getInventory().getItem(r.slot);       // 물을 부어서 빈 양동이가 된 것
        if (r.emptied && in != null && (in.getType() == Material.BUCKET || in.getType() == Material.WATER_BUCKET)) p.getInventory().setItem(r.slot, null);
    }

    private static void restore(Player p, int slot, ItemStack saved) {
        if (saved == null || saved.getType().isAir()) return;
        ItemStack now = p.getInventory().getItem(slot);
        if (now == null || now.getType().isAir()) p.getInventory().setItem(slot, saved);
        else Items.give(p, saved);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r == null || !isOurs(e.getPlayer().getInventory().getItem(e.getHand()))) return;
        r.emptied = true;
        r.water = e.getBlock().getLocation();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (isOurs(e.getCurrentItem()) || isOurs(e.getCursor())) e.setCancelled(true);
        if (e.getHotbarButton() >= 0 && e.getWhoClicked() instanceof Player p && isOurs(p.getInventory().getItem(e.getHotbarButton()))) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (isOurs(e.getItemDrop().getItemStack())) e.setCancelled(true); }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { if (isOurs(e.getMainHandItem()) || isOurs(e.getOffHandItem())) e.setCancelled(true); }

    /** 물낙하에 실패해 죽으면: 양동이는 떨어뜨리지 않고, 원래 물건은 다른 물건처럼 떨어지거나(인벤세이브권이면) 제자리로 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Run r = runs.remove(p.getUniqueId());
        if (r == null) return;
        p.getPersistentDataContainer().remove(savedKey);
        p.getPersistentDataContainer().remove(slotKey);
        if (e.getKeepInventory()) {
            clearBuckets(p, r);
            restore(p, r.slot, r.saved);
            return;
        }
        e.getDrops().removeIf(this::isOurs);
        if (r.emptied) {
            for (ItemStack d : new ArrayList<>(e.getDrops())) if (d.getType() == Material.BUCKET && d.getAmount() == 1) { e.getDrops().remove(d); break; }
        }
        if (r.saved != null) e.getDrops().add(r.saved);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { finish(e.getPlayer()); }

    /** 서버가 갑자기 꺼져 남은 양동이를 거두고 원래 물건을 돌려준다 */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        byte[] raw = p.getPersistentDataContainer().get(savedKey, PersistentDataType.BYTE_ARRAY);
        Integer slot = p.getPersistentDataContainer().get(slotKey, PersistentDataType.INTEGER);
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) if (isOurs(c[i])) p.getInventory().setItem(i, null);
        if (raw == null) return;
        p.getPersistentDataContainer().remove(savedKey);
        p.getPersistentDataContainer().remove(slotKey);
        if (raw.length == 0) return;
        try {
            restore(p, slot == null ? 0 : slot, ItemStack.deserializeBytes(raw));
        } catch (RuntimeException ex) {
            pl.getLogger().warning("물낙하 전 손 아이템을 되돌리지 못함 (" + p.getName() + "): " + ex);
        }
    }

    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) finish(p);
    }
}
