package kr.kkaegul.core.land;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;

/**
 * 마을(공허 월드 분양지). 2×2 청크 = 한 블록, 블록마다 둘레를 폭 1청크 도로가 감쌈.
 *  - 청크 좌표 (cx-ox)%3==0 또는 (cz-oz)%3==0 → 도로, 나머지 → 1청크 = 땅 1칸 (땅문서로 구매)
 *  - groups-x × groups-z 블록 (기본 8×4 = 땅 128칸, 25×13 청크). 가운데 교차로 = 분수 광장 (스폰·"마을" 이동 위치)
 *  - 월드는 VoidGen 빈 월드, 처음 켤 때 한 번 땅·도로를 깔고 월드 폴더에 표시 파일을 남김 (다시 깔지 않음)
 *  - 도로·안 팔린 땅은 아무도 못 건드림 (LandProtect), 땅은 1인당 max-per-player 칸까지
 */
public final class TownService {
    private static final int VERSION = 2;   // 2: 인도 나무·벤치·화분, 노란 중앙선 없앰 (v1 월드는 도로 청크만 다시 깜)
    private final KkaegulCore plugin;

    TownService(KkaegulCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskLater(plugin, this::setup, 100L);
    }

    private ConfigurationSection cfg() { return plugin.getConfig().getConfigurationSection("town"); }
    public boolean enabled() { ConfigurationSection c = cfg(); return c != null && c.getBoolean("enabled", false); }
    public String worldName() { ConfigurationSection c = cfg(); return c == null ? "" : c.getString("world", "kkaegul_town"); }
    public boolean isTown(String world) { return enabled() && worldName().equals(world); }
    private int gx() { return Math.max(1, cfg().getInt("groups-x", 8)); }
    private int gz() { return Math.max(1, cfg().getInt("groups-z", 4)); }
    private int ox() { return -(3 * gx()) / 2; }
    private int oz() { return -(3 * gz()) / 2; }
    private int groundY() { return cfg().getInt("ground-y", 64); }
    public int maxPerPlayer() { ConfigurationSection c = cfg(); return c == null ? 0 : c.getInt("max-per-player", 1); }

    private boolean inside(int cx, int cz) { return cx >= ox() && cx <= ox() + 3 * gx() && cz >= oz() && cz <= oz() + 3 * gz(); }
    private boolean roadX(int cx) { return Math.floorMod(cx - ox(), 3) == 0; }
    private boolean roadZ(int cz) { return Math.floorMod(cz - oz(), 3) == 0; }

    /** 살 수 있는 땅 칸인지 */
    public boolean isPlot(int cx, int cz) { return inside(cx, cz) && !roadX(cx) && !roadZ(cz); }

    /** 분수 광장 청크 (가운데에서 가장 가까운 교차로) */
    private int plazaCx() { return ox() + 3 * (gx() / 2); }
    private int plazaCz() { return oz() + 3 * (gz() / 2); }

    public Location spawn(World w) {
        return new Location(w, plazaCx() * 16 + 7.5, groundY() + 1, plazaCz() * 16 + 12.5, 180, 0);
    }

    // ───────── 준비 ─────────
    private void setup() {
        if (!enabled()) return;
        String wn = worldName();
        World w = Bukkit.getWorld(wn);
        boolean fresh = w == null && !new File(Bukkit.getWorldContainer(), wn).exists();
        if (w == null && fresh && Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null) {
            // 멀티버스로 만들기 (멀티버스 월드 목록에도 들어감)
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv create " + wn + " normal --generator VoidGen"); }
            catch (RuntimeException e) { plugin.getLogger().warning("[마을] 멀티버스로 월드를 만들지 못함: " + e); }
            w = Bukkit.getWorld(wn);
        }
        if (w == null) {
            WorldCreator wc = new WorldCreator(wn);
            if (Bukkit.getPluginManager().getPlugin("VoidGen") != null) wc.generator("VoidGen");
            w = Bukkit.createWorld(wc);
        }
        if (w == null) { plugin.getLogger().warning("[마을] 월드 " + wn + " 를 열지 못했어요"); return; }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        w.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        w.setDifficulty(Difficulty.NORMAL);
        w.setPVP(false);
        Location sp = spawn(w);
        w.setSpawnLocation(sp);
        File mark = new File(w.getWorldFolder(), "kkaegul_town.v" + VERSION);
        if (!mark.exists()) build(w, mark, new File(w.getWorldFolder(), "kkaegul_town.v1").exists());
        else linkVillage(sp);
    }

