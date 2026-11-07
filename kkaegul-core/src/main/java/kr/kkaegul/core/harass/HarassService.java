package kr.kkaegul.core.harass;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Locs;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 괴롭히기 / 즉사 / 순회공연 / 행동 고정.
 * 개인 괴롭히기(333): config harass.personal.<id>.weight
 * 섭주 전체 괴롭히기(섭주 333): config harass.server.<id>.weight
 */
public final class HarassService {
    private final KkaegulCore plugin;
    private final Map<UUID, Long> frozenUntil = new ConcurrentHashMap<>();
    /** 낙하 데미지 면제 (하늘 텔포 후) */
    public final Set<UUID> noFall = ConcurrentHashMap.newKeySet();
    /** 플러그인이 죽인 플레이어 → 인벤 유지 */
    public final Set<UUID> keepInv = ConcurrentHashMap.newKeySet();
    private final Set<UUID> touring = ConcurrentHashMap.newKeySet();

    public HarassService(KkaegulCore plugin) { this.plugin = plugin; }

    private String pick(String path) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        Map<String, Double> w = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) w.put(k, s.getDouble(k + ".weight", 0));
        return Rng.weighted(w);
    }

    // ───────── 개인 ─────────
    /** 룰렛 칸 목록 (가중치 0 은 뺌) */
    private List<Object> tiles(String path) {
        List<Object> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        if (s != null) for (String k : s.getKeys(false)) if (s.getDouble(k + ".weight", 0) > 0) out.add(k);
        return out;
    }

    private static final long SPIN_TICKS = 112;  // 괴롭히기 룰렛 연출(약 5.4초)이 사라진 뒤에 실제 벌칙

    private Json rouletteFx(String mode, String path, String id, String label) {
        return Json.obj().put("mode", mode).put("tiles", tiles(path)).put("result", id).put("resultText", Txt.strip(Txt.color(label))).put("spinMs", 2800);
    }

    public void personal(Player p) {
        String id = pick("harass.personal");
        if (id == null) return;
        String label = plugin.getConfig().getString("harass.personal." + id + ".label", id);
        if (plugin.ui().hasRenderer(p)) {
            plugin.ui().fx(p, "harass_roulette", rouletteFx("personal", "harass.personal", id, label));
            UUID u = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> { Player now = Bukkit.getPlayer(u); if (now != null) personalEffect(now, id, label); }, SPIN_TICKS);
        } else {
            plugin.ui().center(p, "&c개인 괴롭히기", "&f" + label);
            personalEffect(p, id, label);
        }
    }

    private void personalEffect(Player p, String id, String label) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("harass.personal." + id);
        // 보물광산 안에서는 개인 괴롭히기 중 이동(끌려가기·날리기)은 안 먹힘 (섭주 괴롭히기는 그대로)
        if (plugin.mine().isInMine(p) && (id.equals("tp_random_user") || id.equals("launch"))) {
            plugin.ui().actionbar(p, "&7광산 안이라 " + Txt.strip(Txt.color(label)) + "은(는) 적용되지 않았어요");
            return;
        }
        switch (id) {
            case "monsters" -> spawnMonsters(p, c == null ? 3 : c.getInt("count", 3));
            case "mining_fatigue" -> p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,
                    (c == null ? 60 : c.getInt("seconds", 60)) * 20, c == null ? 1 : c.getInt("level", 1)));
            case "tp_random_user" -> tpRandomUser(p);
            case "freeze" -> freeze(p, c == null ? 3 : c.getInt("seconds", 3));
            case "delete_slot" -> deleteRandomSlot(p);
            case "launch" -> launch(p, c == null ? 20 : c.getInt("min", 20), c == null ? 50 : c.getInt("max", 50));
            default -> plugin.actions().runAll(p.getUniqueId(), plugin.getConfig().getStringList("harass.personal." + id + ".actions"), label);
        }
    }

    private void spawnMonsters(Player p, int count) {
        if (plugin.safeZone(p.getLocation())) {   // 로비·마을에서는 소환 안 함
            plugin.ui().actionbar(p, "&7여기서는 몬스터가 나오지 않아요");
            return;
        }
        List<String> types = plugin.getConfig().getStringList("harass.monster-types");
        if (types.isEmpty()) types = List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "SLIME");
        for (int i = 0; i < count; i++) {
            try {
                EntityType t = EntityType.valueOf(Rng.pick(types).toUpperCase());
                Location l = p.getLocation().clone().add(Rng.range(-3, 3), 0, Rng.range(-3, 3));
                l.setY(p.getLocation().getY());
                p.getWorld().spawnEntity(l, t);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("몬스터 종류 오류: " + e.getMessage());
            }
        }
    }

    public void tpRandomUser(Player p) {
        if (plugin.data().get(p).inJail) return;
        List<Player> others = new ArrayList<>(Bukkit.getOnlinePlayers());
        others.remove(p);
        others.removeIf(o -> plugin.data().get(o).inJail || plugin.minigames().busy(o) || plugin.mine().isInMine(o));   // 감옥·게임 중인 사람에게는 안 감
        Player t = Rng.pick(others);
        if (t == null) return;
        saveBack(p);
        p.teleport(t.getLocation());
        plugin.ui().error(p, "&c" + plugin.displayName(t) + "&f님에게 끌려갔어요!");
    }

    public void freeze(Player p, int seconds) {
        frozenUntil.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        plugin.ui().actionbar(p, "&b" + seconds + "초 행동 고정!");
    }

    public boolean isFrozen(Player p) {
        Long until = frozenUntil.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) { frozenUntil.remove(p.getUniqueId()); return false; }
        return true;
    }

    private void deleteRandomSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) slots.add(i);
        }
        Integer s = Rng.pick(slots);
        if (s == null) return;
        ItemStack it = inv.getItem(s);
        inv.setItem(s, null);
        String name = it == null ? "" : (it.hasItemMeta() && it.getItemMeta().hasDisplayName() ? it.getItemMeta().getDisplayName() : it.getType().name());
        plugin.ui().center(p, "&4인벤토리 한 칸 삭제!", "&f" + Txt.strip(name) + " x" + (it == null ? 0 : it.getAmount()));
        plugin.announce("&4[극악] &f" + plugin.displayName(p) + "님의 인벤토리 한 칸이 사라졌습니다...", true);
    }

    /** 높게 올렸다 떨구기 (요청으로 기본 가중치 0) — 머리 위가 뚫려 있을 때만 */
    private void launch(Player p, int min, int max) {
        int h = Rng.range(min, max);
        Location to = p.getLocation().clone().add(0, h, 0);
        if (!clearColumn(p.getLocation(), h)) return;
        p.teleport(to);
    }

    /** 섭주 하늘 텔포: 위로 n칸이 비어 있을 때만. 낙하 데미지 면제 옵션 */
    public void skyTp(Player p, int up) {
        // 감옥·미니게임·광산 중인 사람은 제외 (감옥에서 하늘로 가면 갇힌 구역이 꼬임)
        if (plugin.data().get(p).inJail || plugin.minigames().busy(p) || plugin.mine().isInMine(p)) return;
        Location to = p.getLocation().clone().add(0, up, 0);
        if (!clearColumn(p.getLocation(), up)) {   // 머리 위가 막혀 있으면 (실내·지하) 지면 위로 up 칸
            int top = p.getWorld().getHighestBlockYAt(p.getLocation().getBlockX(), p.getLocation().getBlockZ());
            to.setY(top + 1 + up);
        }
        double cap = p.getWorld().getMaxHeight() - 3;
        if (to.getY() > cap) to.setY(cap);
        if (to.getY() <= p.getLocation().getY() + 3) return;
        if (plugin.getConfig().getBoolean("harass.sky-no-fall-damage", true)) noFall.add(p.getUniqueId());
        p.teleport(to);
    }

    private boolean clearColumn(Location from, int up) {
        int x = from.getBlockX(), z = from.getBlockZ(), y0 = from.getBlockY() + 2;
        int top = Math.min(from.getWorld().getMaxHeight() - 1, from.getBlockY() + up + 1);
        for (int y = y0; y <= top; y++) {
            Block b = from.getWorld().getBlockAt(x, y, z);
            if (!b.isPassable()) return false;
        }
        return true;
    }

    // ───────── 섭주 전체 ─────────
    public void serverHarass() {
        String id = pick("harass.server");
        if (id == null) return;
        String label = plugin.getConfig().getString("harass.server." + id + ".label", id);
        Json fx = rouletteFx("owner", "harass.server", id, label);
        boolean any = false;
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(p)) { plugin.ui().fx(p, "harass_roulette", fx); any = true; }
        if (any) Bukkit.getScheduler().runTaskLater(plugin, () -> serverEffect(id, label), SPIN_TICKS);
        else serverEffect(id, label);
    }

    private void serverEffect(String id, String label) {
        plugin.announce("&8[&c전체 괴롭히기&8] &f" + label, true);
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("harass.server." + id);
        switch (id) {
            case "quiz" -> plugin.quiz().start(c == null ? "random" : c.getString("category", "random"));
            case "wasd" -> {
                int len = c == null ? 6 : c.getInt("length", 6), sec = c == null ? 6 : c.getInt("seconds", 6);
                String ok = c == null ? "small_decrease" : c.getString("success-roulette", "small_decrease");
                String fail = c == null ? "small_increase" : c.getString("fail-roulette", "small_increase");
                for (Player p : Bukkit.getOnlinePlayers())
                    plugin.wasd().start(p, len, sec,
                            pl -> { if (!ok.isEmpty()) plugin.roulette().spin(pl.getUniqueId(), ok, "WASD 성공"); },
                            pl -> { if (!fail.isEmpty()) plugin.roulette().spin(pl.getUniqueId(), fail, "WASD 실패"); });
            }
            case "sky" -> { for (Player p : Bukkit.getOnlinePlayers()) skyTp(p, c == null ? 50 : c.getInt("height", 50)); }
            case "fever" -> plugin.fever().startDiamond(0);
            case "all_jail" -> {
                for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) plugin.jail().send(p, "전체 감옥행");
                // 전체 감옥 뒤 위로: 랜덤 상자 1개씩 (우편함)
                String after = plugin.getConfig().getString("harass.all-jail-reward", "all box random 1");
                if (!after.isBlank()) plugin.actions().run(null, after, "전체 감옥 위로 상자");
            }
            case "lobby_kick" -> lobbyKick();
            default -> plugin.actions().runAll(null, plugin.getConfig().getStringList("harass.server." + id + ".actions"), label);
        }
    }

    /** 1%: 접속자 1명 랜덤 로비 강제이동 (이름 공개 + 초대장 연출) */
    public void lobbyKick() {
        Player t = Rng.pick(new ArrayList<>(Bukkit.getOnlinePlayers()));
        if (t == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.ui().center(p, "&5" + plugin.displayName(t), "&f님이 로비로 초대되었습니다");
        }
        plugin.ui().fx(t, "invite_letter", Json.obj().put("title", "깨굴이의 위험한 초대").put("to", plugin.displayName(t)).put("place", "로비"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!t.isOnline()) return;
            saveBack(t);
            Location lobby = plugin.places().lobby();
            if (lobby != null) t.teleport(lobby);
        }, plugin.getConfig().getLong("harass.lobby-kick-delay-ticks", 60));
    }

    // ───────── 즉사 / 순회공연 ─────────
    public void instantDeath(Player p) {
        PlayerData d = plugin.data().get(p);
        d.instantDeaths++;
        d.markDirty();
        if (plugin.getConfig().getBoolean("death.keep-inventory", true)) keepInv.add(p.getUniqueId());
        plugin.ui().center(p, "&4즉사", "");
        plugin.announce("&4[즉사] &f" + plugin.displayName(p) + "님이 즉사했습니다.", true);
        p.setHealth(0);
    }

    /** 대상 플레이어를 접속자 전원에게 3~5초씩 차례로 보내고 원래 자리로 복귀 */
    public void tour(Player p) {
        if (!touring.add(p.getUniqueId())) return;
        Location home = p.getLocation().clone();
        List<Player> stops = new ArrayList<>(Bukkit.getOnlinePlayers());
        stops.remove(p);
        if (stops.isEmpty()) { touring.remove(p.getUniqueId()); return; }
        plugin.announce("&d[순회공연] &f" + plugin.displayName(p) + "님의 전국 순회공연이 시작됩니다!", true);
        long delay = 0;
        for (Player s : stops) {
            long stay = Rng.range(plugin.getConfig().getInt("tour.min-seconds", 3), plugin.getConfig().getInt("tour.max-seconds", 5)) * 20L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !s.isOnline()) return;
                p.teleport(s.getLocation());
                plugin.ui().center(s, "&d" + plugin.displayName(p), "&f순회공연 왔어요!");
            }, delay);
            delay += stay;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            touring.remove(p.getUniqueId());
            if (p.isOnline()) p.teleport(home);
        }, delay);
    }

    public void saveBack(Player p) {
        PlayerData d = plugin.data().get(p);
        d.backLocation = Locs.toStr(p.getLocation());
        d.markDirty();
    }
}
