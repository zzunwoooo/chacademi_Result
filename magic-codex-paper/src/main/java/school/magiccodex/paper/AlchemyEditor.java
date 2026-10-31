package school.magiccodex.paper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.AlchemyAdminProtocol;
import school.magiccodex.protocol.AlchemyAdminProtocol.*;
import school.magiccodex.protocol.AlchemyProtocol;

/**
 * Server side of the in-game alchemy recipe editor (/연금술관리 → the mod's AlchemyAdminScreen, same art as the
 * alchemy window). Every request checks magiccodex.alchemy.admin. The client sends a whole recipe draft; this class
 * validates it, writes alchemy.yml (Bukkit YAML keeps the comments), reloads at once and publishes it to the shared
 * copy (AlchemySharedConfig) so school and wild stay identical, putting the old file back if
 * the new one does not load or cannot be shared. Ingredients that the player slot picker would not show (first lore line is not 재료) are
 * added to settings.material-items so players can still put them in.
 */
final class AlchemyEditor implements PluginMessageListener, Listener {
    private final MagicCodexBridge plugin;
    private final AlchemyBridge bridge;
    private final Map<UUID, Long> limits = new HashMap<>();

    AlchemyEditor(MagicCodexBridge plugin, AlchemyBridge bridge) {
        this.plugin = plugin; this.bridge = bridge;
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, AlchemyAdminProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, AlchemyAdminProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** 편집 패킷: OP(관리 명령 기준) 또는 magiccodex.alchemy.admin. */
    private static boolean admin(Player p) { return p.isOp() || p.hasPermission("magiccodex.alchemy.admin"); }
    private static boolean empty(ItemStack i) { return i == null || i.getType().isAir() || i.getAmount() <= 0; }

    /** /컨텐츠관리 연금술 레시피편집: opens the editor on the client (OP는 허브가 검사). */
    void open(Player p) {
        if (!p.getListeningPluginChannels().contains(AlchemyAdminProtocol.RESPONSE)) { CmdKit.userFail(p, "최신 차카데미 모드가 있어야 레시피 편집 창을 열 수 있어요"); return; }
        send(p, AlchemyAdminProtocol.KIND_OPEN, "");
    }

    // ------------------------------------------------------------------ alchemy.yml

    private YamlConfiguration load() throws Exception { var y = new YamlConfiguration(); y.load(bridge.file()); return y; }

    private static String normalizeKey(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return v.isEmpty() || v.contains(":") ? v : "minecraft:" + v;
    }

    /** Every recipe in the file (switched-off ones too). Entries that cannot be shown are reported in `skipped`. */
    private List<Row> rows(List<String> skipped) {
        var list = new ArrayList<Row>();
        try {
            ConfigurationSection root = load().getConfigurationSection("recipes");
            if (root != null) for (String id : root.getKeys(false)) {
                try {
                    var s = Objects.requireNonNull(root.getConfigurationSection(id));
                    var ingredients = new LinkedHashMap<String, Integer>();
                    var is = s.getConfigurationSection("ingredients");
                    if (is != null) for (String k : is.getKeys(false)) ingredients.merge(normalizeKey(k), is.getInt(k, 0), Integer::sum);
                    var stacks = new ArrayList<AlchemyProtocol.Stack>();
                    ingredients.forEach((k, n) -> stacks.add(new AlchemyProtocol.Stack(k, n)));
                    String name = s.getString("name", id);
                    if (name.length() > AlchemyProtocol.MAX_NAME) name = name.substring(0, AlchemyProtocol.MAX_NAME);
                    list.add(new Row(id, name, normalizeKey(s.getString("result", "")), Math.clamp(s.getInt("amount", 1), 1, AlchemyAdminProtocol.MAX_RESULT_AMOUNT),
                            Math.clamp(s.getInt("exp", 0), 0, AlchemyProtocol.MAX_EXP), s.getBoolean("enabled", true), s.getInt("order", 0), stacks));
                } catch (RuntimeException e) { if (skipped != null) skipped.add(id); }
            }
        } catch (Exception e) { plugin.getLogger().warning("연금술 편집: alchemy.yml 읽기 실패: " + e.getMessage()); if (skipped != null) skipped.add("alchemy.yml(콘솔 확인)"); }
        list.sort(Comparator.comparingInt(Row::order).thenComparing(Row::id));
        return list;
    }

    // ------------------------------------------------------------------ state

    private void send(Player p, int kind, String notice) {
        if (!p.getListeningPluginChannels().contains(AlchemyAdminProtocol.RESPONSE)) return;
        var skipped = new ArrayList<String>();
        var rows = rows(skipped);
        // the operator's own items (main storage + armor/offhand are not offered: 0..35 like the alchemy window)
        var counts = new LinkedHashMap<String, Integer>(); var samples = new HashMap<String, ItemStack>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (empty(item)) continue;
            String key = bridge.keyOf(item);
            if (key == null || !AlchemyProtocol.validKey(key)) continue;
            counts.merge(key, item.getAmount(), Integer::sum); samples.putIfAbsent(key, item);
        }
        var held = new ArrayList<Held>();
        counts.forEach((k, n) -> held.add(new Held(k, n, bridge.material(samples.get(k), k))));
        var keys = new LinkedHashSet<String>(counts.keySet());
        for (Row r : rows) { if (!r.result().isEmpty()) keys.add(r.result()); r.ingredients().forEach(s -> keys.add(s.key())); }
        var items = new ArrayList<AlchemyProtocol.Item>();
        for (String k : keys) items.add(new AlchemyProtocol.Item(k, bridge.preview(k, samples.get(k))));
        String note = notice;
        if (note.isEmpty() && !skipped.isEmpty()) note = "읽지 못한 레시피: " + String.join(", ", skipped);
        if (note.length() > AlchemyProtocol.MAX_NOTICE) note = note.substring(0, AlchemyProtocol.MAX_NOTICE);
        byte[] frame;
        try { frame = AlchemyAdminProtocol.encode(new State(kind, bridge.slots(), items, held.size() > 64 ? held.subList(0, 64) : held, rows, note)); }
        catch (IllegalArgumentException e) { CmdKit.fail(p, "레시피 편집 정보를 보내지 못했어요", e); return; }
        p.sendPluginMessage(plugin, AlchemyAdminProtocol.RESPONSE, frame);
    }

