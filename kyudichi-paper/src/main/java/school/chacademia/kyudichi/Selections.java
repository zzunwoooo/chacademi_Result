package school.chacademia.kyudichi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/** WorldEdit-style 지점1/지점2 per admin (선택도구) + particle outlines for 테두리보기. */
final class Selections implements Listener {
    private final KyudichiPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Location[]> sel = new HashMap<>();
    private final Map<UUID, String> showing = new HashMap<>();
    private final Set<UUID> showSelection = new HashSet<>();

    Selections(KyudichiPlugin plugin) { this.plugin = plugin; wandKey = new NamespacedKey(plugin, "wand"); }

    ItemStack wand() {
        var item = new ItemStack(Material.BLAZE_ROD);
        item.editMeta(m -> {
            m.displayName(Component.text("큐디치 선택도구", NamedTextColor.GOLD));
            m.lore(java.util.List.of(Component.text("좌클릭 지점1 · 우클릭 지점2", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isWand(ItemStack i) { return i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(wandKey); }

    @EventHandler
    public void click(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isWand(e.getItem()) || !e.getPlayer().isOp()) return;
        e.setCancelled(true);
        Block b = e.getClickedBlock();
        if (b == null) b = e.getPlayer().getTargetBlockExact(64);
        if (b == null) { ChacaLink.notify(e.getPlayer(), "warning", "kyudichi:wand", "블록을 보고 클릭하세요", "", 1000); return; }
        boolean left = e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR;
        set(e.getPlayer(), left ? 0 : 1, b.getLocation());
    }

    void set(Player p, int i, Location l) {
        var s = sel.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        s[i] = l.toBlockLocation();
        showSelection.add(p.getUniqueId());
        Box box = box(p);
        ChacaLink.notify(p, "system", "kyudichi:point" + i, "지점" + (i + 1) + " = " + s[i].getBlockX() + ", " + s[i].getBlockY() + ", " + s[i].getBlockZ(),
                box != null ? "선택 " + box.size() : "", 0);
    }

    Box box(Player p) {
        var s = sel.get(p.getUniqueId());
        if (s == null || s[0] == null || s[1] == null || !s[0].getWorld().equals(s[1].getWorld())) return null;
        return Box.of(s[0], s[1]);
    }

    /** 선택 확장 위|아래|사방 n. null = no selection yet. */
    String expand(Player p, String dir, int n) {
        var s = sel.get(p.getUniqueId());
        Box b = box(p);
        if (b == null) return null;
        int x1 = b.x1(), y1 = b.y1(), z1 = b.z1(), x2 = b.x2(), y2 = b.y2(), z2 = b.z2();
        switch (dir) {
            case "위" -> y2 += n;
            case "아래" -> y1 -= n;
            case "사방" -> { x1 -= n; z1 -= n; x2 += n; z2 += n; }
            default -> { return "방향은 위 · 아래 · 사방"; }
        }
        World w = s[0].getWorld();
        s[0] = new Location(w, x1, y1, z1); s[1] = new Location(w, x2, y2, z2);
        return "선택 " + box(p).size();
    }

    void clear(Player p) { sel.remove(p.getUniqueId()); showSelection.remove(p.getUniqueId()); }
    /** Toggles the arena outline for this admin. Returns true if now on. */
    boolean toggleShow(Player p, String arena) {
        if (arena.equals(showing.get(p.getUniqueId()))) { showing.remove(p.getUniqueId()); return false; }
        showing.put(p.getUniqueId(), arena); return true;
    }

    /** Every 10 ticks. */
    void tick() {
        for (UUID id : showSelection) { Player p = Bukkit.getPlayer(id); Box b = p == null ? null : box(p); if (b != null) outline(p, b, Color.YELLOW); }
        for (var e : showing.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey()); Arena a = plugin.arenas().get(e.getValue());
            if (p == null || a == null) continue;
            if (a.bounds != null) outline(p, a.bounds, Color.WHITE);
            if (a.ballArea != null) outline(p, a.ballArea, Color.ORANGE);
            if (a.snitchArea != null) outline(p, a.snitchArea, Color.fromRGB(0xFFD700));
            for (Box g : a.goals.get(Team.A)) outline(p, g, Color.BLUE);
            for (Box g : a.goals.get(Team.B)) outline(p, g, Color.RED);
            for (Box f : a.forbidden) outline(p, f, Color.BLACK);
            for (Team t : Team.values()) for (Point s : a.starts.get(t)) mark(p, s, t == Team.A ? Color.BLUE : Color.RED);
            mark(p, a.quaffle, Color.ORANGE);
            for (Point b : a.bludgers) mark(p, b, Color.fromRGB(0x550000));
            for (Point s : a.snitchSpawns) mark(p, s, Color.YELLOW);
            for (ShrineType t : ShrineType.values()) for (Point s : a.shrines.get(t)) mark(p, s, switch (t) { case HEALTH -> Color.LIME; case MANA -> Color.AQUA; case STABILITY -> Color.fromRGB(0xFFC040); case HASTE -> Color.fromRGB(0x70D8FF); });
        }
    }

    private void mark(Player p, Point pt, Color c) {
        if (pt == null || !pt.world().equals(p.getWorld().getName()) || pt.vector().distanceSquared(p.getLocation().toVector()) > 128 * 128) return;
        p.spawnParticle(Particle.DUST, pt.x(), pt.y() + 0.2, pt.z(), 6, 0.1, 0.4, 0.1, 0, new Particle.DustOptions(c, 1.6f));
    }

    private void outline(Player p, Box b, Color c) {
        World w = p.getWorld();
        if (!w.getName().equals(b.world())) return;
        var dust = new Particle.DustOptions(c, 1.2f);
        double[] xs = {b.minX(), b.maxX()}, ys = {b.minY(), b.maxY()}, zs = {b.minZ(), b.maxZ()};
        Vector eye = p.getLocation().toVector();
        double step = Math.max(0.5, Math.max(Math.max(b.maxX() - b.minX(), b.maxY() - b.minY()), b.maxZ() - b.minZ()) / 48);
        for (double y : ys) for (double z : zs) line(p, eye, new Vector(xs[0], y, z), new Vector(xs[1], y, z), step, dust);
        for (double x : xs) for (double z : zs) line(p, eye, new Vector(x, ys[0], z), new Vector(x, ys[1], z), step, dust);
        for (double x : xs) for (double y : ys) line(p, eye, new Vector(x, y, zs[0]), new Vector(x, y, zs[1]), step, dust);
    }

    private void line(Player p, Vector eye, Vector a, Vector b, double step, Particle.DustOptions dust) {
        double len = a.distance(b);
        Vector d = b.clone().subtract(a).normalize();
        for (double t = 0; t <= len; t += step) {
            Vector v = a.clone().add(d.clone().multiply(t));
            if (v.distanceSquared(eye) < 96 * 96) p.spawnParticle(Particle.DUST, v.getX(), v.getY(), v.getZ(), 1, 0, 0, 0, 0, dust);
        }
    }
}
