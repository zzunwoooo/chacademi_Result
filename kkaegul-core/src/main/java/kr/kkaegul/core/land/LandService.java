package kr.kkaegul.core.land;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 땅 (차카데미 땅문서 방식). 땅문서 = 토지 구매권(land_ticket), 단위 = 청크(16×16).
 * <ul>
 *   <li>들고 있으면: 주변 청크 경계(모드가 벽으로 그림) + 화면 위 가운데 작은 상태 이름표 (소유 가능 / 주인 / 제한 / 내 땅).</li>
 *   <li>들고 우클릭: 서 있는 청크가 소유 가능하면 1장 쓰고 내 땅. 아니면 쓰지 않고 빨간 알림.</li>
 *   <li>내 땅 안: 경계 얇은 선 + 오른쪽 위 땅 카드 → 관리 창 (들어온 사람 허용 / 허용 명단 빼기 / 회수 = 땅문서 돌려받기).</li>
 *   <li>보호: 주인·허용 명단만 부수기·놓기·상자·문·양동이·액자·동물. 폭발·불·피스톤·흘러드는 물/용암도 막음. 우회: kkaegul.land.bypass</li>
 * </ul>
 * 저장: plugins/KkaegulCore/lands.yml (메모리에 두고, 바뀌면 30초마다 저장).
 */
public final class LandService {
    public static final String FREE = "free", OWNED = "owned", BLOCKED = "blocked", MINE = "mine", TRUSTED = "trusted";

    public record Claim(String world, int cx, int cz, UUID owner, long at) {
        public String key() { return LandService.key(world, cx, cz); }
    }

    private final KkaegulCore plugin;
    private final File file;
    private final Map<String, Claim> claims = new HashMap<>();
    private final Map<UUID, List<Claim>> byOwner = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> trust = new HashMap<>();
    private final Map<UUID, String> lastSent = new HashMap<>();
    private boolean dirty;
    private final TownService town;

    public LandService(KkaegulCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lands.yml");
        this.town = new TownService(plugin);
        load();
        Bukkit.getPluginManager().registerEvents(new LandProtect(plugin, this), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 600L, 600L);
    }

    /** 마을(분양지) 월드 */
    public TownService town() { return town; }

    public static String key(String w, int cx, int cz) { return w + ";" + cx + ";" + cz; }

