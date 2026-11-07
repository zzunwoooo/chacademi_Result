package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 보물찾기 (3시간마다 최대 30명, 거부 불가).
 * <ol>
 *   <li>treasure-start 로 이동 → 대기 30초 (반경 15칸 안에서만 돌아다님).</li>
 *   <li>그동안 treasure-start 둘레(±area-radius) 땅 위에만 상자를 숨긴다: 일반 10 · 고급 5 · 코인 5개짜리 20 · 행운 1.
 *       아래가 단단한 블록, 위로 두 칸 비어 있음, 옆에 물·용암 없음, 대기 구역 밖, 상자끼리 spacing 칸 이상 떨어짐.</li>
 *   <li>60초 동안 찾기. 우클릭하면 바로 보상 + 전체 알림 "OO님이 고급 상자를 찾았습니다!" (고급·행운 = 흰 글씨, 나머지 회색).</li>
 *   <li>끝나면 남은 상자를 치우고 원래 자리로 돌려보낸 뒤, 그 월드에서 바뀐 블록(설치·파괴·폭발 등)을 전부 되돌린다.</li>
 * </ol>
 */
public final class TreasureHuntGame extends Minigame {
    private static final class Chest { final Block block; final String kind; Chest(Block b, String k) { block = b; kind = k; } }
    private final List<Chest> placed = new ArrayList<>();
    private final Map<UUID, List<String>> got = new HashMap<>();
    private final WorldRestore restore;
    private boolean waiting;
    private Location center;
    private final Map<UUID, Long> warned = new HashMap<>();

    public TreasureHuntGame(KkaegulCore plugin) {
        super(plugin, "treasure");
        restore = new WorldRestore(plugin, () -> {
            Location s = plugin.places().get("treasure-start");
            return s == null || s.getWorld() == null ? null : s.getWorld().getName();
        });
    }

    @Override public String label() { return "보물찾기"; }

    @Override public String readiness() {
        Location s = plugin.places().get("treasure-start");
        if (s == null || s.getWorld() == null) return "위치 treasure-start 필요";
        return null;
    }

    private int waitRadius() { return cfgInt("wait-radius", 15); }

