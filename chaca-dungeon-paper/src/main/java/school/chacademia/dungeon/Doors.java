package school.chacademia.dungeon;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 문 = 구역 안의 빈칸(공기)을 배리어로 막는 것 + 클라이언트가 그 상자를 보라 홀로그램으로 그림.
 * 우리가 놓은 배리어만 기록해 두고(doors-ledger.yml) 그것만 지운다 → 서버가 갑자기 꺼져도 다음 시작 때 걷어낸다.
 * 칸이 쉬는 동안(FREE)은 문을 열어 둔다 (맵 작업 방해 안 함). 판이 시작될 때 닫는다.
 */
final class Doors {
    private final Logger log;
    private final File file;
    /** "던전:칸:문" → 우리가 놓은 배리어 위치들 {world, x, y, z}. */
    private final Map<String, List<int[]>> placed = new HashMap<>();
    private final Map<String, String> worlds = new HashMap<>();

    Doors(Logger log, File file) { this.log = log; this.file = file; }

    static String key(String dungeon, int slot, String door) { return dungeon + ":" + slot + ":" + door; }

    boolean closed(String key) { return placed.containsKey(key); }

    /** 문 닫기: 상자 안 공기 칸을 배리어로. 서 있는 사람의 몸이 걸치는 칸은 건너뛴다. */
    void close(String key, World w, Defs.Box box) {
        if (placed.containsKey(key)) return;
        var list = new ArrayList<int[]>();
        long volume = (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
        if (volume > 4096) { log.warning("문 " + key + " 이 너무 커요 (" + volume + "칸, 4096 까지) → 닫지 않음"); return; }
        var people = w.getNearbyEntities(box.bounds().clone().expand(1), e -> e instanceof org.bukkit.entity.Player);
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int y = box.minY(); y <= box.maxY(); y++)
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    var b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) continue;
                    var cell = new org.bukkit.util.BoundingBox(x, y, z, x + 1, y + 1, z + 1);
                    boolean occupied = false;
                    for (var p : people) if (p.getBoundingBox().overlaps(cell)) { occupied = true; break; }
                    if (occupied) continue;
                    b.setType(Material.BARRIER, false);
                    list.add(new int[]{x, y, z});
                }
        placed.put(key, list);
        worlds.put(key, w.getName());
        save();
    }

    /** 문 열기: 우리가 놓은 배리어만 공기로. */
    void open(String key) {
        var list = placed.remove(key);
        String world = worlds.remove(key);
        if (list == null) return;
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w != null) for (int[] c : list) {
            var b = w.getBlockAt(c[0], c[1], c[2]);
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
        save();
    }

    /** 이 칸의 문 전부 열기 (정리·종료). */
    void openSlot(String dungeon, int slot) {
        String prefix = dungeon + ":" + slot + ":";
        for (String k : new ArrayList<>(placed.keySet())) if (k.startsWith(prefix)) open(k);
    }

    /** 시작 때: 지난번에 놓고 못 걷은 배리어 걷기. */
    void recover() {
        if (!file.exists()) return;
        var yml = YamlConfiguration.loadConfiguration(file);
        for (String k : yml.getKeys(false)) {
            World w = Bukkit.getWorld(yml.getString(k + ".world", ""));
            if (w == null) continue;
            int n = 0;
            for (String s : yml.getStringList(k + ".blocks")) {
                int[] c = Defs.block(s);
                if (c == null) continue;
                var b = w.getBlockAt(c[0], c[1], c[2]);
                if (b.getType() == Material.BARRIER) { b.setType(Material.AIR, false); n++; }
            }
            if (n > 0) log.info("지난번 문 배리어 " + n + "칸 걷음: " + k);
        }
        placed.clear(); worlds.clear();
        save();
    }

    private void save() {
        var yml = new YamlConfiguration();
        for (var e : placed.entrySet()) {
            yml.set(e.getKey().replace('.', '_') + ".world", worlds.get(e.getKey()));
            yml.set(e.getKey().replace('.', '_') + ".blocks", e.getValue().stream().map(Defs::block).toList());
        }
        try { yml.save(file); } catch (Exception ex) { log.warning("doors-ledger.yml 저장 실패: " + ex); }
    }
}
