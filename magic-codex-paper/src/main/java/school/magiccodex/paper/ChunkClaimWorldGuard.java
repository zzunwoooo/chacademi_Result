package school.magiccodex.paper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * 청크와 겹치는 WorldGuard 지역이 있는지 (메모 #88: 스폰 등 지역과 겹치는 청크는 "제한").
 * 이 플러그인은 WorldGuard 없이도 빌드·실행되므로 리플렉션으로 부른다 (없으면 겹치는 지역 없음).
 * 결과는 청크마다 기억해 두고 (지역은 거의 안 바뀜) 새로고침 때·10분마다 비운다. 서버 스레드에서만.
 *
 * <p>호출: WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world))
 * .getApplicableRegions(new ProtectedCuboidRegion(id, BlockVector3.at(..), BlockVector3.at(..))) → 지역 목록 (__global__ 은 안 들어옴).</p>
 */
final class ChunkClaimWorldGuard {
    private final Logger log;
    private boolean resolved, broken;
    private Method instance, platform, container, adapt, get, applicable, at, regionId;
    private Constructor<?> cuboid;
    private final Map<String, Map<Long, Boolean>> cache = new HashMap<>();
    private long clearedAt = System.currentTimeMillis();

    ChunkClaimWorldGuard(Logger log) { this.log = log; }

    void clear() { cache.clear(); clearedAt = System.currentTimeMillis(); }

    /** 겹치는 지역이 있으면 true (ignore 에 적은 지역 ID 는 빼고). WorldGuard 가 없거나 호출이 깨지면 false. */
    boolean overlaps(World world, int cx, int cz, Set<String> ignore) {
        if (System.currentTimeMillis() - clearedAt > 600_000) clear();
        var w = cache.computeIfAbsent(world.getName(), k -> new HashMap<>());
        long key = ChunkClaimRules.key(cx, cz);
        Boolean hit = w.get(key);
        if (hit == null) { hit = query(world, cx, cz, ignore); if (w.size() > 20_000) w.clear(); w.put(key, hit); }
        return hit;
    }

    private boolean query(World world, int cx, int cz, Set<String> ignore) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard") || !resolve()) return false;
        try {
            Object wg = instance.invoke(null);
            Object regions = container.invoke(platform.invoke(wg));
            Object manager = get.invoke(regions, adapt.invoke(null, world));
            if (manager == null) return false;
            int minY = world.getMinHeight(), maxY = world.getMaxHeight() - 1;
            Object min = at.invoke(null, cx * 16, minY, cz * 16), max = at.invoke(null, cx * 16 + 15, maxY, cz * 16 + 15);
            Object probe = cuboid.newInstance("__chacademi_chunk_probe", min, max);
            Object set = applicable.invoke(manager, probe);
            if (!(set instanceof Iterable<?> list)) return false;
            for (Object region : list) {
                String id = String.valueOf(regionId.invoke(region));
                if (!id.equals("__global__") && !ignore.contains(id.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!broken) { broken = true; log.warning("청크 점유: WorldGuard 지역 확인 실패 (" + e + ") — 지역 겹침은 '제한'으로 보지 않습니다"); }
            return false;
        }
    }

    private boolean resolve() {
        if (resolved) return instance != null;
        resolved = true;
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            ClassLoader loader = plugin == null ? getClass().getClassLoader() : plugin.getClass().getClassLoader();
            Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader);
            Class<?> vector = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, loader);
            Class<?> weWorld = Class.forName("com.sk89q.worldedit.world.World", true, loader);
            Class<?> protectedRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, loader);
            Class<?> cuboidClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion", true, loader);
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, loader);
            instance = wg.getMethod("getInstance");
            platform = wg.getMethod("getPlatform");
            container = platform.getReturnType().getMethod("getRegionContainer");
            adapt = adapter.getMethod("adapt", World.class);
            get = container.getReturnType().getMethod("get", weWorld);
            applicable = managerClass.getMethod("getApplicableRegions", protectedRegion);
            at = vector.getMethod("at", int.class, int.class, int.class);
            regionId = protectedRegion.getMethod("getId");
            cuboid = cuboidClass.getConstructor(String.class, vector, vector);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            instance = null;
            log.warning("청크 점유: WorldGuard API 를 찾지 못했어요 (" + e + ") — 지역 겹침은 '제한'으로 보지 않습니다");
            return false;
        }
    }
}
