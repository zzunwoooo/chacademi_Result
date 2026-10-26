package school.magiccodex.client;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 지도 이동 미니맵 지형 (2026-10-24, owner: 런처의 지도 모드는 없앰 → 미니맵을 따로 렌더링; 학교 타운맵 전까지 학교도).
 *
 * Each client draws the places it has been: when a chunk loads, its top blocks are coloured like a vanilla map
 * (block map colour, a little lighter / darker by the height step to the north, water darker with depth) a few chunks
 * per tick — never a world scan, never a big image built at once on the render thread. Pixels go into 512x512 region
 * tiles (1 px = 1 block) saved as PNG on a background thread under
 * config/magiccodex/travel-map/&lt;server address&gt;/&lt;server/world/world UID&gt;/r.X.Z.png, so a different server, another
 * wild or a reset world (new UID → new key) never mixes with old tiles. Unexplored = transparent (the grid shows).
 * The key of the world being sampled comes from the server (Markers.cache); "" = this world has no map tab, nothing sampled.
 */
final class TravelTerrain {
    private TravelTerrain() {}

    static final int SIZE = 512, CHUNKS = SIZE / 16, PER_TICK = 48, MAX_REGIONS = 32;
    /** Sampling budget per client tick (a chunk takes well under 0.1 ms): the area around the player fills in a second or two. */
    static final long BUDGET_NS = 2_500_000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "magiccodex-travel-map"); t.setDaemon(true); return t; });
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("magiccodex/travel-map");

    private static final class Region {
        final String key; final int rx, rz;
        int[] px;                      // ARGB, 0 = unexplored; null while loading
        boolean dirty, texDirty, loading;
        NativeImageBackedTexture texture; Identifier id;
        long used, uploaded;
        Region(String key, int rx, int rz) { this.key = key; this.rx = rx; this.rz = rz; }
    }
    private static final Map<String, Region> regions = new LinkedHashMap<>();
    private static final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private static final Set<Long> queued = new HashSet<>();
    private static final Map<String, Set<Long>> known = new HashMap<>();
    private static final Set<String> listing = new HashSet<>();
    private static String key = "";
    private static int serial, saveTimer;
    private static long frame;

    static void initialize() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> { if (!key.isEmpty()) enqueue(chunk.getPos()); });
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick(c));
    }

    /** The server named the map key of the world the player is in now ("" = none). */
    static void key(String next) {
        if (next == null) next = "";
        if (next.equals(key)) return;
        saveAll();
        key = next; queue.clear(); queued.clear();
        var c = MinecraftClient.getInstance();
        if (key.isEmpty() || c.world == null || c.player == null) return;
        // chunks already loaded around the player (joined / changed world before the key arrived)
        int view = c.options.getClampedViewDistance(), pcx = c.player.getChunkPos().x, pcz = c.player.getChunkPos().z;
        for (int r = 0; r <= view; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
            if (Math.max(Math.abs(dx), Math.abs(dz)) == r && c.world.getChunkManager().isChunkLoaded(pcx + dx, pcz + dz)) enqueue(new ChunkPos(pcx + dx, pcz + dz));
    }

    private static void enqueue(ChunkPos p) { if (queued.add(p.toLong())) queue.add(p); }

    private static void tick(MinecraftClient c) {
        if (c.world == null) { if (!regions.isEmpty()) { saveAll(); clear(); } return; }
        if (!key.isEmpty()) {
            long until = System.nanoTime() + BUDGET_NS;
            for (int n = 0; n < PER_TICK && !queue.isEmpty() && System.nanoTime() < until; n++) {
                ChunkPos p = queue.poll();
                Region r = region(key, Math.floorDiv(p.x, CHUNKS), Math.floorDiv(p.z, CHUNKS), true);
                if (r.px == null) { queue.add(p); break; }   // tile still loading from disk: try again later
                queued.remove(p.toLong());
                if (c.world.getChunkManager().isChunkLoaded(p.x, p.z)) sample(c.world, c.world.getChunk(p.x, p.z), r);
            }
        }
        if (++saveTimer >= 20 * 30) { saveTimer = 0; saveAll(); }
    }

    // ------------------------------------------------------------------ sampling

    private static void sample(ClientWorld world, WorldChunk chunk, Region r) {
        var pos = new BlockPos.Mutable();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        int ox = Math.floorMod(chunk.getPos().x, CHUNKS) * 16, oz = Math.floorMod(chunk.getPos().z, CHUNKS) * 16;
        int bottom = world.getBottomY();
        int[] north = new int[16];
        for (int x = 0; x < 16; x++) north[x] = top(world, chunk, baseX + x, baseZ - 1, x, -1);
        boolean changed = false;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
            int color = 0, depth = 0;
            for (int yy = y; yy > bottom && yy > y - 24; yy--) {
                pos.set(baseX + x, yy, baseZ + z);
                var state = chunk.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) {
                    // water: count its depth, colour by the water itself, darker when deep
                    depth++;
                    if (depth == 1) color = state.getMapColor(world, pos).color;
                    continue;
                }
                MapColor mc = state.getMapColor(world, pos);
                if (mc == MapColor.CLEAR) continue;
                if (depth == 0) { color = mc.color; y = yy; }
                break;
            }
            int argb;
            if (color == 0 && depth == 0) argb = 0;
            else if (depth > 0) argb = shade(color, depth > 6 ? 180 : depth > 2 ? 205 : 230);
            else {
                int step = y - north[x];
                argb = shade(color, step > 0 ? 255 : step < 0 ? 180 : 220);
            }
            north[x] = y;
            int i = (oz + z) * SIZE + ox + x;
            if (r.px[i] != argb) { r.px[i] = argb; changed = true; }
        }
        if (changed) { r.dirty = true; r.texDirty = true; known.computeIfAbsent(r.key, k -> new HashSet<>()).add(id(r.rx, r.rz)); }
    }
    private static int top(ClientWorld world, WorldChunk chunk, int wx, int wz, int lx, int lz) {
        if (lz >= 0) return chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz);
        int cx = wx >> 4, cz = wz >> 4;
        if (!world.getChunkManager().isChunkLoaded(cx, cz)) return chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, 0);
        return world.getChunk(cx, cz).sampleHeightmap(Heightmap.Type.WORLD_SURFACE, wx & 15, wz & 15);
    }
    private static int shade(int rgb, int k) {
        int r = (rgb >> 16 & 255) * k / 255, g = (rgb >> 8 & 255) * k / 255, b = (rgb & 255) * k / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ tiles (memory, disk)

    private static long id(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xFFFFFFFFL); }
    private static String name(String key, int rx, int rz) { return key + "|" + rx + "|" + rz; }
    private static Path dir(String key) {
        var server = MinecraftClient.getInstance().getCurrentServerEntry();
        String addr = server == null ? "local" : server.address.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (addr.length() > 80) addr = addr.substring(0, 80);
        return ROOT.resolve(addr).resolve(key.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** The tile, loading it from disk in the background when it is not in memory (px stays null until then). */
    private static Region region(String key, int rx, int rz, boolean create) {
        String n = name(key, rx, rz);
        Region r = regions.get(n);
        if (r != null) { r.used = frame; return r; }
        if (!create) return null;
        r = new Region(key, rx, rz); r.loading = true; r.used = frame;
        regions.put(n, r);
        evict();
        Region target = r;
        Path file = dir(key).resolve("r." + rx + "." + rz + ".png");
        IO.execute(() -> {
            int[] px = new int[SIZE * SIZE];
            try {
                if (Files.isRegularFile(file)) {
                    BufferedImage img = ImageIO.read(file.toFile());
                    if (img != null && img.getWidth() == SIZE && img.getHeight() == SIZE) img.getRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
                }
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("magiccodex").warn("지도 타일 읽기 실패: {}", file, e); }
            MinecraftClient.getInstance().execute(() -> { target.px = px; target.loading = false; target.texDirty = true; });
        });
        return r;
    }
    private static void evict() {
        if (regions.size() <= MAX_REGIONS) return;
        var oldest = new ArrayList<>(regions.values());
        oldest.sort(Comparator.comparingLong(r -> r.used));
        for (Region r : oldest) {
            if (regions.size() <= MAX_REGIONS) break;
            if (r.loading || r.used >= frame - 2) continue;
            if (r.dirty) save(r);
            if (r.id != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(r.id);
            regions.remove(name(r.key, r.rx, r.rz));
        }
    }
    private static void save(Region r) {
        if (r.px == null || !r.dirty) return;
        r.dirty = false;
        int[] copy = r.px.clone();
        Path file = dir(r.key).resolve("r." + r.rx + "." + r.rz + ".png");
        IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                var img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, SIZE, SIZE, copy, 0, SIZE);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                ImageIO.write(img, "png", tmp.toFile());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("magiccodex").warn("지도 타일 저장 실패: {}", file, e); }
        });
    }
    static void saveAll() { for (Region r : regions.values()) if (r.dirty) save(r); }
    private static void clear() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Region r : regions.values()) if (r.id != null) tm.destroyTexture(r.id);
        regions.clear(); known.clear();
    }

    /** Explored tiles of a key (memory + files), for the map's extent. Listed in the background once per key. */
    static Set<Long> known(String key) {
        if (key == null || key.isEmpty()) return Set.of();
        if (listing.add(key)) {
            Path d = dir(key);
            IO.execute(() -> {
                var found = new HashSet<Long>();
                try (var files = Files.list(d)) {
                    files.forEach(f -> {
                        String[] p = f.getFileName().toString().split("\\.");
                        if (p.length == 4 && p[0].equals("r") && p[3].equals("png")) try { found.add(id(Integer.parseInt(p[1]), Integer.parseInt(p[2]))); } catch (NumberFormatException ignored) {}
                    });
                } catch (Exception ignored) {}
                MinecraftClient.getInstance().execute(() -> known.computeIfAbsent(key, k -> new HashSet<>()).addAll(found));
            });
        }
        return known.getOrDefault(key, Set.of());
    }
    /** min x, min z, max x, max z (blocks) of the explored tiles; null = none. */
    static double[] knownBounds(String key) {
        var set = known(key);
        if (set.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long v : set) { int rx = (int) (v >> 32), rz = (int) v; x0 = Math.min(x0, rx); z0 = Math.min(z0, rz); x1 = Math.max(x1, rx); z1 = Math.max(z1, rz); }
        return new double[]{x0 * (double) SIZE, z0 * (double) SIZE, (x1 + 1) * (double) SIZE, (z1 + 1) * (double) SIZE};
    }

    // ------------------------------------------------------------------ drawing (map window)

    /**
     * Draws the explored terrain of `key` for the world rectangle x0..x1 / z0..z1. The caller's matrix maps blocks to the
     * screen with (ox, oz) at its origin (1 unit = 1 block; a nearby origin keeps float precision far from 0,0).
     * At most 36 tiles; missing ones load in the background.
     */
    static void draw(DrawContext c, String key, double x0, double z0, double x1, double z1, int ox, int oz) {
        if (key == null || key.isEmpty()) return;
        frame++;
        int rx0 = (int) Math.floor(x0 / SIZE), rz0 = (int) Math.floor(z0 / SIZE), rx1 = (int) Math.floor(x1 / SIZE), rz1 = (int) Math.floor(z1 / SIZE);
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 36) return;   // zoomed far out: grid only
        var known = known(key);
        var tm = MinecraftClient.getInstance().getTextureManager();
        int uploads = 0;
        for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) {
            boolean exists = known.contains(id(rx, rz)) || regions.containsKey(name(key, rx, rz));
            if (!exists) continue;
            Region r = region(key, rx, rz, true);
            if (r.px == null) continue;
            long nowMs = System.currentTimeMillis();
            // a couple of uploads per frame, and a tile that is still being filled at most ~4 times a second
            if (r.texDirty && uploads < 2 && (r.texture == null || nowMs - r.uploaded > 250)) {
                r.uploaded = nowMs;
                uploads++;
                if (r.texture == null) {
                    var img = new NativeImage(SIZE, SIZE, false);
                    r.texture = new NativeImageBackedTexture(img);
                    r.texture.setFilter(false, false);
                    r.id = Identifier.of("magiccodex", "travel_map/" + serial++);
                    tm.registerTexture(r.id, r.texture);
                }
                var img = r.texture.getImage();
                if (img != null) { for (int i = 0; i < r.px.length; i++) img.setColorArgb(i % SIZE, i / SIZE, r.px[i]); r.texture.upload(); }
                r.texDirty = false;
            }
            if (r.id == null) continue;
            c.drawTexture(RenderLayer::getGuiTextured, r.id, rx * SIZE - ox, rz * SIZE - oz, 0f, 0f, SIZE, SIZE, SIZE, SIZE);
        }
    }
}
