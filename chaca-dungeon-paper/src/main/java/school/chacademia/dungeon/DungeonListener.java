package school.chacademia.dungeon;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** 던전 월드 규칙 (블록 보호·아이템 유지·떨구기 금지) + 판 진행에 필요한 이벤트를 DungeonService 로 넘긴다. */
final class DungeonListener implements Listener {
    private final DungeonService service;
    DungeonListener(DungeonService service) { this.service = service; }

    private boolean protectedHere(Player p) {
        return service.dungeonOfWorld(p.getWorld()) != null && !p.hasPermission("chacademi.dungeon.build");
    }

    @EventHandler(ignoreCancelled = true)
    void breakBlock(BlockBreakEvent e) { if (protectedHere(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    void placeBlock(BlockPlaceEvent e) { if (protectedHere(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    void bucketEmpty(PlayerBucketEmptyEvent e) { if (protectedHere(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    void bucketFill(PlayerBucketFillEvent e) { if (protectedHere(e.getPlayer())) e.setCancelled(true); }

    /** 던전에서 떨군 아이템은 칸 정리 때 지워지므로 떨구지 못하게 한다. */
    @EventHandler(ignoreCancelled = true)
    void drop(PlayerDropItemEvent e) { if (protectedHere(e.getPlayer())) e.setCancelled(true); }

    /** 던전 월드에서는 죽어도 아이템·경험치 유지 (오너 결정). 진행 중이면 실패 처리. */
    @EventHandler(priority = EventPriority.HIGHEST)
    void death(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (service.dungeonOfWorld(p.getWorld()) == null && service.run(p.getUniqueId()) == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        service.died(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    void respawn(PlayerRespawnEvent e) {
        var to = service.respawn(e.getPlayer());
        if (to != null) e.setRespawnLocation(to);
        service.respawned(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void join(PlayerJoinEvent e) { service.join(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    void quit(PlayerQuitEvent e) { service.quit(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    void world(PlayerChangedWorldEvent e) { service.sendPortals(e.getPlayer()); }

    /** 봉인된 방에서 걸어서 나가기 막기. */
    @EventHandler(ignoreCancelled = true)
    void move(PlayerMoveEvent e) {
        if (!e.hasChangedBlock() || e.getTo() == null) return;
        if (service.blockMove(e.getPlayer(), e.getTo())) {
            var back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void teleport(PlayerTeleportEvent e) {
        if (service.teleport(e.getPlayer(), e.getTo())) e.setCancelled(true);
    }

    /** 던전 안에서는 배고픔으로 차는 체력 자동 회복을 막는다 (물약·마법 회복은 그대로, 마나와 무관). 던전마다 natural-regen. */
    @EventHandler(ignoreCancelled = true)
    void regen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        var reason = e.getRegainReason();
        if (reason != EntityRegainHealthEvent.RegainReason.SATIATED && reason != EntityRegainHealthEvent.RegainReason.REGEN) return;
        var d = service.dungeonOfWorld(p.getWorld());
        if (d != null && !d.naturalRegen) e.setCancelled(true);
    }

    /** 가장 먼저 (MythicMobs 가 몹 정보를 지우기 전에) 이름을 읽는다. */
    @EventHandler(priority = EventPriority.LOWEST)
    void mobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        service.mobDied(e.getEntity(), e.getEntity().getKiller());
    }
}
