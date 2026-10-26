package school.magiccodex.paper;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import school.magiccodex.protocol.FishingProtocol;

/**
 * One measurement of the water the bobber landed in (main thread, once per cast).
 *
 * - surface: the top water block above the bobber.
 * - area: water blocks connected to it on the surface layer (4-neighbour flood fill), stops at the scan limit
 *   (owner 2026-10-19: 바다처럼 큰 물은 전부 '대형').
 * - depth: water blocks straight down from the surface at the bobber (sea plants count as water), at most 64.
 * - underground: the air above the surface gets almost no sky light.
 */
record FishingWater(String biome, Set<String> groups, int area, int depth, int surfaceY, boolean underground, int background) {
    private static final int MAX_DEPTH_SCAN = 64;

    static boolean water(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    /** null when there is no water at this spot. */
    static FishingWater measure(Location at, FishingConfig c) {
        World world = at.getWorld();
        if (world == null) return null;
        Block b = at.getBlock();
        if (!water(b)) { b = b.getRelative(0, -1, 0); if (!water(b)) return null; }
        int top = world.getMaxHeight() - 1;
        while (b.getY() < top && water(b.getRelative(0, 1, 0))) b = b.getRelative(0, 1, 0);
        int surface = b.getY();
        // depth
        int depth = 0;
        Block d = b;
        while (depth < MAX_DEPTH_SCAN && d.getY() > world.getMinHeight() && water(d)) { depth++; d = d.getRelative(0, -1, 0); }
        // area on the surface layer
        int limit = c.scanLimit, x0 = b.getX(), z0 = b.getZ();
        var seen = new HashSet<Long>();
        var queue = new ArrayDeque<long[]>();
        queue.add(new long[]{x0, z0}); seen.add(pack(x0, z0));
        int area = 0;
        while (!queue.isEmpty() && area < limit) {
            long[] p = queue.poll();
            int x = (int) p[0], z = (int) p[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!water(world.getBlockAt(x, surface, z))) continue;
            area++;
            int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] o : n) {
                int nx = x + o[0], nz = z + o[1];
                if (seen.add(pack(nx, nz))) queue.add(new long[]{nx, nz});
            }
        }
        if (area >= limit) area = Integer.MAX_VALUE / 2;
        boolean underground = b.getRelative(0, 1, 0).getLightFromSky() < 8;
        String biome = b.getBiome().getKey().toString().toLowerCase(Locale.ROOT);
        var groups = c.groupsOf(biome);
        int bg;
        if (underground || !Collections.disjoint(groups, c.caveGroups)) bg = FishingProtocol.BG_CAVE;
        else if (c.iceBiomes.contains(biome) || !Collections.disjoint(groups, c.iceGroups)) bg = FishingProtocol.BG_ICE;
        else if (!Collections.disjoint(groups, c.oceanGroups)) bg = FishingProtocol.BG_OCEAN;
        else bg = FishingProtocol.BG_FRESH;
        return new FishingWater(biome, Set.copyOf(groups), area, depth, surface, underground, bg);
    }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
}