    private void result(Player p, long seq, boolean ok, String id, String message) {
        if (message.length() > 300) message = message.substring(0, 300);
        p.sendPluginMessage(plugin, AlchemyAdminProtocol.RESPONSE, AlchemyAdminProtocol.encode(new Result(seq, ok, id == null ? "" : id, message)));
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!AlchemyAdminProtocol.REQUEST.equals(channel) || !p.getListeningPluginChannels().contains(AlchemyAdminProtocol.RESPONSE)) return;
        Request r;
        try { r = AlchemyAdminProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        if (r.action() == AlchemyAdminProtocol.CLOSE) return;
        if (!admin(p)) { result(p, r.sequence(), false, "", "관리자만 사용할 수 있습니다."); return; }
        long now = System.currentTimeMillis();
        if (now < limits.getOrDefault(p.getUniqueId(), 0L)) { if (r.action() != AlchemyAdminProtocol.QUERY) result(p, r.sequence(), false, "", "잠시 뒤 다시 시도해 주세요."); return; }
        limits.put(p.getUniqueId(), now + 250);
        switch (r.action()) {
            case AlchemyAdminProtocol.QUERY -> send(p, AlchemyAdminProtocol.KIND_STATE, "");
            case AlchemyAdminProtocol.RELOAD -> {
                String error = bridge.reloadAndPush();
                if (error != null) { result(p, r.sequence(), false, "", "alchemy.yml 오류: " + error); send(p, AlchemyAdminProtocol.KIND_STATE, ""); }
                else share(p, r.sequence(), bridge.localText(), "", "alchemy.yml 을 다시 불러왔습니다");
            }
            // results (and the refreshed state) are sent once the shared copy is updated
            case AlchemyAdminProtocol.SAVE -> save(p, r.sequence(), r.row());
            case AlchemyAdminProtocol.DELETE -> delete(p, r.sequence(), r.id());
            default -> { }
        }
    }