    /** "마을" 이동 위치가 비어 있으면 광장으로 */
    private void linkVillage(Location sp) {
        if (!cfg().getBoolean("set-village", true)) return;
        Location v = plugin.places().get("village");
        if (v == null || v.getWorld() == null || !v.getWorld().getName().equals(worldName())) {
            plugin.places().set("village", sp);
            plugin.getLogger().info("[마을] \"마을\" 이동 위치를 광장으로 정함");
        }
    }

    /** roadsOnly = 이미 깔린 월드: 땅(유저가 지은 것)은 건드리지 않고 도로 청크만 다시 깜 */
    private void build(World w, File mark, boolean roadsOnly) {
        int x0 = ox(), x1 = ox() + 3 * gx(), z0 = oz(), z1 = oz() + 3 * gz();
        int total = (x1 - x0 + 1) * (z1 - z0 + 1);
        plugin.getLogger().info("[마을] 땅·도로 까는 중... (" + total + "청크, 땅 " + (gx() * gz() * 4) + "칸)");
        final int[] cur = {x0, z0};
        final int[] done = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int n = 0; n < 2; n++) {
                if (!roadsOnly || !isPlot(cur[0], cur[1])) { chunk(w, cur[0], cur[1]); done[0]++; }
                if (++cur[0] > x1) {
                    cur[0] = x0;
                    if (++cur[1] > z1) {
                        task[0].cancel();
                        try { mark.createNewFile(); } catch (IOException e) { plugin.getLogger().warning("[마을] 표시 파일 실패: " + e); }
                        linkVillage(spawn(w));
                        plugin.getLogger().info("[마을] 완성 (" + done[0] + "청크)");
                        return;
                    }
                }
            }
        }, 1L, 1L);
    }

    // ───────── 청크 하나 ─────────
    private void chunk(World w, int cx, int cz) {
        int g = groundY();
        boolean rx = roadX(cx), rz = roadZ(cz);
        boolean plaza = cx == plazaCx() && cz == plazaCz();
        for (int u = 0; u < 16; u++) for (int v = 0; v < 16; v++) {
            int x = cx * 16 + u, z = cz * 16 + v;
            set(w, x, g - 6, z, Material.BEDROCK);
            for (int y = g - 5; y <= g - 3; y++) set(w, x, y, z, Material.STONE);
            if (!rx && !rz) {   // 땅
                set(w, x, g - 2, z, Material.DIRT);
                set(w, x, g - 1, z, Material.DIRT);
                set(w, x, g, z, Material.GRASS_BLOCK);
                continue;
            }
            set(w, x, g - 2, z, Material.STONE);
            for (int y = g + 1; y <= g + 7; y++) set(w, x, y, z, Material.AIR);   // 예전 장식 지우기
            if (plaza) { plazaColumn(w, x, z, u, v, g); continue; }
            Material top;
            boolean low;     // 차도 = 한 칸 낮음
            if (rx && rz) {  // 교차로: 네 모서리만 인도, 가장자리엔 횡단보도
                boolean sideU = u <= 3 || u >= 12, sideV = v <= 3 || v >= 12;
                if (sideU && sideV) { top = walk(u, v); low = false; }
                else {
                    low = true;
                    boolean zebra = (v <= 2 || v >= 13) && u % 2 == 0 || (u <= 2 || u >= 13) && v % 2 == 0;
                    top = zebra ? Material.WHITE_CONCRETE : asphalt(x, z);
                }
            } else {
                int a = rx ? u : v, along = rx ? v : u;   // a = 도로 가로, along = 도로 따라
                if (a <= 2 || a >= 13) { top = walk(a, along); low = false; }
                else if (a == 3 || a == 12) { top = Material.SMOOTH_STONE; low = false; }
                else {
                    low = true;
                    top = asphalt(x, z);
                }
            }
            if (low) { set(w, x, g - 1, z, top); }
            else { set(w, x, g - 1, z, Material.STONE); set(w, x, g, z, top); }
        }
        if (!plaza) decorate(w, cx, cz, rx, rz, g);
        edgeWall(w, cx, cz, g);
    }

    private static Material walk(int a, int along) {
        if (a == 0 || a == 15) return Material.STONE_BRICKS;
        return (a + along) % 4 == 0 ? Material.ANDESITE : Material.POLISHED_ANDESITE;
    }

    private static Material asphalt(int x, int z) {
        int h = (x * 73856093) ^ (z * 19349663);
        return Math.floorMod(h, 23) == 0 ? Material.LIGHT_GRAY_CONCRETE_POWDER : Math.floorMod(h, 7) == 0 ? Material.CYAN_TERRACOTTA : Material.GRAY_CONCRETE;
    }

    /** 인도 꾸미기: 가로등만 (땅 옆 인도는 비워 둠) */
    private void decorate(World w, int cx, int cz, boolean rx, boolean rz, int g) {
        if (rx && rz) {   // 교차로 모서리 가로등
            lamp(w, cx * 16 + 1, g, cz * 16 + 1);
            lamp(w, cx * 16 + 14, g, cz * 16 + 1);
            lamp(w, cx * 16 + 1, g, cz * 16 + 14);
            lamp(w, cx * 16 + 14, g, cz * 16 + 14);
            return;
        }
        for (int side : new int[]{1, 14}) {
            int x = cx * 16 + (rx ? side : 8), z = cz * 16 + (rx ? 8 : side);
            lamp(w, x, g, z);
        }
    }

    private void lamp(World w, int x, int g, int z) {
        set(w, x, g + 1, z, Material.STONE_BRICK_WALL);
        set(w, x, g + 2, z, Material.DARK_OAK_FENCE);
        set(w, x, g + 3, z, Material.DARK_OAK_FENCE);
        set(w, x, g + 4, z, Material.LANTERN);
    }

    /** 마을 바깥 테두리: 낮은 담 */
    private void edgeWall(World w, int cx, int cz, int g) {
        int minX = ox() * 16, maxX = (ox() + 3 * gx()) * 16 + 15, minZ = oz() * 16, maxZ = (oz() + 3 * gz()) * 16 + 15;
        for (int u = 0; u < 16; u++) for (int v = 0; v < 16; v++) {
            int x = cx * 16 + u, z = cz * 16 + v;
            if (x == minX || x == maxX || z == minZ || z == maxZ) {
                set(w, x, g + 1, z, Material.STONE_BRICK_WALL);
                if ((x + z) % 8 == 0) { set(w, x, g + 2, z, Material.STONE_BRICK_WALL); set(w, x, g + 3, z, Material.LANTERN); }
            }
        }
    }

    /** 가운데 분수 광장 */
    private void plazaColumn(World w, int x, int z, int u, int v, int g) {
        set(w, x, g - 1, z, Material.STONE);
        boolean ring = (u == 3 || u == 11) && v >= 3 && v <= 11 || (v == 3 || v == 11) && u >= 3 && u <= 11;
        boolean basin = u >= 4 && u <= 10 && v >= 4 && v <= 10;
        if (basin) {
            set(w, x, g, z, Material.WATER);
            if (u == 7 && v == 7) {
                set(w, x, g, z, Material.CHISELED_STONE_BRICKS);
                set(w, x, g + 1, z, Material.CHISELED_STONE_BRICKS);
                set(w, x, g + 2, z, Material.STONE_BRICK_WALL);
                set(w, x, g + 3, z, Material.SEA_LANTERN);
            }
            return;
        }
        int d = Math.max(Math.abs(u - 7), Math.abs(v - 7));
        set(w, x, g, z, d % 2 == 0 ? Material.POLISHED_DIORITE : Material.SMOOTH_STONE);
        if (ring) set(w, x, g + 1, z, Material.STONE_BRICK_SLAB);
        if ((u == 1 || u == 14) && (v == 1 || v == 14)) lamp(w, x, g, z);
        if ((u == 1 || u == 14) && v == 7 || (v == 1 || v == 14) && u == 7) set(w, x, g + 1, z, Material.POTTED_FLOWERING_AZALEA_BUSH);
    }

    private static void set(World w, int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }
}
