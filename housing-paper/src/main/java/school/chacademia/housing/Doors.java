package school.chacademia.housing;

import java.io.File;
import java.io.IOException;
import java.util.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import school.magiccodex.protocol.SchoolProtocol;

/**
 * 기숙사실 방문(블록). 관리자가 /하우징관리 문 등록 으로 블록을 정하면, 그 블록을 우클릭한 학생의 방 꾸미기 창이 열린다.
 * 문마다 기숙사를 정할 수 있다 (그 기숙사 학생만 열 수 있음). 이 서버의 plugins/ChacaHousing/doors.yml 에 저장.
 */
final class Doors implements Listener {
    record Door(String world, int x, int y, int z, int house) {
        String key() { return key(world, x, y, z); }
        static String key(String world, int x, int y, int z) { return world + "," + x + "," + y + "," + z; }
        String describe() { return world + " " + x + " " + y + " " + z + " · " + (house < 0 ? "모든 기숙사" : SchoolProtocol.HOUSES.get(house)); }
    }

    private final HousingPlugin plugin;
    private final File file;
    private final Map<String, Door> doors = new LinkedHashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    Doors(HousingPlugin plugin) { this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "doors.yml"); }

    void load() {
        doors.clear();
        var yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("doors")) {
            String[] f = line.split(",");
            try {
                if (f.length != 5) throw new IllegalArgumentException();
                var d = new Door(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Math.clamp(Integer.parseInt(f[4]), -1, 3));
                doors.put(d.key(), d);
            } catch (IllegalArgumentException bad) { plugin.getLogger().warning("doors.yml 줄 건너뜀: " + line); }
        }
    }
    private void save() {
        var yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("기숙사실 방문 블록: 월드,x,y,z,기숙사(-1 = 모두, 0 아르케온, 1 루미나, 2 베스티아즈, 3 노크세르). /하우징관리 문 으로 바꾸세요."));
        yaml.set("doors", doors.values().stream().map(d -> d.world() + "," + d.x() + "," + d.y() + "," + d.z() + "," + d.house()).toList());
        try { yaml.save(file); } catch (IOException e) { plugin.getLogger().warning("doors.yml 저장 실패: " + e.getMessage()); }
    }

    Door at(Block b) { return doors.get(Door.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())); }
    /** 실제 문(2칸)은 위·아래 어느 쪽을 눌러도 같은 문으로 본다. */
    Door near(Block b) {
        Door d = at(b);
        if (d != null || !b.getType().name().endsWith("_DOOR")) return d;
        d = at(b.getRelative(BlockFace.DOWN));
        return d != null ? d : at(b.getRelative(BlockFace.UP));
    }
    void add(Block b, int house) { var d = new Door(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), house); doors.put(d.key(), d); save(); }
    boolean remove(Block b) { boolean r = doors.remove(Door.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) != null; if (r) save(); return r; }
    Collection<Door> all() { return doors.values(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Door door = near(e.getClickedBlock());
        if (door == null) return;
        e.setCancelled(true);
        var p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now - lastClick.getOrDefault(p.getUniqueId(), 0L) < 1000) return;
        lastClick.put(p.getUniqueId(), now);
        if (door.house() >= 0) {
            int house = plugin.rooms().house(p.getUniqueId());
            if (house < 0) { int live = Rooms.fallbackHouse(p); if (live >= 0) house = live; }
            if (house == -2) { ChacaLink.notify(p, "system", "housing-door-wait", "학생 정보를 불러오는 중이에요", "잠시 후 다시 눌러 주세요", 1500); return; }
            if (house != door.house() && !p.isOp()) {
                ChacaLink.notify(p, "warning", "housing-door-house", "들어갈 수 없는 방이에요", SchoolProtocol.HOUSES.get(door.house()) + " 기숙사 학생만 들어갈 수 있어요", 2000);
                return;
            }
        }
        plugin.rooms().open(p, p.getUniqueId(), true);
    }

    /** 등록된 블록을 실수로 부수지 않게 (관리자는 먼저 문 삭제). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (near(e.getBlock()) == null) return;
        e.setCancelled(true);
        ChacaLink.notify(e.getPlayer(), "warning", "housing-door-break", "기숙사실 문으로 등록된 블록이에요", "/하우징관리 문 삭제 로 먼저 등록을 지워 주세요", 2000);
    }

    void forget(UUID id) { lastClick.remove(id); }
}
