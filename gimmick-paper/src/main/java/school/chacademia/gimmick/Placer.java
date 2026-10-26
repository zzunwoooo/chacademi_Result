package school.chacademia.gimmick;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Placing like an ItemsAdder furniture (owner 2026-10-20): every gimmick sits on the block grid, centred on its block,
 * turned in 90° steps. `/던전관리 소환` = the block the admin stands in, facing where the admin looks.
 * Overlapping another gimmick or replacing non-air blocks is refused.
 */
final class Placer {
    private final GimmickPlugin plugin;

    Placer(GimmickPlugin plugin) { this.plugin = plugin; }

    /** Place on block `feet` (the model's feet). @return the gimmick, or null (reason given to `refused`). */
    Gimmick place(Player p, String model, Block feet, int facing, java.util.function.Consumer<String> refused) {
        var info = plugin.catalog().get(model);
        if (!plugin.catalog().known(model)) plugin.getLogger().info("models.yml에 없는 모델 설치: " + model + " (렌더러에 등록돼 있으면 그대로 그림)");
        if (!plugin.protection().pair(p, feet.getLocation())) { refused.accept("보호 구역이라 설치할 수 없어요"); return null; }
        var probe = new Gimmick("probe", model, feet.getWorld().getName(), feet.getX(), feet.getY(), feet.getZ(), facing);
        Gimmick other = plugin.world().overlap(probe.world, probe.columns(info), probe.by, probe.layers(info), null);
        if (other != null) { refused.accept("그 자리에는 이미 다른 기믹(" + other.id + ")이 있어요"); return null; }
        if (!info.collision().equals("none") && !info.collision().equals("floor")) {
            int layers = info.collision().equals("platform") ? 1 : probe.layers(info);
            for (int[] c : probe.columns(info)) for (int l = 0; l < layers; l++) {
                Block b = feet.getWorld().getBlockAt(c[0], feet.getY() + l, c[1]);
                if (!b.getType().isAir() && !b.isPassable()) { refused.accept("모델이 들어갈 자리가 막혀 있어요 (" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")"); return null; }
            }
        }
        Gimmick g = plugin.world().create(model, feet.getWorld(), feet.getX(), feet.getY(), feet.getZ(), facing);
        plugin.tool().select(p, g);
        return g;
    }
}
