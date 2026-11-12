package school.chacademia.tower;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** 탑 설정 도구 (돌풍 막대): 블록 좌클릭 = 방 모서리1, 우클릭 = 모서리2. 들고 있는 동안 방 테두리를 보여 준다. */
final class SetupTool implements Listener {
    private final TowerPlugin plugin;
    private final NamespacedKey key;

    SetupTool(TowerPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "setup_tool");
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::show, 40L, 10L);
    }

    ItemStack item() {
        var it = new ItemStack(Material.BREEZE_ROD);
        it.editMeta(m -> {
            m.displayName(Component.text("탑 설정 도구", NamedTextColor.LIGHT_PURPLE));
            m.lore(List.of(Component.text("블록 좌클릭: 방 모서리 1", NamedTextColor.GRAY), Component.text("블록 우클릭: 방 모서리 2", NamedTextColor.GRAY),
                    Component.text("/탑관리 방 <이름> 으로 고칠 방을 고른 뒤 (기본 1)", NamedTextColor.GRAY), Component.text("/탑관리 시작 · 소환자리 : 내 자리를 저장", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    private boolean holding(Player p) {
        var it = p.getInventory().getItemInMainHand();
        return it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler
    void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !holding(e.getPlayer())) return;
        if (!e.getPlayer().hasPermission("chacademi.tower.admin")) return;
        boolean first = e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!first && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        var b = e.getClickedBlock();
        String at = b.getX() + "," + b.getY() + "," + b.getZ();
        String id = plugin.editing(e.getPlayer());
        // 다른 방 구역과 겹치면 거절 (겹치면 한쪽 판이 끝날 때 다른 판의 몹·아이템까지 치워짐)
        var other = Settings.block(plugin.getConfig().getString("rooms." + id + (first ? ".corner2" : ".corner1")));
        var next = Settings.Box.of(new int[]{b.getX(), b.getY(), b.getZ()}, other);
        String clash = plugin.service().s.overlapping(id, next);
        if (clash != null) { ChacaLink.fail(e.getPlayer(), "방 " + clash + " 구역과 겹쳐요 — 방끼리는 떨어져 있어야 해요"); return; }
        plugin.set("rooms." + id + (first ? ".corner1" : ".corner2"), at);
        var room = plugin.service().s.rooms.get(id);
        var box = room == null ? null : room.box();
        ChacaLink.ok(e.getPlayer(), "방 " + id + " 모서리 " + (first ? 1 : 2) + " = " + at + (box == null ? "" : " · 구역 " + box.text()));
    }

    private void show() {
        for (var p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (!holding(p)) continue;
            var s = plugin.service().s;
            var room = s.rooms.get(plugin.editing(p));
            if (room == null) continue;
            // 소환 자리: 초록 기둥
            var mark = new Particle.DustOptions(org.bukkit.Color.fromRGB(0x5CFF8A), 1.4f);
            for (var sp : room.spawns()) {
                var l = sp.location();
                if (l == null || !l.getWorld().equals(p.getWorld())) continue;
                for (double y = 0; y < 2.2; y += 0.4) p.spawnParticle(Particle.DUST, l.getX(), l.getY() + y, l.getZ(), 1, mark);
            }
            var box = room.box();
            if (box == null) continue;
            var w = p.getWorld();
            var dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xC070FF), 1.2f);
            // 바닥·천장 테두리만 (2칸 간격)
            for (int x = box.minX(); x <= box.maxX() + 1; x += 2)
                for (int y : new int[]{box.minY(), box.maxY() + 1}) {
                    p.spawnParticle(Particle.DUST, x, y, box.minZ(), 1, dust);
                    p.spawnParticle(Particle.DUST, x, y, box.maxZ() + 1, 1, dust);
                }
            for (int z = box.minZ(); z <= box.maxZ() + 1; z += 2)
                for (int y : new int[]{box.minY(), box.maxY() + 1}) {
                    p.spawnParticle(Particle.DUST, box.minX(), y, z, 1, dust);
                    p.spawnParticle(Particle.DUST, box.maxX() + 1, y, z, 1, dust);
                }
        }
    }
}
