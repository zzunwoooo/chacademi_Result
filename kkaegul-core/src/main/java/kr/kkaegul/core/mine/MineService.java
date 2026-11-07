package kr.kkaegul.core.mine;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Locs;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 보물광산.
 *  - 광물을 캘 때 trigger-chance% 로 광산 이동 (하루 3번).
 *  - 이동 시 90% 제한맵(블록 적음/철곡괭이만), 10% 보물광산(블록 가득, 개인 곡괭이 OK).
 *  - stay-seconds 후 원래 자리로 복귀. 보물광산 이동권은 하루 횟수와 별개.
 *  - 각 광산 구역은 regen-minutes 마다 palette 대로 재생성.
 */
public final class MineService {
    private final KkaegulCore plugin;
    private final Map<UUID, BukkitTask> returns = new ConcurrentHashMap<>();
    private final Map<UUID, String> inMine = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRegen = new ConcurrentHashMap<>();
    /** 광산에 들어오기 전 위치 (다른 기능의 backLocation 과 분리) */
    private final Map<UUID, String> origin = new ConcurrentHashMap<>();

    public MineService(KkaegulCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::regenTick, 20L * 30, 20L * 30);
        Bukkit.getScheduler().runTaskLater(plugin, this::autoSetup, 60L);
    }

    public ConfigurationSection regions() { return plugin.getConfig().getConfigurationSection("mine.regions"); }

    /** BlockBreak 훅: 광산 이동 판정 */
    public void onOreMined(Player p, Material type) {
        if (inMine.containsKey(p.getUniqueId())) return;
        if (!plugin.getConfig().getStringList("mine.trigger-blocks").contains(type.name())) return;
        if (!Rng.chance(plugin.getConfig().getDouble("mine.trigger-chance", 2))) return;
        PlayerData d = plugin.data().get(p);
        String today = plugin.clock().dayKey();
        if (!today.equals(d.mineDay)) { d.mineDay = today; d.mineMoves = 0; }
        if (d.mineMoves >= plugin.getConfig().getInt("mine.daily-moves", 3)) return;
        d.mineMoves++;
        d.markDirty();
        boolean treasure = Rng.chance(plugin.getConfig().getDouble("mine.treasure-chance", 10));
        if (treasure) sendTreasure(p, false);
        else sendLimited(p);
    }

    /** 보물광산: treasure 로 시작하는 구역 중 랜덤 한 곳. 갔으면 true */
    public boolean sendTreasure(Player p, boolean byTicket) {
        ConfigurationSection rs = regions();
        List<String> list = new ArrayList<>();
        if (rs != null) for (String k : rs.getKeys(false)) if (k.startsWith("treasure") && plugin.places().get("mine." + k) != null) list.add(k);
        String pick = Rng.pick(list);
        if (pick == null) { plugin.getLogger().warning("보물광산 구역/스폰 위치가 없어요 (mine.regions.treasure*)"); return false; }
        return go(p, pick, byTicket ? "&e보물광산 이동권 사용!" : "&6보물광산 당첨!!");
    }

    /**
     * 보물광산 자동 준비 (mine.auto-setup): 빈 월드(VoidGen)를 만들고, 구역마다 바닥(기반암)·둘레 투명벽을 두르고
     * palette 로 채운 뒤 스폰 위치(mine.<구역>)를 구역 윗면 가운데로 정한다. 이미 있으면 건드리지 않음.
     */
    private void autoSetup() {
        if (!plugin.getConfig().getBoolean("mine.auto-setup", true)) return;
        ConfigurationSection rs = regions();
        if (rs == null) return;
        for (String k : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(k);
            if (r == null) continue;
            String wn = r.getString("world", "");
            if (wn.isBlank()) continue;
            World w = Bukkit.getWorld(wn);
            if (w == null) {
                try {
                    org.bukkit.WorldCreator wc = new org.bukkit.WorldCreator(wn);
                    String gen = plugin.getConfig().getString("mine.generator", "VoidGen");
                    if (!gen.isBlank() && Bukkit.getPluginManager().getPlugin(gen) != null) wc.generator(gen);
                    w = Bukkit.createWorld(wc);
                    plugin.getLogger().info("[광산] 월드 만듦/불러옴: " + wn);
                } catch (RuntimeException e) { plugin.getLogger().warning("[광산] 월드 " + wn + " 를 만들지 못함: " + e); continue; }
            }
            if (w == null) continue;
            int[] a = xyz(r.getString("min")), b = xyz(r.getString("max"));
            if (a == null || b == null) continue;
            int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]), y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]), z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
            if (plugin.places().get("mine." + k) == null) {
                // 바닥 기반암 + 둘레 투명벽 (구역 위로 6칸까지)
                for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) {
                    w.getBlockAt(x, y0 - 1, z).setType(Material.BEDROCK, false);
                    boolean edge = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                    if (edge) for (int y = y0; y <= y1 + 6; y++) w.getBlockAt(x, y, z).setType(Material.BARRIER, false);
                }
                Location spawn = new Location(w, (x0 + x1) / 2.0 + .5, y1 + 1, (z0 + z1) / 2.0 + .5);
                plugin.places().set("mine." + k, spawn);
                regen(k);
                plugin.getLogger().info("[광산] 구역 " + k + " 준비 완료 (스폰 " + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ() + ")");
            }
        }
    }

    public void sendLimited(Player p) {
        ConfigurationSection rs = regions();
        List<String> limited = new ArrayList<>();
        if (rs != null) for (String k : rs.getKeys(false)) if (k.startsWith("limited")) limited.add(k);
        String pick = Rng.pick(limited);
        if (pick == null) return;
        go(p, pick, "&7광산으로 끌려왔어요...");
    }

    private boolean go(Player p, String region, String title) {
        Location to = plugin.places().get("mine." + region);
        if (to == null) { plugin.getLogger().warning("광산 스폰 위치 없음: /kg setloc mine." + region); return false; }
        if (!inMine.containsKey(p.getUniqueId())) origin.put(p.getUniqueId(), Locs.toStr(p.getLocation()));
        inMine.put(p.getUniqueId(), region);
        p.teleport(to);
        int stay = plugin.getConfig().getInt("mine.regions." + region + ".stay-seconds", plugin.getConfig().getInt("mine.stay-seconds", 300));
        plugin.ui().center(p, title, "&f" + Txt.duration(stay) + " 후 원래 자리로 돌아가요");
        BukkitTask old = returns.remove(p.getUniqueId());
        if (old != null) old.cancel();
        if (stay > 0) returns.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> leave(p), stay * 20L));
        return true;
    }

    public void leave(Player p) {
        returns.remove(p.getUniqueId());
        String from = origin.remove(p.getUniqueId());
        if (inMine.remove(p.getUniqueId()) == null || !p.isOnline()) return;
        Location back = Locs.fromStr(from);
        if (back == null) back = plugin.places().lobby();
        if (back != null) p.teleport(back);
        plugin.ui().center(p, "&a광산 시간 끝!", "&f원래 자리로 돌아왔어요");
    }

    public void onQuit(Player p) {
        BukkitTask t = returns.remove(p.getUniqueId());
        if (t != null) t.cancel();
        if (inMine.remove(p.getUniqueId()) != null) markReturn(p.getUniqueId());
    }

    /** 다음 접속 때 광산 들어오기 전 자리에서 시작하도록 표시 */
    private void markReturn(UUID id) {
        PlayerData d = plugin.data().get(id);
        String from = origin.remove(id);
        if (from != null) d.mineReturn = from;
        if (!d.pendingApi.contains("@mine-return")) d.pendingApi.add("@mine-return");
        d.markDirty();
    }

    /** 서버 종료: 광산 안 사람 전원 다음 접속 때 복귀 */
    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(inMine.keySet())) {
            BukkitTask t = returns.remove(id);
            if (t != null) t.cancel();
            inMine.remove(id);
            markReturn(id);
        }
    }

    public void returnAfterRelog(Player p) {
        PlayerData d = plugin.data().get(p);
        Location back = Locs.fromStr(d.mineReturn);
        if (back == null) back = plugin.places().lobby();
        d.mineReturn = "";
        d.markDirty();
        if (back != null) p.teleport(back);
    }

    /** 이 블록이 속한 광산 구역 (없으면 null) */
    public String regionAt(Location l) {
        ConfigurationSection rs = regions();
        if (rs == null || l.getWorld() == null) return null;
        for (String k : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(k);
            if (r == null || !l.getWorld().getName().equals(r.getString("world"))) continue;
            int[] a = xyz(r.getString("min")), b = xyz(r.getString("max"));
            if (a == null || b == null) continue;
            if (between(l.getBlockX(), a[0], b[0]) && between(l.getBlockY(), a[1], b[1]) && between(l.getBlockZ(), a[2], b[2])) return k;
        }
        return null;
    }

    /** 제한맵 도구 제한 */
    public boolean toolAllowed(String region, Material tool) {
        List<String> allowed = plugin.getConfig().getStringList("mine.regions." + region + ".allowed-tools");
        return allowed.isEmpty() || allowed.contains(tool.name());
    }

    private static boolean between(int v, int a, int b) { return v >= Math.min(a, b) && v <= Math.max(a, b); }

    private static int[] xyz(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ───────── 재생성 ─────────
    private void regenTick() {
        ConfigurationSection rs = regions();
        if (rs == null) return;
        long now = System.currentTimeMillis();
        for (String k : rs.getKeys(false)) {
            long every = rs.getLong(k + ".regen-minutes", 0) * 60_000L;
            if (every <= 0) continue;
            long last = lastRegen.getOrDefault(k, 0L);
            if (now - last >= every) { lastRegen.put(k, now); regen(k); }
        }
    }

    public boolean regen(String region) {
        ConfigurationSection r = plugin.getConfig().getConfigurationSection("mine.regions." + region);
        if (r == null) return false;
        World w = Bukkit.getWorld(r.getString("world", ""));
        int[] a = xyz(r.getString("min")), b = xyz(r.getString("max"));
        ConfigurationSection pal = r.getConfigurationSection("palette");
        if (w == null || a == null || b == null || pal == null) return false;
        Map<Material, Double> weights = new LinkedHashMap<>();
        for (String m : pal.getKeys(false)) {
            Material mat = Material.matchMaterial(m);
            if (mat != null && mat.isBlock()) weights.put(mat, pal.getDouble(m));
        }
        if (weights.isEmpty()) return false;
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        // 안에 있는 플레이어는 스폰으로
        Location spawn = plugin.places().get("mine." + region);
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (spawn != null && between(l.getBlockX(), x0, x1) && between(l.getBlockY(), y0, y1) && between(l.getBlockZ(), z0, z1)) p.teleport(spawn);
        }
        int perTick = plugin.getConfig().getInt("mine.regen-blocks-per-tick", 4000);
        final int[] cur = {x0, y0, z0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int n = 0;
            while (n < perTick) {
                Block blk = w.getBlockAt(cur[0], cur[1], cur[2]);
                Material m = Rng.weighted(weights);
                if (m != null && blk.getType() != m) blk.setType(m, false);
                n++;
                if (++cur[0] > x1) { cur[0] = x0; if (++cur[2] > z1) { cur[2] = z0; if (++cur[1] > y1) { task[0].cancel(); return; } } }
            }
        }, 1L, 1L);
        return true;
    }

    public boolean isInMine(Player p) { return inMine.containsKey(p.getUniqueId()); }
}
