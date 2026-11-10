package kr.kkaegul.core.cosmetic;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.ItemIcon;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 치장품(모자·얼굴·등·손·풍선).
 *  - 정의는 plugins/KkaegulCore/cosmetics.yml (치장품 id = ItemsAdder namespaced id).
 *  - 엔티티를 붙이지 않는다. 클라 모드가 플레이어 렌더에 직접 그리고, 서버는 "누가 뭘 입었는지"만 알려 준다.
 *  - 서버 → 클라: cosmetic_defs(정의+아이콘), cosmetics(full=접속 때 전원 상태 / full=false=바뀐 사람 한 명), 옷장 창 wardrobe.
 *  - 치장 상자(boxes.cosmetic): 아직 없는 것 중 등급 가중치로 1개. 전부 가졌으면 상자를 쓰지 않는다.
 */
public final class CosmeticService {
    public static final List<String> SLOTS = List.of("head", "face", "back", "offhand", "balloon");
    public static final List<String> RARITIES = List.of("common", "uncommon", "rare", "legendary");
    public static final String BOX_KIND = "cosmetic";

    /** 치장품 정의 한 개 */
    public record Def(String id, String slot, String name, String description, String rarity) {}

    private final KkaegulCore plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    /** id → 아이콘 문자열. ItemsAdder 가 플러그인보다 늦게 뜰 수 있어 성공한 것만 담고, 빈 것은 다음에 다시 만든다 */
    private final Map<String, String> icons = new HashMap<>();
    /** 마지막으로 접속자 전원에게 보낸 정의의 아이콘 개수 → 늘어나면(IA 로딩 끝) 전원에게 다시 보냄 */
    private int broadcastIcons = -1;

    public CosmeticService(KkaegulCore plugin) {
        this.plugin = plugin;
        load();
    }

    // ───────── 정의 불러오기 ─────────

