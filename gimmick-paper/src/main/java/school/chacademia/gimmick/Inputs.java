package school.chacademia.gimmick;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Player inputs: right click (lever, open teleport gate), standing on pressure plates, walking into open gates.
 * Left clicks on placed gimmicks are swallowed (they are not mobs).
 */
final class Inputs implements Listener {
    private final GimmickPlugin plugin;
    private final Map<UUID, Long> teleportCooldown = new HashMap<>();

    Inputs(GimmickPlugin plugin) {
        this.plugin = plugin;
        int every = Math.max(1, plugin.getConfig().getInt("stand-check-ticks", 4));
        Bukkit.getScheduler().runTaskTimer(plugin, this::standCheck, every, every);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rightClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Gimmick g = plugin.world().byEntity(e.getRightClicked());
        if (g == null) return;
        Player p = e.getPlayer();
        if (!plugin.protection().pair(p, g.location())) return;
        if (plugin.tool().isTool(p.getInventory().getItemInMainHand())) { e.setCancelled(true); plugin.tool().use(p, g, true); return; }
        if (g.attached()) return;
        e.setCancelled(true);
        interact(p, g);
    }

    /** Right click on a gimmick (its model, its collision barrier, or from afar with 194 유령 도구). */
    void interact(Player p, Gimmick g) {
        if (g.attached()) return;
        if (plugin.catalog().get(g.model).is("gate")) { Gimmick to = plugin.signals().partner(g); if (to != null) teleportTo(p, to); return; }
        plugin.signals().interact(g);
    }

    /**
     * Clicks that land on a gimmick's collision barrier (the client hits the block before the model) and right clicks
     * from beyond normal reach while 194 유령 도구 raised the block reach.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void blockClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        boolean right = e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR;
        boolean left = e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR;
        if (!right && !left) return;
        Gimmick g = null;
        var b = e.getClickedBlock();
        if (b != null && plugin.collision().ours(b)) g = plugin.world().at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (g == null && right && e.getAction() == Action.RIGHT_CLICK_AIR) {
            var reach = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double r = reach == null ? 4.5 : reach.getValue();
            if (r > 6) { Gimmick far = plugin.world().looking(p, r); if (far != null && far.location() != null && far.location().distance(p.getEyeLocation()) > 3) g = far; }
        }
        if (g == null || !plugin.protection().pair(p, g.location())) return;
        if (plugin.tool().isTool(p.getInventory().getItemInMainHand())) return; // the tool handles it
        e.setCancelled(true);
        if (right) interact(p, g);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void leftClick(PrePlayerAttackEntityEvent e) {
        Gimmick g = plugin.world().byEntity(e.getAttacked());
        if (g == null) return;
        Player p = e.getPlayer();
        if (!plugin.protection().pair(p, g.location())) return;
        if (plugin.tool().isTool(p.getInventory().getItemInMainHand())) { e.setCancelled(true); plugin.tool().use(p, g, false); return; }
        if (g.attached()) return; // a real mob: let the hit through
        e.setCancelled(true);
    }

    /** Extra things that hold plates down (set by MagicHook: shadow doubles). */
    static java.util.function.Supplier<List<Location>> extraStanders = List::of;

    private boolean teleportTo(Player p, Gimmick t) {
        long now = plugin.signals().now();
        if (teleportCooldown.getOrDefault(p.getUniqueId(), -1L) > now) return false;
        Location to = t.location();
        if (to == null || !plugin.protection().pair(p, to)) return false;
        Vector f = t.forward().multiply(1.5);
        to = to.clone().add(f.getX(), 0.1, f.getZ()); to.setY(t.by + 0.05); to.setYaw(t.yaw); to.setPitch(0);
        if (!plugin.protection().pair(p, to) || !to.getBlock().isPassable() || !to.clone().add(0, 1, 0).getBlock().isPassable()) return false;
        teleportCooldown.put(p.getUniqueId(), now + plugin.getConfig().getLong("teleport-cooldown-ticks", 60));
        p.teleport(to);
        return true;
    }

    /** Pressure plates held down by players (or 168 그림자 분신), and walk-in teleport gates. */
    private void standCheck() {
        var pressed = new HashSet<String>();
        for (Gimmick g : plugin.world().all()) {
            if (!g.live() || g.attached()) continue;
            var info = plugin.catalog().get(g.model);
            boolean plate = info.is("plate");
            Gimmick gateTo = info.is("gate") ? plugin.signals().partner(g) : null;
            if (!plate && gateTo == null) continue;
            Location l = g.location();
            if (l == null) continue;
            BoundingBox box = g.box(info);
            BoundingBox feet = plate ? new BoundingBox(box.getMinX() - 0.2, box.getMinY() - 0.3, box.getMinZ() - 0.2, box.getMaxX() + 0.2, box.getMaxY() + 1.0, box.getMaxZ() + 0.2) : box;
            for (Player p : l.getWorld().getPlayers()) {
                if (!plugin.protection().pair(p, l) || p.getGameMode() == GameMode.SPECTATOR || !feet.contains(p.getLocation().toVector())) continue;
                if (plate) pressed.add(g.id);
                if (gateTo != null) teleportTo(p, gateTo);
            }
            if (plate && !pressed.contains(g.id)) for (Location d : extraStanders.get())
                if (d.getWorld() == l.getWorld() && feet.contains(d.toVector())) { pressed.add(g.id); break; }
        }
        plugin.signals().standing(pressed);
    }
}
