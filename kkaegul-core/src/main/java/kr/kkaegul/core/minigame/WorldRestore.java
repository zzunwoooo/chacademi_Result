package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 한 월드(보물찾기 맵)에서 바뀐 블록의 "처음 모습"을 기억했다가 되돌린다.
 * 설치·파괴·폭발·물/용암 흐름·불·피스톤·나무 자람·모래 떨어짐 등을 잡고, 부서진 블록 둘레(붙어 있던 횃불 등)도 같이 기억.
 * 기억한 것은 파일(restore-<월드>.txt)에 바로바로 덧붙여 써서, 서버가 갑자기 꺼져도 다음에 켤 때 되돌린다.
 */
public final class WorldRestore implements Listener {
    private final KkaegulCore plugin;
    private final java.util.function.Supplier<String> worldName;
    /** "x,y,z" → 처음 블록 데이터 */
    private final Map<String, String> original = new LinkedHashMap<>();
    private final List<String> buffer = new ArrayList<>();
    private String fileWorld;
    private boolean restoring;

    public WorldRestore(KkaegulCore plugin, java.util.function.Supplier<String> worldName) {
        this.plugin = plugin;
        this.worldName = worldName;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 40L, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, this::recoverFromFile, 100L);
    }

    private File file(String w) { return new File(plugin.getDataFolder(), "restore-" + w + ".txt"); }

    private boolean watched(World w) {
        String n = worldName.get();
        return !restoring && n != null && w != null && w.getName().equals(n);
    }

    private static String key(Block b) { return b.getX() + "," + b.getY() + "," + b.getZ(); }

    /** 바뀌기 전 블록 기억 (처음 한 번만) */
    public void remember(Block b) {
        if (b == null || !watched(b.getWorld())) return;
        String k = key(b);
        if (original.containsKey(k)) return;
        String data = b.getBlockData().getAsString();
        original.put(k, data);
        fileWorld = b.getWorld().getName();
        buffer.add(k + "\t" + data);
    }

    private void remember(BlockState st) {
        if (st == null || !watched(st.getWorld())) return;
        Block b = st.getBlock();
        String k = key(b);
        if (original.containsKey(k)) return;
        String data = st.getBlockData().getAsString();
        original.put(k, data);
        fileWorld = st.getWorld().getName();
        buffer.add(k + "\t" + data);
    }

    private static final BlockFace[] AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private void rememberAround(Block b) {
        if (b == null || !watched(b.getWorld())) return;
        remember(b);
        for (BlockFace f : AROUND) remember(b.getRelative(f));
        remember(b.getRelative(BlockFace.UP).getRelative(BlockFace.UP));
    }

    public int changed() { return original.size(); }

    // ───────── 파밍 막기: 이 월드에서 부순 블록·폭발은 아이템을 떨어뜨리지 않음 (OP 제외) ─────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void noDrops(BlockBreakEvent e) {
        String n = worldName.get();
        if (n == null || !e.getBlock().getWorld().getName().equals(n) || e.getPlayer().isOp()) return;
        if (plugin.mine().inOreArea(e.getBlock())) return;   // 같은 월드의 광산 구역은 캔 광석이 떨어져야 함 (철 등)
        e.setDropItems(false);
        e.setExpToDrop(0);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void noExplodeDrops(EntityExplodeEvent e) {
        String n = worldName.get();
        if (n != null && e.getEntity() != null && e.getEntity().getWorld().getName().equals(n)) e.setYield(0);
    }

    // ───────── 이벤트 ─────────
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { rememberAround(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) {
        remember(e.getBlockReplacedState());
        if (e instanceof BlockMultiPlaceEvent m) for (BlockState st : m.getReplacedBlockStates()) remember(st);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onExplode(EntityExplodeEvent e) { for (Block b : e.blockList()) rememberAround(b); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { rememberAround(e.getBlock()); for (Block b : e.blockList()) rememberAround(b); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFlow(BlockFromToEvent e) { remember(e.getToBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBurn(BlockBurnEvent e) { rememberAround(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onIgnite(BlockIgniteEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFade(BlockFadeEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onForm(BlockFormEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSpread(BlockSpreadEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDecay(LeavesDecayEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onFert(BlockFertilizeEvent e) { for (BlockState st : e.getBlocks()) remember(st); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onGrow(StructureGrowEvent e) { for (BlockState st : e.getBlocks()) remember(st); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onEntityBlock(EntityChangeBlockEvent e) { rememberAround(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBucket(PlayerBucketEmptyEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBucketFill(PlayerBucketFillEvent e) { remember(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonOut(BlockPistonExtendEvent e) {
        remember(e.getBlock());
        for (Block b : e.getBlocks()) { remember(b); remember(b.getRelative(e.getDirection())); }
        remember(e.getBlock().getRelative(e.getDirection()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonIn(BlockPistonRetractEvent e) {
        remember(e.getBlock());
        remember(e.getBlock().getRelative(e.getDirection().getOppositeFace()));
        for (Block b : e.getBlocks()) { remember(b); remember(b.getRelative(e.getDirection())); }
    }

    // ───────── 파일 ─────────
    private void flush() {
        if (buffer.isEmpty() || fileWorld == null) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(fileWorld), true), StandardCharsets.UTF_8)) {
            for (String l : buffer) w.write(l + "\n");
        } catch (IOException e) {
            plugin.getLogger().warning("복구 기록 실패: " + e);
        }
        buffer.clear();
    }

    private void recoverFromFile() {
        String n = worldName.get();
        if (n == null) return;
        File f = file(n);
        if (!f.exists()) return;
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                original.putIfAbsent(l.substring(0, t), l.substring(t + 1));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("복구 기록 읽기 실패: " + e);
            return;
        }
        fileWorld = n;
        if (!original.isEmpty()) {
            plugin.getLogger().info("[보물찾기] 지난번에 되돌리지 못한 블록 " + original.size() + "개를 되돌립니다");
            restoreAll(null);
        }
    }

    /** 전부 되돌리기 (틱마다 나눠서). 끝나면 파일 지움 */
    public void restoreAll(Runnable done) {
        String n = worldName.get();
        World w = n == null ? null : Bukkit.getWorld(n);
        if (w == null) { if (done != null) done.run(); return; }
        flush();
        List<Map.Entry<String, String>> list = new ArrayList<>(original.entrySet());
        original.clear();
        buffer.clear();
        restoring = true;
        final int per = Math.max(200, plugin.getConfig().getInt("minigame.games.treasure.restore-per-tick", 3000));
        final int[] at = {0};
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(list.size(), at[0] + per);
            for (; at[0] < end; at[0]++) {
                var e = list.get(at[0]);
                String[] p = e.getKey().split(",");
                try {
                    Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    BlockData d = Bukkit.createBlockData(e.getValue());
                    b.setBlockData(d, false);
                } catch (RuntimeException ex) { /* 잘못된 줄은 건너뜀 */ }
            }
            if (at[0] >= list.size()) {
                task[0].cancel();
                restoring = false;
                File f = file(w.getName());
                if (f.exists() && !f.delete()) plugin.getLogger().warning("복구 기록 파일을 지우지 못했어요: " + f);
                if (!list.isEmpty()) plugin.getLogger().info("[보물찾기] 블록 " + list.size() + "개 되돌림");
                if (done != null) done.run();
            }
        }, 1L, 1L);
    }
}