    private void load() {
        File f = new File(plugin.getDataFolder(), "cosmetics.yml");
        if (!f.exists()) plugin.saveResource("cosmetics.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        defs.clear();
        icons.clear();
        weights.clear();
        ConfigurationSection w = y.getConfigurationSection("weights");
        for (String r : RARITIES) weights.put(r, w == null ? 1 : Math.max(0, w.getInt(r, 0)));
        ConfigurationSection list = y.getConfigurationSection("list");
        if (list != null) for (String id : list.getKeys(false)) {
            ConfigurationSection c = list.getConfigurationSection(id);
            if (c == null) continue;
            String slot = c.getString("slot", "").toLowerCase(Locale.ROOT);
            if (!SLOTS.contains(slot)) { plugin.getLogger().warning("치장품 부위가 잘못됨 (무시): " + id + " slot=" + slot); continue; }
            String rarity = c.getString("rarity", "common").toLowerCase(Locale.ROOT);
            if (!RARITIES.contains(rarity)) rarity = "common";
            defs.put(id, new Def(id, slot, c.getString("name", id), c.getString("description", ""), rarity));
        }
        broadcastIcons = -1;
        plugin.getLogger().info("치장품 " + defs.size() + "개 로드");
    }

    /** /깨굴 리로드: 정의를 다시 읽고 접속자 전원에게 정의 + 전원 상태를 다시 보냄 */
    public void reload() {
        load();
        Json d = defsPayload();
        Json full = fullPayload();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.ui().hasRenderer(p)) continue;
            plugin.ui().send(p, d);
            plugin.ui().send(p, full);
        }
        broadcastIcons = resolvedIcons();
    }

    public Def def(String id) { return id == null ? null : defs.get(id); }

    public java.util.Collection<Def> defs() { return defs.values(); }

    public java.util.Set<String> ids() { return defs.keySet(); }

    private String icon(String id) {
        String c = icons.get(id);
        if (c != null) return c;
        String s;
        try { s = ItemIcon.of(plugin.items().itemsAdder().createAny(id, 1)); }
        catch (RuntimeException | LinkageError e) { s = ""; }
        if (!s.isEmpty()) icons.put(id, s);
        return s;
    }

    private int resolvedIcons() {
        int n = 0;
        for (String id : defs.keySet()) if (!icon(id).isEmpty()) n++;
        return n;
    }

    // ───────── 서버 → 클라 메시지 ─────────

    private Json defsPayload() {
        Json all = Json.obj();
        for (Def d : defs.values())
            all.put(d.id(), Json.obj().put("slot", d.slot()).put("name", d.name()).put("description", d.description())
                    .put("rarity", d.rarity()).put("icon", icon(d.id())));
        return Json.obj().put("type", "cosmetic_defs").put("defs", all);
    }

    /** 이 사람이 지금 입은 것 (정의에서 사라진 id 는 뺌) */
    private Json equippedOf(PlayerData d) {
        Json m = Json.obj();
        for (String slot : SLOTS) {
            String id = d.cosmeticsEquipped.get(slot);
            Def def = def(id);
            if (def != null && def.slot().equals(slot)) m.put(slot, id);
        }
        if (m.map().containsKey("head")) m.map().remove("face");
        return m;
    }

    private static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    private Json fullPayload() {
        Json players = Json.obj();
        for (Player o : Bukkit.getOnlinePlayers()) {
            Json eq = equippedOf(plugin.data().get(o));
            if (!eq.map().isEmpty()) players.put(key(o), eq);
        }
        return Json.obj().put("type", "cosmetics").put("full", true).put("players", players);
    }

    /** 이 사람 상태가 바뀌었을 때 접속자 전원에게 (빈 객체 = 다 벗음) */
    private void broadcastDelta(Player who) {
        Json payload = Json.obj().put("type", "cosmetics").put("full", false)
                .put("players", Json.obj().put(key(who), equippedOf(plugin.data().get(who))));
        for (Player o : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(o)) plugin.ui().send(o, payload);
    }

    /** IA 가 늦게 떠서 예전에 빈 아이콘을 보냈다면, 아이콘이 채워졌을 때 접속자 전원에게 정의를 다시 보냄. 다시 보냈으면 true */
    private boolean refreshDefsIfNeeded() {
        int now = resolvedIcons();
        if (broadcastIcons >= 0 && now <= broadcastIcons) return false;
        boolean first = broadcastIcons < 0;
        broadcastIcons = now;
        if (first) return false;   // 처음엔 각자 접속 때 받으므로 전원 재전송 필요 없음
        Json d = defsPayload();
        for (Player o : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(o)) plugin.ui().send(o, d);
        return true;
    }

    /**
     * 접속: 모드 채널 등록이 접속 직후엔 늦을 수 있어(이름표 pushLater 와 같은) 30틱 뒤에 보내고,
     * 그때도 렌더러가 안 보이면 1초 간격으로 몇 번 더 기다린다.
     * 받는 사람: 본인 ← 정의 + 전원 상태(full) / 다른 접속자 ← 이 사람 상태(full=false, 입은 게 있을 때만)
     */
    public void onJoin(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Json eq = equippedOf(plugin.data().get(p));
            if (p.isOnline() && !eq.map().isEmpty()) {
                Json payload = Json.obj().put("type", "cosmetics").put("full", false).put("players", Json.obj().put(key(p), eq));
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && plugin.ui().hasRenderer(o)) plugin.ui().send(o, payload);
            }
            sendJoin(p, 0);
        }, plugin.getConfig().getLong("cosmetics.join-delay-ticks", 30));
    }

    private void sendJoin(Player p, int tries) {
        if (!p.isOnline() || !plugin.isEnabled()) return;
        if (!plugin.ui().hasRenderer(p)) {
            if (tries < 5) Bukkit.getScheduler().runTaskLater(plugin, () -> sendJoin(p, tries + 1), 20L);
            return;
        }
        if (!refreshDefsIfNeeded()) plugin.ui().send(p, defsPayload());   // 전원 재전송했으면 p 도 이미 받음
        plugin.ui().send(p, fullPayload());
    }

    // ───────── 옷장 창 ─────────

    public Json wardrobeData(Player p) {
        PlayerData d = plugin.data().get(p);
        List<String> owned = new ArrayList<>();
        for (String id : d.cosmeticsOwned) if (defs.containsKey(id)) owned.add(id);
        return Json.obj().put("owned", owned).put("equipped", equippedOf(d))
                .put("counts", Json.obj().put("owned", owned.size()).put("total", defs.size()));
    }

    public void open(Player p) {
        if (!plugin.ui().hasRenderer(p)) {
            plugin.ui().error(p, "&c옷장은 깨굴 모드가 켜진 상태에서만 열 수 있어요.");
            return;
        }
        refreshDefsIfNeeded();
        plugin.ui().open(p, "wardrobe", wardrobeData(p));
    }

    private void sync(Player p) { if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "wardrobe", wardrobeData(p)); }

    /** 장착: 가진 것만, 그 부위에 (같은 부위 기존 것은 교체) */
    public void equip(Player p, String id) {
        Def def = def(id);
        PlayerData d = plugin.data().get(p);
        if (def == null) { plugin.ui().error(p, "&c없는 치장품이에요."); sync(p); return; }
        if (!d.cosmeticsOwned.contains(id)) { plugin.ui().error(p, "&c아직 가지고 있지 않은 치장품이에요."); sync(p); return; }
        if (!id.equals(d.cosmeticsEquipped.get(def.slot()))) {
            // 모자·얼굴은 둘 다 머리에 씌우는 것 → 머리에는 하나만 (하나 입으면 다른 쪽은 벗김)
            if (def.slot().equals("head")) d.cosmeticsEquipped.remove("face");
            if (def.slot().equals("face")) d.cosmeticsEquipped.remove("head");
            d.cosmeticsEquipped.put(def.slot(), id);
            d.markDirty();
            broadcastDelta(p);
        }
        sync(p);
    }

    /** 해제: 부위(slot) 기준. 치장품 id 를 보내도 그 부위를 벗김 */
    public void unequip(Player p, String slot) {
        String s = slot == null ? "" : slot.toLowerCase(Locale.ROOT);
        if (!SLOTS.contains(s)) { Def def = def(slot); s = def == null ? "" : def.slot(); }
        if (!SLOTS.contains(s)) { plugin.ui().error(p, "&c벗을 부위를 알 수 없어요."); sync(p); return; }
        PlayerData d = plugin.data().get(p);
        if (d.cosmeticsEquipped.remove(s) != null) {
            d.markDirty();
            broadcastDelta(p);
        }
        sync(p);
    }

    // ───────── 지급·회수 (관리자) ─────────

    /** 결과 문구를 돌려줌 */
    public String adminGive(PlayerData d, String id) {
        Def def = def(id);
        if (def == null) return "&c없는 치장품 id: " + id;
        if (!d.cosmeticsOwned.add(id)) return "&e이미 가지고 있어요: " + def.name();
        d.markDirty();
        Player p = Bukkit.getPlayer(d.uuid);
        if (p != null) { plugin.ui().center(p, "&d새 치장품: &f" + def.name(), "&7/옷장 에서 입을 수 있어요"); sync(p); }
        return "&a" + plugin.nickname().display(d) + " 님에게 치장품 지급: &f" + def.name();
    }

    public String adminTake(PlayerData d, String id) {
        if (!d.cosmeticsOwned.remove(id)) return "&e가지고 있지 않아요: " + id;
        boolean wasOn = d.cosmeticsEquipped.values().removeIf(id::equals);
        d.markDirty();
        Player p = Bukkit.getPlayer(d.uuid);
        if (p != null) { if (wasOn) broadcastDelta(p); sync(p); }
        Def def = def(id);
        return "&a" + plugin.nickname().display(d) + " 님 치장품 회수: &f" + (def == null ? id : def.name());
    }

    public List<String> adminList(PlayerData d) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String id : d.cosmeticsOwned) if (defs.containsKey(id)) n++;
        out.add("&6" + plugin.nickname().display(d) + " 님 치장품 &7(" + n + "/" + defs.size() + ")");
        for (String id : d.cosmeticsOwned) {
            Def def = def(id);
            if (def == null) { out.add("&8 - " + id + " (정의 없음)"); continue; }
            boolean on = id.equals(d.cosmeticsEquipped.get(def.slot()));
            out.add("&7 - &f" + def.name() + " &7" + id + " · " + def.slot() + " · " + def.rarity() + (on ? " &a(장착 중)" : ""));
        }
        return out;
    }

    // ───────── 치장 상자 ─────────

    /**
     * 치장 상자 열기. 가진 것 뺀 후보 중 등급 가중치로 1개 → 소유 추가 → 상자 연출(fx box_open).
     * 후보가 없으면(전부 보유) 알림만 하고 false → 아이템으로 열었으면 상자를 쓰지 않는다.
     */
    public boolean openBox(Player p) {
        PlayerData d = plugin.data().get(p);
        Map<String, List<Def>> byRarity = new LinkedHashMap<>();
        for (Def def : defs.values()) if (!d.cosmeticsOwned.contains(def.id())) byRarity.computeIfAbsent(def.rarity(), k -> new ArrayList<>()).add(def);
        if (byRarity.isEmpty()) {
            plugin.ui().center(p, "&d모든 치장품을 모았어요!", "&7상자는 쓰지 않았어요");
            return false;
        }
        Map<String, Integer> w = new LinkedHashMap<>();
        for (String r : byRarity.keySet()) w.put(r, weights.getOrDefault(r, 0));
        String rarity = Rng.weighted(w);
        List<Def> pool = rarity == null ? null : byRarity.get(rarity);
        if (pool == null) {   // 남은 등급의 가중치가 전부 0 → 남은 것 중 아무거나
            pool = new ArrayList<>();
            for (List<Def> l : byRarity.values()) pool.addAll(l);
        }
        Def got = Rng.pick(pool);
        d.cosmeticsOwned.add(got.id());
        d.boxesOpened++;
        d.markDirty();

        ConfigurationSection c = plugin.getConfig().getConfigurationSection("boxes." + BOX_KIND);
        String label = c == null ? "치장 상자" : c.getString("label", "치장 상자");
        boolean rare = got.rarity().equals("rare") || got.rarity().equals("legendary");
        Json reward = Json.obj().put("id", got.id()).put("label", got.name()).put("rare", rare)
                .put("icon", icon(got.id())).put("rarity", got.rarity());
        Json payload = Json.obj().put("box", BOX_KIND).put("label", label).put("rewards", List.of(reward)).put("count", 1).put("fever", false);
        if (!plugin.ui().send(p, Json.obj().put("type", "fx").put("id", "box_open").put("data", payload)))
            p.sendMessage(Txt.color(Txt.notice("&d[" + label + "] &f" + got.name() + " &7(" + got.rarity() + ") · /옷장 에서 입을 수 있어요")));
        p.playSound(p.getLocation(), "minecraft:block.chest.open", 1f, 1f);
        List<String> loud = c == null ? List.of("legendary") : c.getStringList("broadcast-rarities");
        if (loud.contains(got.rarity()))
            plugin.announce(Txt.fmt(plugin.getConfig().getString("boxes.rare-broadcast", "&d[대박] &f{name}님이 {box}에서 &e{item}&f 획득!"),
                    "name", plugin.displayName(p), "box", label, "item", got.name()), true);
        return true;
    }
}