    // ───────── 저장 ─────────
    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection c = y.getConfigurationSection("claims");
        if (c != null) for (String k : c.getKeys(false)) {
            String[] p = k.split(";");
            if (p.length != 3) continue;
            try {
                put(new Claim(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), UUID.fromString(c.getString(k + ".owner", "")), c.getLong(k + ".at", 0)));
            } catch (RuntimeException ignored) { }
        }
        ConfigurationSection t = y.getConfigurationSection("trust");
        if (t != null) for (String k : t.getKeys(false)) {
            try {
                LinkedHashSet<UUID> set = new LinkedHashSet<>();
                for (String s : t.getStringList(k)) set.add(UUID.fromString(s));
                trust.put(UUID.fromString(k), set);
            } catch (RuntimeException ignored) { }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Claim c : claims.values()) {
            y.set("claims." + c.key() + ".owner", c.owner().toString());
            y.set("claims." + c.key() + ".at", c.at());
        }
        for (var e : trust.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            if (!l.isEmpty()) y.set("trust." + e.getKey(), l);
        }
        try { y.save(file); dirty = false; } catch (IOException e) { plugin.getLogger().warning("lands.yml 저장 실패: " + e); }
    }

    private void put(Claim c) {
        claims.put(c.key(), c);
        List<Claim> l = byOwner.computeIfAbsent(c.owner(), k -> new ArrayList<>());
        l.add(c);
        l.sort(Comparator.comparingLong(Claim::at));
    }

    private void remove(Claim c) {
        claims.remove(c.key());
        List<Claim> l = byOwner.get(c.owner());
        if (l != null) { l.removeIf(x -> x.key().equals(c.key())); if (l.isEmpty()) byOwner.remove(c.owner()); }
    }

    // ───────── 판정 ─────────
    public Claim at(Location l) { return l == null || l.getWorld() == null ? null : claims.get(key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4)); }
    public Claim at(Block b) { return b == null ? null : claims.get(key(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4)); }
    public Claim at(String w, int cx, int cz) { return claims.get(key(w, cx, cz)); }

    public List<Claim> of(UUID owner) { return byOwner.getOrDefault(owner, List.of()); }

    public Set<UUID> trusted(UUID owner) { return trust.getOrDefault(owner, new LinkedHashSet<>()); }

    public boolean bypass(Player p) { return p.hasPermission("kkaegul.land.bypass"); }

    /** 이 사람이 이 청크에서 블록을 만질 수 있는지 */
    public boolean can(Player p, Claim c) {
        if (c == null || bypass(p)) return true;
        return c.owner().equals(p.getUniqueId()) || trusted(c.owner()).contains(p.getUniqueId());
    }

    /** 이 청크를 살 수 없는 이유 (없으면 null) */
    public String blockedReason(String world, int cx, int cz) {
        if (town.isTown(world)) return town.isPlot(cx, cz) ? null : "도로에는 땅을 살 수 없어요";
        List<String> worlds = plugin.getConfig().getStringList("land.worlds");
        if (worlds.isEmpty()) worlds = List.of("world");
        if (!worlds.contains(world)) return "땅을 살 수 없는 곳이에요";
        double x0 = cx * 16, z0 = cz * 16;
        for (Map<?, ?> m : plugin.getConfig().getMapList("land.excluded")) {
            Object w = m.get("world");
            if (w != null && !w.toString().equals(world)) continue;
            double x = num(m.get("x")), z = num(m.get("z")), r = num(m.get("radius"));
            if (overlaps(x0, z0, x, z, r)) return "땅을 살 수 없는 구역이에요";
        }
        ConfigurationSection places = plugin.getConfig().getConfigurationSection("land.excluded-places");
        if (places != null) for (String name : places.getKeys(false)) {
            Location l = plugin.places().get(name);
            if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) continue;
            if (overlaps(x0, z0, l.getX(), l.getZ(), places.getDouble(name, 48))) return "땅을 살 수 없는 구역이에요";
        }
        return null;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : o == null ? 0 : Double.parseDouble(o.toString()); }

    private static boolean overlaps(double x0, double z0, double cx, double cz, double r) {
        double nx = Math.max(x0, Math.min(cx, x0 + 16)), nz = Math.max(z0, Math.min(cz, z0 + 16));
        double dx = nx - cx, dz = nz - cz;
        return dx * dx + dz * dz <= r * r;
    }

    public String status(Player viewer, String world, int cx, int cz) {
        Claim c = at(world, cx, cz);
        if (c != null) {
            if (c.owner().equals(viewer.getUniqueId())) return MINE;
            if (trusted(c.owner()).contains(viewer.getUniqueId())) return TRUSTED;
            return OWNED;
        }
        return blockedReason(world, cx, cz) == null ? FREE : BLOCKED;
    }

    public String ownerName(UUID u) {
        PlayerData d = plugin.data().get(u);
        return d == null ? "?" : plugin.nickname() == null ? d.name : plugin.nickname().display(d);
    }

    // ───────── 땅문서 ─────────
    public static boolean isDeed(KkaegulCore plugin, ItemStack it) { return plugin.items().type(it) == CustomItem.LAND_TICKET; }

    /** 들고 우클릭. 샀으면 true (그때만 1장 씀) */
    public boolean claim(Player p) {
        Location l = p.getLocation();
        String w = l.getWorld().getName();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        Claim c = at(w, cx, cz);
        if (c != null) {
            plugin.ui().error(p, c.owner().equals(p.getUniqueId()) ? "&c이미 내 땅이에요." : "&c" + ownerName(c.owner()) + "님의 땅이에요.");
            return false;
        }
        String why = blockedReason(w, cx, cz);
        if (why != null) { plugin.ui().error(p, "&c" + why); return false; }
        int max = plugin.getConfig().getInt("land.max-per-player", 0);
        if (max > 0 && of(p.getUniqueId()).size() >= max && !bypass(p)) { plugin.ui().error(p, "&c땅은 " + max + "칸까지 가질 수 있어요."); return false; }
        int tmax = town.maxPerPlayer();
        if (town.isTown(w) && tmax > 0 && !bypass(p) && of(p.getUniqueId()).stream().filter(x -> x.world().equals(w)).count() >= tmax) { plugin.ui().error(p, "&c마을 땅은 " + tmax + "칸까지 가질 수 있어요."); return false; }
        put(new Claim(w, cx, cz, p.getUniqueId(), System.currentTimeMillis()));
        dirty = true;
        plugin.ui().successSound(p);
        plugin.ui().center(p, "&f땅을 샀어요!", "&7청크 " + cx + ", " + cz + " · 내 땅 " + of(p.getUniqueId()).size() + "칸");
        p.sendMessage(Txt.color(Txt.notice("&f땅을 샀어요! &7(청크 " + cx + ", " + cz + ")")));
        plugin.getLogger().info("[땅] " + p.getName() + " 구매 " + w + " " + cx + "," + cz);
        lastSent.remove(p.getUniqueId());
        return true;
    }

    /** 서 있는 내 땅 회수 → 땅문서 1장 돌려받기 */
    public void release(Player p) {
        if (!plugin.getConfig().getBoolean("land.allow-release", false)) { plugin.ui().error(p, "&c땅은 회수할 수 없어요."); return; }
        Claim c = at(p.getLocation());
        if (c == null || !c.owner().equals(p.getUniqueId())) { plugin.ui().error(p, "&c내 땅 안에서만 회수할 수 있어요."); return; }
        remove(c);
        dirty = true;
        plugin.mail().giveOrMail(p, plugin.items().create(CustomItem.LAND_TICKET, 1));
        p.sendMessage(Txt.color(Txt.notice("&f땅을 회수했어요. 땅문서를 돌려받았어요. &7(청크 " + c.cx() + ", " + c.cz() + ")")));
        p.playSound(p.getLocation(), "minecraft:item.book.page_turn", 1f, 1f);
        plugin.ui().close(p, "land");
        lastSent.remove(p.getUniqueId());
        plugin.getLogger().info("[땅] " + p.getName() + " 회수 " + c.key());
    }

    /** 관리자 강제 회수: 이 땅을 주인 없이 (땅문서는 돌려주지 않음) */
    public void adminRevoke(Claim c, String by) {
        remove(c);
        dirty = true;
        plugin.getLogger().info("[땅] 관리자 " + by + " 강제 회수 " + c.key() + " (주인 " + ownerName(c.owner()) + ")");
    }

    /** 관리자 강제 회수: 이 사람 땅 전부 + 허용 명단. 회수한 땅 수 */
    public int adminRevokeAll(UUID owner, String by) {
        List<Claim> l = new ArrayList<>(of(owner));
        for (Claim c : l) adminRevoke(c, by);
        if (trust.remove(owner) != null) dirty = true;
        return l.size();
    }

    public void trust(Player owner, UUID who) {
        if (who.equals(owner.getUniqueId())) return;
        if (trust.computeIfAbsent(owner.getUniqueId(), k -> new LinkedHashSet<>()).add(who)) {
            dirty = true;
            String n = ownerName(who);
            owner.sendMessage(Txt.color(Txt.notice("&f" + n + "님이 내 땅을 같이 쓸 수 있어요.")));
            Player t = Bukkit.getPlayer(who);
            if (t != null) t.sendMessage(Txt.color(Txt.notice("&f" + plugin.displayName(owner) + "님이 땅을 같이 쓰게 해 줬어요.")));
        }
        refreshPanel(owner);
    }

    public void untrust(Player owner, UUID who) {
        Set<UUID> s = trust.get(owner.getUniqueId());
        if (s != null && s.remove(who)) {
            dirty = true;
            owner.sendMessage(Txt.color(Txt.notice("&7" + ownerName(who) + "님을 허용 명단에서 뺐어요.")));
        }
        refreshPanel(owner);
    }

    /** 내 집 자리 (처음 산 땅 한가운데), 없으면 null */
    public Location homeSpot(UUID owner) {
        List<Claim> l = of(owner);
        if (l.isEmpty()) return null;
        Claim c = l.get(0);
        World w = Bukkit.getWorld(c.world());
        return w == null ? null : new Location(w, c.cx() * 16 + 8.5, 0, c.cz() * 16 + 8.5);
    }

    /** 이동 → 마을: 처음 산 내 땅 한가운데 땅 위로 */
    public boolean home(Player p) {
        List<Claim> l = of(p.getUniqueId());
        if (l.isEmpty()) {   // 땅이 없으면 마을 분수 광장으로 (거기서 땅문서로 빈 땅 구매)
            World tw = town.enabled() ? Bukkit.getWorld(town.worldName()) : null;
            if (tw == null) { plugin.ui().error(p, "&c내 땅이 없어요."); return false; }
            Location from = p.getLocation();
            p.teleport(town.spawn(tw));
            plugin.ui().teleportSound(p, from);
            plugin.ui().center(p, "&f마을 광장", "&7땅문서를 들고 빈 땅에서 우클릭하면 내 땅이 돼요");
            return true;
        }
        Claim c = l.stream().filter(x -> town.isTown(x.world())).findFirst().orElse(l.get(0));   // 마을 땅 먼저
        World w = Bukkit.getWorld(c.world());
        if (w == null) { plugin.ui().error(p, "&c내 땅이 있는 월드를 찾을 수 없어요."); return false; }
        int x = c.cx() * 16 + 8, z = c.cz() * 16 + 8;
        Location to = new Location(w, x + .5, w.getHighestBlockYAt(x, z) + 1, z + .5, p.getLocation().getYaw(), 0);
        Location from = p.getLocation();
        p.teleport(to);
        plugin.ui().teleportSound(p, from);
        return true;
    }

    // ───────── 관리 창 ─────────
    public Json snapshot(Player owner) {
        UUID me = owner.getUniqueId();
        Set<UUID> tr = trusted(me);
        List<Object> inside = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(owner)) continue;
            Claim c = at(o.getLocation());
            if (c == null || !c.owner().equals(me)) continue;
            inside.add(Json.obj().put("uuid", o.getUniqueId().toString()).put("name", plugin.displayName(o)).put("account", o.getName()).put("trusted", tr.contains(o.getUniqueId())));
        }
        List<Object> tl = new ArrayList<>();
        for (UUID u : tr) {
            Player o = Bukkit.getPlayer(u);
            tl.add(Json.obj().put("uuid", u.toString()).put("name", ownerName(u)).put("account", o == null ? "" : o.getName()).put("online", o != null));
        }
        Claim here = at(owner.getLocation());
        return Json.obj().put("count", of(me).size()).put("inside", inside).put("trusted", tl)
                .put("here", here != null && here.owner().equals(me) ? here.cx() + ", " + here.cz() : "");
    }

    public void open(Player p) {
        Claim c = at(p.getLocation());
        if (c == null || !c.owner().equals(p.getUniqueId())) { plugin.ui().error(p, "&c내 땅 안에서 열 수 있어요."); return; }
        plugin.ui().open(p, "land", snapshot(p));
    }

    private void refreshPanel(Player p) { plugin.ui().update(p, "land", snapshot(p)); }

    // ───────── 0.5초마다: 모드에 상태 보내기 (바뀐 때만) ─────────
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.ui().hasRenderer(p)) continue;
            Location l = p.getLocation();
            if (l.getWorld() == null) continue;
            String w = l.getWorld().getName();
            int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
            boolean holding = isDeed(plugin, p.getInventory().getItemInMainHand());
            int r = Math.max(0, Math.min(3, plugin.getConfig().getInt("land.view-radius", 1)));
            Json m = Json.obj().put("type", "land").put("holding", holding).put("cx", cx).put("cz", cz).put("radius", r);
            StringBuilder sig = new StringBuilder().append(holding).append('|').append(w).append('|').append(cx).append(',').append(cz);
            if (holding) {
                List<Object> cells = new ArrayList<>();
                for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                    String st = status(p, w, cx + dx, cz + dz);
                    cells.add(st);
                    sig.append(st.charAt(0));
                }
                m.put("cells", cells);
                String st = status(p, w, cx, cz);
                Claim c = at(w, cx, cz);
                String label = switch (st) {
                    case FREE -> "소유 가능 · 우클릭으로 구매";
                    case BLOCKED -> "제한 구역";
                    case MINE -> "내 땅";
                    default -> ownerName(c.owner()) + "님의 땅";
                };
                m.put("status", st).put("label", label);
                sig.append(label);
            }
            Claim here = at(w, cx, cz);
            if (!holding && here != null && !here.owner().equals(p.getUniqueId())) {   // 남의 땅에 들어오면 주인 이름 (안 들고 있어도)
                String st = trusted(here.owner()).contains(p.getUniqueId()) ? TRUSTED : OWNED;
                String label = ownerName(here.owner()) + "님의 땅" + (st.equals(TRUSTED) ? " · 같이 쓰는 중" : "");
                m.put("status", st).put("label", label);
                sig.append("|in").append(label);
            }
            if (here != null && here.owner().equals(p.getUniqueId())) {
                m.put("mine", Json.obj().put("name", plugin.displayName(p)).put("count", of(p.getUniqueId()).size()));
                sig.append("|mine").append(of(p.getUniqueId()).size());
            }
            String s = sig.toString();
            if (s.equals(lastSent.get(p.getUniqueId()))) continue;
            lastSent.put(p.getUniqueId(), s);
            plugin.ui().send(p, m);
        }
    }

    public void forget(Player p) { lastSent.remove(p.getUniqueId()); }

    /** 엔티티가 서 있는 청크의 땅 */
    public Claim at(Entity e) { return at(e.getLocation()); }

    /** 상자·문 등 만지는 블록인지 */
    public static boolean interactable(Material m) {
        String n = m.name();
        return n.contains("CHEST") || n.contains("SHULKER_BOX") || n.contains("BARREL") || n.contains("FURNACE") || n.contains("SMOKER") || n.contains("HOPPER")
                || n.contains("DISPENSER") || n.contains("DROPPER") || n.contains("DOOR") || n.contains("GATE") || n.contains("BUTTON") || n.contains("LEVER")
                || n.contains("PRESSURE_PLATE") || n.contains("ANVIL") || n.contains("BREWING") || n.contains("BEACON") || n.contains("REPEATER") || n.contains("COMPARATOR")
                || n.contains("NOTE_BLOCK") || n.contains("JUKEBOX") || n.contains("LECTERN") || n.contains("CAMPFIRE") || n.contains("CAULDRON") || n.contains("COMPOSTER")
                || n.contains("BED") || n.contains("SIGN") || n.contains("FLOWER_POT") || n.contains("CAKE") || n.contains("RESPAWN_ANCHOR") || n.contains("DECORATED_POT")
                || n.contains("CHISELED_BOOKSHELF") || n.contains("CRAFTER") || n.equals("FARMLAND") || n.contains("TRIPWIRE") || n.contains("DAYLIGHT_DETECTOR");
    }
}
