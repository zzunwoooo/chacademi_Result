package school.magiccodex.paper;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Changes presentation only; a resource template never creates a functional core. */
final class CoreVisualBridge implements Listener {
    private final MagicCodexBridge plugin;
    private final ItemsAdderItems items;
    CoreVisualBridge(MagicCodexBridge plugin) {
        this.plugin = plugin;
        this.items = new ItemsAdderItems(plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    CoreVisualBridge(MagicCodexBridge plugin, ItemsAdderItems items) { this.plugin = plugin; this.items = items; }
    static String appearanceId(int state) {
        return switch (state) {
            case 0 -> "item:core_unidentified";
            case 1 -> "item:core_identified";
            case 2 -> "item:core_broken";
            default -> null;
        };
    }
    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }

    ItemStack appearance(ItemStack source) {
        if (source == null || !source.hasItemMeta()) return source;
        var data = source.getItemMeta().getPersistentDataContainer();
        Integer state = data.get(key("core_state"), PersistentDataType.INTEGER);
        Integer nodes = data.get(key("core_nodes"), PersistentDataType.INTEGER);
        String identity = data.get(key("core_id"), PersistentDataType.STRING);
        if (state == null || appearanceId(state) == null || nodes == null || nodes < 0 || nodes > 10 || identity == null) return source;
        try { UUID.fromString(identity); } catch (IllegalArgumentException invalid) { return source; }
        // Existing functional cores are amethyst. Plain legacy PAPER aliases have no authority.
        if (source.getType() != Material.AMETHYST_SHARD) return source;
        String id = appearanceId(state);
        ItemStack template = items.create(id, 1);
        if (template == null || template.getType() != Material.AMETHYST_SHARD || !template.hasItemMeta()) return source;
        var visual = template.getItemMeta();
        if (!visual.hasCustomModelData()) return source;
        var visualData = visual.getPersistentDataContainer();
        // Only ItemsAdder-owned metadata may replace metadata; never template core/stat values.
        if (visualData.getKeys().isEmpty() || visualData.getKeys().stream().anyMatch(k -> !k.getNamespace().equals("itemsadder"))) return source;
        ItemStack result = source.clone();
        var meta = result.getItemMeta();
        meta.setCustomModelData(visual.getCustomModelData());
        var preserved = meta.getPersistentDataContainer();
        for (var k : java.util.Set.copyOf(preserved.getKeys())) if (k.getNamespace().equals("itemsadder")) preserved.remove(k);
        visualData.copyTo(preserved, true);
        result.setItemMeta(meta);
        if (!id.equals(items.idOf(result))) return source;
        return result.equals(source) ? source : result;
    }

    boolean similar(ItemStack a, ItemStack b) {
        return a != null && b != null && appearance(a).isSimilar(appearance(b));
    }

    private void refresh(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack old = inventory.getItem(slot), next = appearance(old);
            if (next != old) inventory.setItem(slot, next);
        }
    }
    private void refresh(Player player) { refresh(player.getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (event.getPlayer().isOnline()) refresh(event.getPlayer()); });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack old = event.getItem().getItemStack(), next = appearance(old);
        if (next != old) event.getItem().setItemStack(next);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        refresh(event.getInventory());
        if (event.getPlayer() instanceof Player player) refresh(player);
    }
    @EventHandler
    public void close(InventoryCloseEvent event) {
        refresh(event.getInventory());
        if (event.getPlayer() instanceof Player player) refresh(player);
    }
}
