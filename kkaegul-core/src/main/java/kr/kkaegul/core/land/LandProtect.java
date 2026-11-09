package kr.kkaegul.core.land;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.land.LandService.Claim;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 땅 보호: 주인·허용 명단만. 폭발·불·피스톤·흘러드는 물/용암은 다른 주인의 땅으로 못 들어감 */
final class LandProtect implements Listener {
    private final KkaegulCore plugin;
    private final LandService land;
    private final Map<UUID, Long> warned = new HashMap<>();

    LandProtect(KkaegulCore plugin, LandService land) { this.plugin = plugin; this.land = land; }

    private boolean deny(Player p, Claim c) {
        if (land.can(p, c)) return false;
        long now = System.currentTimeMillis();
        Long last = warned.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            warned.put(p.getUniqueId(), now);
            plugin.ui().error(p, "&c" + land.ownerName(c.owner()) + "님의 땅이에요.");
        }
        return true;
    }

    /** 마을 월드에서는 안 팔린 땅·도로도 막음 */
    private boolean deny(Player p, Block b) {
        Claim c = land.at(b);
        if (c == null && b != null && land.town().isTown(b.getWorld().getName()) && !land.bypass(p)) {
            long now = System.currentTimeMillis();
            Long last = warned.get(p.getUniqueId());
            if (last == null || now - last > 1500) { warned.put(p.getUniqueId(), now); plugin.ui().error(p, "&c마을에서는 내 땅에서만 지을 수 있어요."); }
            return true;
        }
        return deny(p, c);
    }

    private boolean townFree(Block b) { return land.at(b) == null && land.town().isTown(b.getWorld().getName()); }

    private UUID owner(Block b) { Claim c = land.at(b); return c == null ? null : c.owner(); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (deny(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (deny(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean touch = switch (e.getAction()) {
            case PHYSICAL -> true;
            case RIGHT_CLICK_BLOCK -> LandService.interactable(b.getType());
            default -> false;
        };
        if (touch && deny(e.getPlayer(), b)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (deny(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (deny(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    private static boolean protectedEntity(Entity t) {
        if (t instanceof Player || t instanceof Monster) return false;
        return true;   // 동물·주민·액자·갑옷 거치대 등
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!protectedEntity(t) || plugin.npcs().boundAction(t) != null) return;
        String n = t.getType().name();
        if (!(n.contains("FRAME") || n.contains("ARMOR_STAND") || n.contains("PAINTING"))) return;   // 동물 먹이 주기 등은 허용
        if (deny(e.getPlayer(), land.at(t))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player p = attacker(e.getDamager());
        if (p == null || !protectedEntity(e.getEntity())) return;
        if (deny(p, land.at(e.getEntity()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        Claim c = land.at(e.getEntity());
        if (p == null) { if (c != null) e.setCancelled(true); return; }
        if (deny(p, c)) e.setCancelled(true);
    }

    private static Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // ───────── 바깥에서 들어오는 것 ─────────
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> land.at(b) != null || townFree(b)); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> land.at(b) != null || townFree(b)); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (land.at(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (land.at(e.getBlock()) != null && e.getSource() != null && e.getSource().getType().name().contains("FIRE")) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (land.at(e.getBlock()) == null) return;
        if (e.getPlayer() != null) { if (deny(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
        else e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        UUID to = owner(e.getToBlock());
        if ((to != null || townFree(e.getToBlock())) && !Objects.equals(to, owner(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        UUID from = owner(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (!Objects.equals(from, owner(b)) || !Objects.equals(from, owner(b.getRelative(e.getDirection())))) { e.setCancelled(true); return; }
        }
        if (!Objects.equals(from, owner(e.getBlock().getRelative(e.getDirection())))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        UUID from = owner(e.getBlock());
        for (Block b : e.getBlocks()) if (!Objects.equals(from, owner(b))) { e.setCancelled(true); return; }
    }
}
