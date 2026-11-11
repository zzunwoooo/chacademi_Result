package kr.kkaegul.core.land;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 농사 밭(AddCrop 경작지 = ItemsAdder 노트블록)이 한 번 툭 쳐도 부서지던 것 막기:
 * 웅크린 채로 부술 때만 부서짐. 대상 id 는 farm.pot-guard.prefixes (기본 addcrop:addcrop_pot).
 */
public final class PotGuard implements Listener {
    private final KkaegulCore plugin;
    private Method byAlreadyPlaced, blockId, evPlayer, evId;

    public PotGuard(KkaegulCore plugin) {
        this.plugin = plugin;
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return;
        try {
            Class<?> cb = Class.forName("dev.lone.itemsadder.api.CustomBlock");
            byAlreadyPlaced = cb.getMethod("byAlreadyPlaced", Block.class);
            blockId = cb.getMethod("getNamespacedID");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("밭 보호: ItemsAdder CustomBlock 을 찾지 못함 " + e);
        }
        // ItemsAdder 자체 블록 부수기 이벤트도 막음 (노트블록 블록은 이쪽으로 처리될 수 있음)
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> ev = (Class<? extends Event>) Class.forName("dev.lone.itemsadder.api.Events.CustomBlockBreakEvent");
            evPlayer = ev.getMethod("getPlayer");
            evId = ev.getMethod("getNamespacedID");
            Bukkit.getPluginManager().registerEvent(ev, this, EventPriority.LOWEST, (l, e) -> {
                if (!ev.isInstance(e)) return;
                try {
                    Player p = (Player) evPlayer.invoke(e);
                    String id = (String) evId.invoke(e);
                    if (p != null && guarded(id) && !p.isSneaking()) { ((Cancellable) e).setCancelled(true); warn(p); }
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }, plugin, true);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("밭 보호: CustomBlockBreakEvent 를 찾지 못함 " + e);
        }
    }

    private boolean guarded(String id) {
        if (id == null || !plugin.getConfig().getBoolean("farm.pot-guard.enabled", true)) return false;
        List<String> pre = plugin.getConfig().getStringList("farm.pot-guard.prefixes");
        if (pre.isEmpty()) pre = List.of("addcrop:addcrop_pot");
        for (String s : pre) if (id.startsWith(s)) return true;
        return false;
    }

    private String idOf(Block b) {
        if (byAlreadyPlaced == null || b.getType() != Material.NOTE_BLOCK) return null;
        try {
            Object cb = byAlreadyPlaced.invoke(null, b);
            return cb == null ? null : (String) blockId.invoke(cb);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private void warn(Player p) {
        plugin.ui().actionbar(p, "&7밭은 &f웅크리고 &7부숴야 부서져요");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getPlayer().isSneaking()) return;
        if (!guarded(idOf(e.getBlock()))) return;
        e.setCancelled(true);
        warn(e.getPlayer());
    }
}
