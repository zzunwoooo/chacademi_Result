package kr.kkaegul.core.npc;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** 야생 입장 NPC: 일반 입장(고정 위치) / 랜덤 장거리 텔레포트 */
public final class WildService {
    private final KkaegulCore plugin;

    public WildService(KkaegulCore plugin) { this.plugin = plugin; }

    public void enter(Player p) {
        Location l = plugin.places().get("wild");
        if (l != null) { p.teleport(l); return; }
        randomTp(p);
    }

    public void randomTp(Player p) {
        World w = Bukkit.getWorld(plugin.getConfig().getString("wild.world", "world"));
        if (w == null) { plugin.ui().error(p, "&c야생 월드가 없어요 (config wild.world)"); return; }
        int min = plugin.getConfig().getInt("wild.min-radius", 1000), max = plugin.getConfig().getInt("wild.max-radius", 5000);
        int cx = plugin.getConfig().getInt("wild.center-x", 0), cz = plugin.getConfig().getInt("wild.center-z", 0);
        for (int attempt = 0; attempt < plugin.getConfig().getInt("wild.attempts", 25); attempt++) {
            double ang = Rng.r().nextDouble() * Math.PI * 2;
            int r = Rng.range(min, max);
            int x = cx + (int) (Math.cos(ang) * r), z = cz + (int) (Math.sin(ang) * r);
            int y = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, y, z);
            Material t = ground.getType();
            if (t == Material.WATER || t == Material.LAVA || t.name().contains("LEAVES") || t == Material.CACTUS || t == Material.MAGMA_BLOCK) continue;
            if (y <= w.getMinHeight() + 1) continue;
            p.teleport(new Location(w, x + 0.5, y + 1, z + 0.5, p.getLocation().getYaw(), 0));
            plugin.ui().center(p, "&a야생 랜덤 이동", "&f" + x + ", " + z);
            return;
        }
        plugin.ui().error(p, "&c안전한 위치를 못 찾았어요. 다시 시도해 주세요.");
    }
}