    @EventHandler public void quit(PlayerQuitEvent e) { limits.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ save / delete

    private void save(Player p, long seq, Row d) {
        if (d.result().isEmpty()) { result(p, seq, false, d.id(), "결과 아이템을 정해 주세요. (가운데 고리를 클릭)"); return; }
        var ingredients = new LinkedHashMap<String, Integer>();
        for (var s : d.ingredients()) ingredients.merge(s.key(), s.amount(), Integer::sum);
        if (ingredients.isEmpty()) { result(p, seq, false, d.id(), "재료를 하나 이상 넣어 주세요."); return; }
        if (ingredients.size() > bridge.slots()) { result(p, seq, false, d.id(), "재료 종류는 최대 " + bridge.slots() + "개입니다."); return; }
        for (var e : ingredients.entrySet()) {
            if (e.getValue() > AlchemyProtocol.MAX_AMOUNT) { result(p, seq, false, d.id(), "한 재료는 최대 " + AlchemyProtocol.MAX_AMOUNT + "개입니다."); return; }
            if (e.getKey().contains(".")) { result(p, seq, false, d.id(), "이 아이템은 레시피에 쓸 수 없습니다: " + e.getKey()); return; }
        }
        ItemStack resultItem = bridge.create(d.result(), 1);
        if (resultItem == null) { result(p, seq, false, d.id(), "결과 아이템(" + d.result() + ")을 서버에서 만들 수 없습니다."); return; }
        String name = d.name().strip();
        if (name.isEmpty()) name = AlchemyBridge.displayName(resultItem);
        if (name.isBlank()) name = d.result();
        if (name.length() > AlchemyProtocol.MAX_NAME) name = name.substring(0, AlchemyProtocol.MAX_NAME);
        var existing = rows(null);
        if (!d.id().isEmpty() && existing.stream().noneMatch(r -> r.id().equals(d.id()))) { result(p, seq, false, d.id(), "그 사이 삭제된 레시피입니다. 새 레시피로 다시 만들어 주세요."); return; }
        for (Row r : existing) {
            if (r.id().equals(d.id())) continue;
            var other = new LinkedHashMap<String, Integer>();
            r.ingredients().forEach(s -> other.merge(s.key(), s.amount(), Integer::sum));
            if (other.equals(ingredients)) { result(p, seq, false, d.id(), "'" + r.name() + "' 레시피와 재료가 완전히 같습니다. 재료나 개수를 바꿔 주세요."); return; }
        }

        File file = bridge.file();
        String before; YamlConfiguration yaml;
        try { before = Files.readString(file.toPath(), StandardCharsets.UTF_8); yaml = load(); }
        catch (Exception e) { plugin.getLogger().warning("연금술 편집: alchemy.yml 읽기 실패: " + e); result(p, seq, false, d.id(), "alchemy.yml 을 읽지 못했어요 (자세한 내용은 콘솔)"); return; }
        String id = d.id();
        int order = d.order();
        if (id.isEmpty()) {
            int n = 1; while (yaml.contains("recipes.recipe_" + n)) n++;
            id = "recipe_" + n;
            order = existing.stream().mapToInt(Row::order).max().orElse(0) + 1;
        }
        String path = "recipes." + id;
        // [기능:alchemy-overhaul 시작] 편집기에 없는 칸(first-title·once·note·hint)은 다시 저장해도 지우지 않는다
        var kept = new LinkedHashMap<String, Object>();
        for (String k : List.of("first-title", "once", "note", "hint")) if (yaml.contains(path + "." + k)) kept.put(k, yaml.get(path + "." + k));
        // [기능:alchemy-overhaul 끝]
        yaml.set(path, null);
        yaml.set(path + ".name", name);
        yaml.set(path + ".result", d.result());
        // [기능:alchemy-overhaul 시작] 장비로 등록한 아이템을 결과로 넣으면 등록 정보까지 그대로 저장 (없으면 아이템 종류만)
        kept.forEach((k, v) -> yaml.set(path + "." + k, v));
        String template = AlchemyPlus.templateOf(p, d.result(), bridge);
        if (template != null) yaml.set(path + ".result-item", template);
        // [기능:alchemy-overhaul 끝]
        yaml.set(path + ".amount", d.amount());
        yaml.set(path + ".exp", d.exp());
        yaml.set(path + ".order", order);
        yaml.set(path + ".enabled", d.enabled());
        var section = yaml.createSection(path + ".ingredients");
        ingredients.forEach(section::set);
        var materialItems = new ArrayList<>(yaml.getStringList("settings.material-items"));
        var added = new ArrayList<String>();
        for (String k : ingredients.keySet()) {
            ItemStack sample = null;
            for (int i = 0; i < 36 && sample == null; i++) { var it = p.getInventory().getItem(i); if (!empty(it) && k.equals(bridge.keyOf(it))) sample = it; }
            if (sample == null) sample = bridge.create(k, 1);
            if ((sample == null || !bridge.material(sample, k)) && !materialItems.contains(k)) { materialItems.add(k); added.add(k); }
        }
        if (!added.isEmpty()) yaml.set("settings.material-items", materialItems);
        try { yaml.save(file); } catch (Exception e) { plugin.getLogger().warning("연금술 편집: 저장 실패: " + e); result(p, seq, false, d.id(), "저장하지 못했어요 (자세한 내용은 콘솔)"); return; }
        String error = bridge.reloadAndPush();
        if (error != null) {
            try { Files.writeString(file.toPath(), before, StandardCharsets.UTF_8); } catch (Exception ignored) { }
            bridge.reloadAndPush();
            plugin.getLogger().warning("연금술 편집: 저장 뒤 불러오기 오류로 되돌림: " + error);
            result(p, seq, false, d.id(), "저장한 내용을 불러오지 못해 되돌렸어요 (자세한 내용은 콘솔)");
            return;
        }
        plugin.getLogger().info("ALCHEMY EDIT save by=" + p.getName() + " recipe=" + id);
        String message = "'" + name + "' 저장 완료" + (d.enabled() ? "" : " (꺼짐)");
        if (!added.isEmpty()) message += " · 재료로 등록 " + added.size() + "개";
        share(p, seq, before, id, message);
    }

    /** The edit is applied here; now make it the shared setting for both servers (or undo it here). */
    private void share(Player p, long seq, String before, String id, String message) {
        var shared = bridge.shared();
        shared.publish(bridge.localText(), "editor:" + p.getName(), failed -> {
            if (failed != null) {
                bridge.applyText(before);
                plugin.getLogger().warning("ALCHEMY EDIT not shared, reverted here: " + failed);
                plugin.getLogger().warning("연금술 편집: 공유 저장 실패: " + failed);
                if (p.isOnline()) { result(p, seq, false, id, "저장하지 못했어요 (자세한 내용은 콘솔)"); send(p, AlchemyAdminProtocol.KIND_STATE, ""); }
                return;
            }
            if (p.isOnline()) {
                result(p, seq, true, id, message + (shared.shared() ? " · 스쿨·야생에 바로 적용됩니다." : " · 이 서버에 적용 (공유 DB 없음)"));
                send(p, AlchemyAdminProtocol.KIND_STATE, "");
            }
        });
    }

    private void delete(Player p, long seq, String id) {
        File file = bridge.file();
        String before;
        try {
            before = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            var yaml = load();
            if (!yaml.contains("recipes." + id)) { result(p, seq, false, id, "이미 없는 레시피입니다."); return; }
            yaml.set("recipes." + id, null);
            yaml.save(file);
            String error = bridge.reloadAndPush();
            if (error != null) { Files.writeString(file.toPath(), before, StandardCharsets.UTF_8); bridge.reloadAndPush(); plugin.getLogger().warning("연금술 편집: 삭제 뒤 불러오기 오류로 되돌림: " + error); result(p, seq, false, id, "삭제 뒤 불러오기 오류로 되돌렸어요 (자세한 내용은 콘솔)"); return; }
        } catch (Exception e) { plugin.getLogger().warning("연금술 편집: 삭제 실패: " + e); result(p, seq, false, id, "삭제하지 못했어요 (자세한 내용은 콘솔)"); return; }
        plugin.getLogger().info("ALCHEMY EDIT delete by=" + p.getName() + " recipe=" + id);
        share(p, seq, before, "", "레시피를 삭제했습니다");
    }
}
