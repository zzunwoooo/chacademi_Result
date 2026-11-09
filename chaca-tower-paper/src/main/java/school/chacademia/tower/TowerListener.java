package school.chacademia.tower;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

final class TowerListener implements Listener {
    private final TowerService service;

    TowerListener(TowerService service) { this.service = service; }

    private static Entity source(Entity damager) {
        return damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : damager;
    }

    /** 몹 → 플레이어: 층 배율·받는 피해 감소. 플레이어 → 몹: 주는 피해 증가·흡수. 가시 몹 반사. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    void onDamage(EntityDamageByEntityEvent e) {
        Entity src = source(e.getDamager());
        if (e.getEntity() instanceof Player victim && src != null && src.getScoreboardTags().contains("chaca_tower")) {
            service.mobHitsPlayer(e, victim, src);
        } else if (src instanceof Player attacker && e.getEntity().getScoreboardTags().contains("chaca_tower")) {
            service.playerHitsMob(e, attacker, e.getEntity());
        }
    }

    /** 쓰러짐: 깃털이 있으면 버티고, 없으면 판 끝 (죽지 않게 막고 밖으로). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void onFatal(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && service.inRun(p) && p.getHealth() - e.getFinalDamage() <= 0) service.fatal(e, p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onMobDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains("chaca_tower")) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        service.mobDied(e.getEntity());
    }

    /** 탑 안에서는 배고픔이 줄지 않는다 (무한). */
    @EventHandler(ignoreCancelled = true)
    void onFood(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && service.inRun(p)) { e.setCancelled(true); p.setFoodLevel(20); p.setSaturation(20); }
    }

    /** 탑 안에서는 배고픔으로 체력이 차지 않는다 (회복은 카드·물약·음식 효과로만). */
    @EventHandler(ignoreCancelled = true)
    void onRegen(EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof Player p && service.inRun(p)
                && (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof CardMenu)) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() == e.getInventory())
            service.menuClick(p, CardMenu.slotToChoice(e.getRawSlot()));
    }

    @EventHandler(ignoreCancelled = true)
    void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof CardMenu) e.setCancelled(true); }

    /** 보상 설정 창: 고르는 창은 잠그고, 편집 상자는 자유롭게 (닫을 때 저장). */
    @EventHandler(ignoreCancelled = true)
    void onRewardMenu(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Rewards.Menu menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        String target = menu.target(e.getRawSlot());
        if (target == null) return;
        var r = service.rewards;
        if (target.equals("guardian") && (e.isLeftClick() || e.isRightClick()) && !e.isShiftClick()) {
            r.guardianChance = Math.clamp(r.guardianChance + (e.isLeftClick() ? 5 : -5), 0, 100);
            r.save();
            org.bukkit.Bukkit.getScheduler().runTask(service.plugin(), () -> p.openInventory(r.new Menu().getInventory()));
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTask(service.plugin(), () -> p.openInventory(r.new Edit(target).getInventory()));
    }

    @EventHandler
    void onRewardEditClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Rewards.Edit edit) || !(e.getPlayer() instanceof Player p)) return;
        edit.save();
        ChacaLink.ok(p, Rewards.title(edit.target) + " 보상을 저장했어요");
        org.bukkit.Bukkit.getScheduler().runTask(service.plugin(), () -> { if (p.isOnline()) p.openInventory(service.rewards.new Menu().getInventory()); });
    }

    @EventHandler
    void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof CardMenu && e.getPlayer() instanceof Player p) service.menuClosed(p);
    }

    /** 소지품은 HuskSync 가 저장하기 전에 돌려놓는다 (LOWEST = 가장 먼저). */
    @EventHandler(priority = EventPriority.LOWEST)
    void onQuit(PlayerQuitEvent e) { service.quit(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    void onJoin(PlayerJoinEvent e) { service.join(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    void onWorld(PlayerChangedWorldEvent e) { service.changedWorld(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTeleport(PlayerTeleportEvent e) { service.teleported(e.getPlayer(), e.getTo()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onMove(PlayerMoveEvent e) { if (e.hasChangedBlock()) service.moved(e.getPlayer(), e.getTo()); }

    /** 판 중에는 다른 곳으로 이동하는 명령을 막는다 (/탑 포기 로 나가기). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    void onCommand(PlayerCommandPreprocessEvent e) {
        if (!service.inRun(e.getPlayer()) || e.getPlayer().hasPermission("chacademi.tower.admin")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(java.util.Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (service.blockedCommand(cmd)) {
            e.setCancelled(true);
            ChacaLink.notify(e.getPlayer(), "warning", "tower:cmd", "탑 안에서는 쓸 수 없어요", "나가려면 /탑 포기", 1500);
        }
    }

    static boolean living(Entity e) { return e instanceof LivingEntity le && !le.isDead(); }
}