    @Override
    protected void start(List<Player> list) {
        center = plugin.places().get("treasure-start");
        got.clear();
        placed.clear();
        warned.clear();
        waiting = true;
        for (Player p : online()) p.teleport(center);
        int total = hide();
        int seconds = cfgInt("seconds", 60);
        waitPhase(cfgInt("wait-seconds", 30), "보물찾기 시작", () -> {
            waiting = false;
            for (Player p : online()) {
                hudOpen(p, Json.obj().put("chests", total).put("left", placed.size()).put("seconds", seconds));
                if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, "&f보물찾기 시작!", "&7상자 " + total + "개 · " + seconds + "초");
                p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 0.8f, 1.2f);
            }
            later(seconds * 20L, this::end);
        });
    }

    // ───────── 상자 숨기기 ─────────
    private int hide() {
        World w = center.getWorld();
        ConfigurationSection kinds = plugin.getConfig().getConfigurationSection("minigame.games.treasure.chests");
        List<String> bag = new ArrayList<>();
        if (kinds != null) for (String k : kinds.getKeys(false)) for (int i = 0; i < kinds.getInt(k + ".count", 0); i++) bag.add(k);
        if (bag.isEmpty()) for (int i = 0; i < 5; i++) bag.add("premium");
        Collections.shuffle(bag);
        int area = cfgInt("area-radius", 170), spacing = cfgInt("spacing", 20), keepOut = waitRadius() + 5;
        int cx = center.getBlockX(), cz = center.getBlockZ();
        List<Block> spots = new ArrayList<>();
        for (String kind : bag) {
            Block spot = null;
            for (int tries = 0; tries < 60 && spot == null; tries++) {
                int x = cx + Rng.range(-area, area), z = cz + Rng.range(-area, area);
                if (Math.abs(x - cx) <= keepOut && Math.abs(z - cz) <= keepOut) continue;
                boolean near = false;
                for (Block o : spots) if (Math.abs(o.getX() - x) < spacing && Math.abs(o.getZ() - z) < spacing) { near = true; break; }
                if (near) continue;
                Block top = w.getHighestBlockAt(x, z);
                if (!ground(top)) continue;
                Block at = top.getRelative(BlockFace.UP);
                if (!at.getType().isAir() || !at.getRelative(BlockFace.UP).getType().isAir()) continue;
                if (wet(at)) continue;
                spot = at;
            }
            if (spot == null) continue;
            spots.add(spot);
            restore.remember(spot);
            spot.setType(Material.CHEST, false);
            placed.add(new Chest(spot, kind));
        }
        if (placed.size() < bag.size()) plugin.getLogger().info("[보물찾기] 상자 " + placed.size() + "/" + bag.size() + "개만 놓을 자리를 찾았어요");
        return placed.size();
    }

    private static boolean ground(Block b) {
        Material m = b.getType();
        if (!m.isSolid()) return false;
        String n = m.name();
        return !n.endsWith("LEAVES") && !n.contains("ICE") && m != Material.CACTUS && m != Material.MAGMA_BLOCK && !n.endsWith("_LOG") && !n.contains("GLASS");
    }

    private static boolean wet(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN, BlockFace.UP}) {
            Material m = b.getRelative(f).getType();
            if (m == Material.WATER || m == Material.LAVA) return true;
        }
        return false;
    }

    // ───────── 대기 구역 ─────────
    @Override
    public boolean allowMove(Player p, Location to) {
        if (!waiting || center == null || to.getWorld() == null || !to.getWorld().equals(center.getWorld())) return true;
        double dx = to.getX() - center.getX(), dz = to.getZ() - center.getZ(), r = waitRadius();
        if (dx * dx + dz * dz <= r * r) return true;
        long now = System.currentTimeMillis();
        Long last = warned.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            warned.put(p.getUniqueId(), now);
            p.sendActionBar(c("&7시작 전에는 " + (int) r + "칸 밖으로 나갈 수 없어요"));
        }
        return false;
    }

    // ───────── 찾기 ─────────
    @Override
    public boolean onInteractBlock(Player p, Block b) {
        Chest hit = find(b);
        if (hit == null) return false;
        if (waiting) { p.sendActionBar(c("&7아직 시작 전이에요!")); return true; }
        placed.remove(hit);
        b.setType(Material.AIR, false);
        reward(p, hit.kind);
        for (Player o : online()) plugin.ui().update(o, "minigame", Json.obj().put("left", placed.size()).put("finder", plugin.displayName(p)));
        if (placed.isEmpty()) { tell("&f보물을 모두 찾았어요!", ""); later(60, this::end); }
        return true;
    }

    private Chest find(Block b) {
        for (Chest c : placed) {
            Block k = c.block;
            if (k.getWorld().equals(b.getWorld()) && k.getX() == b.getX() && k.getY() == b.getY() && k.getZ() == b.getZ()) return c;
        }
        return null;
    }

    private void reward(Player p, String kind) {
        String base = "minigame.games.treasure.chests." + kind;
        String label = plugin.getConfig().getString(base + ".label", kind);
        boolean important = plugin.getConfig().getBoolean(base + ".important", kind.equals("premium") || kind.equals("lucky"));
        for (String a : plugin.getConfig().getStringList(base + ".actions")) plugin.actions().run(p.getUniqueId(), a, "보물찾기");
        got.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(label);
        setState(p.getUniqueId(), "success");
        if (plugin.ui().hasRenderer(p)) plugin.ui().fx(p, "treasure_found", Json.obj().put("label", label).put("premium", important));
        else plugin.ui().center(p, "&f보물 발견!", "&7" + label);
        p.playSound(p.getLocation(), important ? "minecraft:entity.player.levelup" : "minecraft:entity.experience_orb.pickup", 1f, important ? 1.2f : 1.0f);
        plugin.announce((important ? "&f" : "&7") + plugin.displayName(p) + "님이 " + label + josa(label) + " 찾았습니다!", important);
    }

    /** 을/를 */
    static String josa(String w) {
        if (w == null || w.isEmpty()) return "를";
        char c = w.charAt(w.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return Character.isDigit(c) ? ("013678".indexOf(c) >= 0 ? "을" : "를") : "를";
        return (c - 0xAC00) % 28 == 0 ? "를" : "을";
    }

    @Override
    public boolean protects(Block b) { return find(b) != null; }

    @Override
    protected void cleanup() {
        waiting = false;
        for (Player p : online()) {
            List<String> mine = got.getOrDefault(p.getUniqueId(), List.of());
            resultFx(p, !mine.isEmpty(), mine.isEmpty() ? "상자를 찾지 못했어요" : "상자 " + mine.size() + "개 찾았어요!", mine.toArray(new String[0]));
        }
        got.clear();
        for (Chest c : placed) if (c.block.getType() == Material.CHEST) c.block.setType(Material.AIR, false);
        placed.clear();
        // 참가자가 원래 자리로 돌아간 뒤 블록 되돌리기
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> restore.restoreAll(null), 40L);
    }

    /** 관리자: 지금 바로 되돌리기 */
    public int restoreNow() { int n = restore.changed(); restore.restoreAll(null); return n; }
}
