package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.AlchemyProtocol;
import school.magiccodex.protocol.AlchemyProtocol.*;
import school.magiccodex.protocol.AlchemyRules;

/**
 * 연금술 (2026-10-16): crafting that costs vanilla experience points, with hidden recipes.
 *
 * - Recipes live in plugins/MagicCodexBridge/alchemy.yml (/연금술관리 새로고침). Order of ingredients never matters:
 *   a recipe is "which items, how many in total".
 * - The slot picker only offers items whose first lore line is a material category (settings.material-categories,
 *   default 재료) or that are listed in settings.material-items.
 * - Pressing 합성 with a recipe's exact ingredients and enough experience crafts it: ingredients and experience are
 *   taken, the result goes to the inventory and
 *   whatever does not fit is sent to the mailbox. A wrong mix costs nothing; if it is one ingredient away from a
 *   recipe (or has the right ingredients in the wrong amounts) the player gets a hint instead.
 * - A recipe is discovered (LuckPerms magiccodex.alchemy.recipe.&lt;id&gt;) only by its first successful craft. Without
 *   enough experience nothing happens: no discovery, nothing spent (owner decision 2026-10-16).
 * - Undiscovered recipes never reach the client (it only learns how many are still locked).
 * Everything runs on the main thread (plugin messages, commands, the refresh timer).
 */
final class AlchemyBridge implements PluginMessageListener, Listener {
    private record Recipe(String id, String name, String result, int amount, int exp, Map<String, Integer> ingredients, int order) {}
    private record Inventory(Map<String, Integer> counts, Map<String, ItemStack> samples, Set<String> materials) {}

    private static final String PERMISSION = "magiccodex.alchemy.recipe.";
    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private volatile List<Recipe> recipes = List.of();
    private Set<String> categories = Set.of("재료"), materialItems = Set.of();
    private int slots = 6;
    private boolean nearHint = true;
    private String mailTitle = "연금술 결과물", mailBody = "인벤토리가 가득 차서 연금술 결과물을 우편으로 보내 드립니다.";
    private final Map<String, String> messages = new HashMap<>();
    /** key -> serialized icon (canonical item from ItemsAdder / vanilla, else the first stack seen). Cleared on reload. */
    private final Map<String, byte[]> previews = new HashMap<>();
    /** Discoveries sent to LuckPerms but maybe not visible as a permission yet. */
    private final Map<UUID, Set<String>> pendingGrants = new HashMap<>();
    /** Players with the window open -> last state signature (resend only when it changes). */
    private final Map<UUID, Integer> viewers = new HashMap<>();
    private final Map<UUID, Long> limits = new HashMap<>();
    private AlchemyEditor editor;
    private AlchemySharedConfig shared;
    // [기능:alchemy-overhaul 시작] 연금 쪽지·수첩·최초 발견 칭호·한 번만 만드는 레시피·장비 결과물 (AlchemyPlus)
    private final AlchemyPlus plus;
    // [기능:alchemy-overhaul 끝]

    AlchemyBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        itemsAdder = new ItemsAdderItems(plugin.getLogger());
        // [기능:alchemy-overhaul 시작]
        plus = new AlchemyPlus(plugin, this);
        // [기능:alchemy-overhaul 끝]
        if (!new File(plugin.getDataFolder(), "alchemy.yml").exists()) plugin.saveResource("alchemy.yml", false);
        reload();
        editor = new AlchemyEditor(plugin, this);
        // school and wild share one alchemy.yml through the shared MariaDB (local file = this server's copy)
        shared = new AlchemySharedConfig(plugin, localText(), this::applyText);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, AlchemyProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, AlchemyProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        CmdKit.register(plugin, "/연금술·/유저관리 연금술·/컨텐츠관리 연금술·/테스트 연금술", this::commands);
        // Inventory or experience changed while the window is open (pickups, other plugins): resend, at most once a second.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (var id : List.copyOf(viewers.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) { viewers.remove(id); continue; }
                var inv = inventory(p);
                if (signature(p, inv) != viewers.get(id)) send(p, AlchemyProtocol.KIND_STATE, "", inv);
            }
        }, 20, 20);
    }

    // ------------------------------------------------------------------ config

    void reload() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "alchemy.yml"));
        int count = yaml.getInt("settings.slots", 6);
        if (count < 1 || count > AlchemyProtocol.MAX_SLOTS) throw new IllegalArgumentException("settings.slots 는 1~" + AlchemyProtocol.MAX_SLOTS);
        var cats = new HashSet<String>();
        for (String c : yaml.getStringList("settings.material-categories")) if (!normalize(c).isEmpty()) cats.add(normalize(c));
        var items = new HashSet<String>();
        for (String k : yaml.getStringList("settings.material-items")) items.add(key(k, "settings.material-items"));
        var msg = new HashMap<String, String>();
        var ms = yaml.getConfigurationSection("messages");
        for (String k : List.of("near", "near-amount", "nothing", "crafted", "discovered", "mailed", "no-exp", "no-exp-locked", "missing", "empty"))
            msg.put(k, ms == null ? "" : ms.getString(k, ""));
        var list = new ArrayList<Recipe>();
        var seen = new HashMap<Map<String, Integer>, String>();
        ConfigurationSection root = yaml.getConfigurationSection("recipes");
        if (root != null) for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) throw new IllegalArgumentException("recipes." + id + " 형식 오류");
            if (!s.getBoolean("enabled", true)) continue;
            if (!AlchemyProtocol.validId(id)) throw new IllegalArgumentException("레시피 아이디는 영문 소문자·숫자·_ 만: " + id);
            String name = s.getString("name", "").strip();
            if (name.isEmpty() || name.length() > AlchemyProtocol.MAX_NAME) throw new IllegalArgumentException(id + ".name 은 1~" + AlchemyProtocol.MAX_NAME + "자");
            String result = key(s.getString("result", ""), id + ".result");
            int amount = s.getInt("amount", 1);
            if (amount < 1 || amount > 576) throw new IllegalArgumentException(id + ".amount 는 1~576");
            int exp = s.getInt("exp", 0);
            if (exp < 0 || exp > AlchemyProtocol.MAX_EXP) throw new IllegalArgumentException(id + ".exp 는 0~" + AlchemyProtocol.MAX_EXP);
            var is = s.getConfigurationSection("ingredients");
            if (is == null || is.getKeys(false).isEmpty()) throw new IllegalArgumentException(id + ".ingredients 가 비어 있음");
            var ingredients = new LinkedHashMap<String, Integer>();
            for (String raw : is.getKeys(false)) {
                int n = is.getInt(raw, 0);
                if (n < 1 || n > AlchemyProtocol.MAX_AMOUNT) throw new IllegalArgumentException(id + ".ingredients." + raw + " 개수는 1~" + AlchemyProtocol.MAX_AMOUNT);
                ingredients.merge(key(raw, id + ".ingredients"), n, Integer::sum);
            }
            if (ingredients.size() > count) throw new IllegalArgumentException(id + ": 재료 종류(" + ingredients.size() + ")가 칸 수(" + count + ")보다 많음");
            for (int n : ingredients.values()) if (n > AlchemyProtocol.MAX_AMOUNT) throw new IllegalArgumentException(id + ": 한 재료는 최대 " + AlchemyProtocol.MAX_AMOUNT + "개");
            String clash = seen.putIfAbsent(Map.copyOf(ingredients), id);
            if (clash != null) throw new IllegalArgumentException(id + ": " + clash + " 와 재료가 똑같음");
            list.add(new Recipe(id, name, result, amount, exp, Collections.unmodifiableMap(ingredients), s.getInt("order", 0)));
        }
        if (list.size() > AlchemyProtocol.MAX_RECIPES) throw new IllegalArgumentException("레시피는 최대 " + AlchemyProtocol.MAX_RECIPES + "개");
        list.sort(Comparator.comparingInt(Recipe::order).thenComparing(Recipe::id));
        // [기능:alchemy-overhaul 시작] first-title·once·note·hint·result-item·문구 (틀리면 이 reload 전체 거절)
        plus.load(yaml);
        // [기능:alchemy-overhaul 끝]
        // commit only after everything parsed
        recipes = List.copyOf(list); slots = count; categories = Set.copyOf(cats); materialItems = Set.copyOf(items);
        nearHint = yaml.getBoolean("settings.near-hint", true);
        mailTitle = yaml.getString("settings.mail-title", mailTitle); mailBody = yaml.getString("settings.mail-body", mailBody);
        messages.clear(); messages.putAll(msg);
        previews.clear();
    }

    /** "item:lily" (ItemsAdder) or "minecraft:sugar"; a bare "sugar" means minecraft:sugar. */
    private static String key(String raw, String where) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!v.contains(":")) v = "minecraft:" + v;
        if (!AlchemyProtocol.validKey(v)) throw new IllegalArgumentException(where + ": 아이템 표기 오류 '" + raw + "' (예: item:lily, minecraft:sugar)");
        return v;
    }

    private static String normalize(String category) {
        return category == null ? "" : category.replaceAll("[\\[\\]()<>{}「」『』【】〔〕]", "").strip();
    }

    private String message(String key, Recipe r, int amount, String item) {
        String m = messages.getOrDefault(key, "");
        if (m.isEmpty() && key.equals("no-exp-locked")) m = "경험치가 부족해 마법진이 반응하지 않는다.";
        if (r != null) m = m.replace("{name}", r.name()).replace("{exp}", Integer.toString(r.exp()));
        m = m.replace("{amount}", Integer.toString(amount)).replace("{item}", item == null ? "" : item);
        return m.length() > AlchemyProtocol.MAX_NOTICE ? m.substring(0, AlchemyProtocol.MAX_NOTICE) : m;
    }

    // ------------------------------------------------------------------ items

    private static boolean empty(ItemStack i) { return i == null || i.getType().isAir() || i.getAmount() <= 0; }

    /** The item's alchemy key (ItemsAdder id first, else the vanilla id), or null for air. */
    String keyOf(ItemStack item) {
        if (empty(item)) return null;
        String ia = itemsAdder.idOf(item);
        if (ia != null) { String k = ia.toLowerCase(Locale.ROOT); if (AlchemyProtocol.validKey(k)) return k; }
        return item.getType().getKey().toString();
    }

    /** Category 재료: first lore line (brackets ignored) is a material category, or the key is listed in material-items. */
    boolean material(ItemStack item, String key) {
        if (materialItems.contains(key)) return true;
        var meta = item.getItemMeta();
        var lore = meta == null ? null : meta.lore();
        if (lore == null || lore.isEmpty() || lore.getFirst() == null) return false;
        return categories.contains(normalize(PlainTextComponentSerializer.plainText().serialize(lore.getFirst())));
    }

    /** A new stack of this key (ItemsAdder or vanilla), or null when it does not exist. */
    ItemStack create(String key, int amount) {
        if (key.startsWith("minecraft:")) {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(key);
            if (m == null || m.isAir() || !m.isItem()) return null;
            return new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        }
        return itemsAdder.create(key, amount);
    }

    byte[] preview(String key, ItemStack fallback) {
        byte[] cached = previews.get(key);
        if (cached != null) return cached;
        ItemStack canonical = create(key, 1);
        ItemStack source = canonical != null ? canonical : fallback;
        byte[] bytes = source == null ? new byte[0] : serialize(source);
        if (canonical != null || fallback != null) previews.put(key, bytes);
        return bytes;
    }

    /** Icon bytes within MAX_PREVIEW: full stack, then without lore/data, then the bare material. */
    private static byte[] serialize(ItemStack item) {
        try {
            ItemStack one = item.clone(); one.setAmount(1);
            byte[] b = one.serializeAsBytes();
            if (b.length <= AlchemyProtocol.MAX_PREVIEW) return b;
            var meta = one.getItemMeta();
            if (meta != null) {
                meta.lore(null);
                var pdc = meta.getPersistentDataContainer();
                for (var k : List.copyOf(pdc.getKeys())) pdc.remove(k);
                one.setItemMeta(meta);
                b = one.serializeAsBytes();
                if (b.length <= AlchemyProtocol.MAX_PREVIEW) return b;
            }
            b = new ItemStack(item.getType()).serializeAsBytes();
            return b.length <= AlchemyProtocol.MAX_PREVIEW ? b : new byte[0];
        } catch (RuntimeException e) { return new byte[0]; }
    }

    static String displayName(ItemStack item) {
        if (item == null) return "";
        var meta = item.getItemMeta();
        String n = meta != null && meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
                : meta != null && meta.hasItemName() ? PlainTextComponentSerializer.plainText().serialize(meta.itemName())
                : item.getType().getKey().getKey().replace('_', ' ');
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Main storage only (hotbar + 27): armor, offhand and the equipment window's accessories are never used. */
    private Inventory inventory(Player p) {
        var counts = new LinkedHashMap<String, Integer>(); var samples = new HashMap<String, ItemStack>(); var materials = new LinkedHashSet<String>();
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            String key = keyOf(item);
            if (key == null) continue;
            counts.merge(key, item.getAmount(), Integer::sum);
            samples.putIfAbsent(key, item);
            if (material(item, key)) materials.add(key);
        }
        return new Inventory(counts, samples, materials);
    }

    // ------------------------------------------------------------------ discovery

    private boolean discovered(Player p, String id) {
        String node = PERMISSION + id;
        // isPermissionSet: an operator does not count as having discovered everything (unregistered nodes default to op).
        return (p.isPermissionSet(node) && p.hasPermission(node)) || (p.isPermissionSet(PERMISSION + "*") && p.hasPermission(PERMISSION + "*"))
                || pendingGrants.getOrDefault(p.getUniqueId(), Set.of()).contains(id);
    }

    private void discover(Player p, Recipe r) {
        UUID uid = p.getUniqueId();
        pendingGrants.computeIfAbsent(uid, k -> new HashSet<>()).add(r.id());
        var future = ArtifactGrants.grant(uid, PERMISSION + r.id());
        if (future == null) { plugin.getLogger().warning("ALCHEMY DISCOVERY NOT SAVED (LuckPerms 없음) player=" + p.getName() + " recipe=" + r.id()); return; }
        future.whenComplete((ok, error) -> {
            if (error != null) plugin.getLogger().warning("ALCHEMY DISCOVERY NOT SAVED player=" + uid + " recipe=" + r.id() + " " + error);
        });
    }

    // ------------------------------------------------------------------ state

    private int signature(Player p, Inventory inv) {
        int known = 0;
        for (Recipe r : recipes) if (discovered(p, r.id())) known = known * 31 + r.id().hashCode();
        return Objects.hash(inv.counts(), inv.materials(), known, recipes.size(), System.identityHashCode(recipes));
    }

    private void send(Player p, int kind, String notice) { send(p, kind, notice, inventory(p)); }

    private void send(Player p, int kind, String notice, Inventory inv) {
        if (!p.getListeningPluginChannels().contains(AlchemyProtocol.RESPONSE)) return;
        var known = new ArrayList<AlchemyProtocol.Recipe>(); int locked = 0;
        var keys = new LinkedHashSet<String>(inv.materials());
        for (Recipe r : recipes) {
            if (!discovered(p, r.id())) { locked++; continue; }
            var ingredients = new ArrayList<AlchemyProtocol.Stack>();
            r.ingredients().forEach((k, n) -> { ingredients.add(new AlchemyProtocol.Stack(k, n)); keys.add(k); });
            keys.add(r.result());
            known.add(new AlchemyProtocol.Recipe(r.id(), r.name(), r.result(), r.amount(), r.exp(), ingredients));
        }
        var items = new ArrayList<Item>(); var materials = new ArrayList<AlchemyProtocol.Material>();
        for (String k : keys) {
            items.add(new Item(k, preview(k, inv.samples().get(k))));
            if (inv.materials().contains(k) || inv.counts().containsKey(k)) materials.add(new AlchemyProtocol.Material(k, inv.counts().getOrDefault(k, 0), inv.materials().contains(k)));
        }
        byte[] frame;
        try { frame = AlchemyProtocol.encode(new State(kind, slots, items, materials, known, locked, notice)); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("연금술 상태 전송 실패 (" + p.getName() + "): " + e.getMessage()); return; }
        p.sendPluginMessage(plugin, AlchemyProtocol.RESPONSE, frame);
        if (viewers.containsKey(p.getUniqueId()) || kind == AlchemyProtocol.KIND_OPEN) viewers.put(p.getUniqueId(), signature(p, inv));
    }

    private void result(Player p, long sequence, int outcome, Recipe r, int amount, boolean mailed, String notice) {
        if (!p.getListeningPluginChannels().contains(AlchemyProtocol.RESPONSE)) return;
        p.sendPluginMessage(plugin, AlchemyProtocol.RESPONSE, AlchemyProtocol.encode(new Result(sequence, outcome,
                r == null ? "" : r.id(), r == null ? "" : r.result(), amount, mailed, notice == null ? "" : notice)));
    }

    // ------------------------------------------------------------------ requests

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!AlchemyProtocol.REQUEST.equals(channel) || !p.getListeningPluginChannels().contains(AlchemyProtocol.RESPONSE)) return;
        Request r;
        try { r = AlchemyProtocol.request(bytes); } catch (IllegalArgumentException e) { return; }
        if (r.action() == AlchemyProtocol.CLOSE) { viewers.remove(p.getUniqueId()); return; }
        long now = System.currentTimeMillis();
        if (now < limits.getOrDefault(p.getUniqueId(), 0L)) {
            if (r.action() == AlchemyProtocol.CRAFT) result(p, r.sequence(), AlchemyProtocol.REFUSED, null, 0, false, "잠시 뒤 다시 시도해 주세요.");
            return;
        }
        limits.put(p.getUniqueId(), now + (r.action() == AlchemyProtocol.CRAFT ? 400 : 200));
        if (r.action() == AlchemyProtocol.QUERY) { viewers.put(p.getUniqueId(), 0); send(p, AlchemyProtocol.KIND_STATE, ""); return; }
        craft(p, r);
    }

    private void craft(Player p, Request r) {
        long seq = r.sequence();
        if (!plugin.playerStateReady(p)) { result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, "캐릭터 정보를 불러오는 중입니다."); return; }
        if (!p.hasPermission("magiccodex.alchemy.use") || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) { result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, "지금은 연금술을 할 수 없습니다."); return; }
        // Never touch the inventory while a container or the vanilla cursor holds an item (same rule as the equipment window).
        if (p.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING || !empty(p.getItemOnCursor())) {
            result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, "다른 보관함을 닫은 뒤 다시 시도해 주세요."); return;
        }
        if (r.slots().size() > slots) { result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, "재료 칸이 바뀌었습니다. 창을 다시 열어 주세요."); return; }
        var want = AlchemyRules.merge(r.slots());
        if (want.isEmpty()) { result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, message("empty", null, 0, "")); return; }
        var inv = inventory(p);
        for (var e : want.entrySet()) if (inv.counts().getOrDefault(e.getKey(), 0) < e.getValue()) {
            ItemStack sample = inv.samples().get(e.getKey()); if (sample == null) sample = create(e.getKey(), 1);
            result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, message("missing", null, 0, sample == null ? e.getKey() : displayName(sample)));
            send(p, AlchemyProtocol.KIND_STATE, "", inv); return;
        }
        Recipe match = null;
        for (Recipe recipe : recipes) if (AlchemyRules.matches(recipe.ingredients(), want)) { match = recipe; break; }
        // 384 영감의 섬광 (LifeSpells): this attempt tells how many of the put-in ingredient kinds are right (number only).
        if (LifeSpells.take(p.getUniqueId(), "insight_flash")) LifeSpells.insight(p, want.size(), match != null ? want.size() : closest(want));
        if (match == null) {
            int hint = nearHint ? AlchemyRules.hint(recipes.stream().map(Recipe::ingredients).toList(), want) : AlchemyProtocol.NOTHING;
            String key = hint == AlchemyProtocol.NEAR_AMOUNT ? "near-amount" : hint == AlchemyProtocol.NEAR ? "near" : "nothing";
            // [기능:alchemy-overhaul 시작] 근접 힌트가 없을 때: 아직 모르는 레시피와 재료가 몇 종류 겹치는지 문구
            if (hint == AlchemyProtocol.NOTHING) { String partial = plus.matchMessage(p, want); if (partial != null) { result(p, seq, AlchemyProtocol.NEAR, null, 0, false, partial); return; } }
            // [기능:alchemy-overhaul 끝]
            result(p, seq, hint, null, 0, false, message(key, null, 0, "")); return;
        }
        boolean isNew = !discovered(p, match.id());
        // [기능:alchemy-overhaul 시작] 한 번만 만드는 레시피 (이미 발견 = 이미 한 번 만듦)
        String onceRefusal = plus.onceRefusal(match.id(), isNew);
        if (onceRefusal != null) { result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, onceRefusal); return; }
        // [기능:alchemy-overhaul 끝]
        long total = AlchemyRules.total(p.getLevel(), p.getExp());
        // 374 연금로 예열 (LifeSpells): the next craft within 10 minutes costs 30% less experience (used only by a successful craft).
        boolean preheat = match.exp() > 0 && LifeSpells.active(p.getUniqueId(), "alchemy_preheat");
        int cost = preheat ? LifeSpells.preheated(match.exp()) : match.exp();
        // Not enough experience: nothing happens at all (no discovery, nothing spent). A recipe is discovered only by a
        // successful craft. An undiscovered recipe's cost is not revealed.
        if (total < cost) {
            plugin.getLogger().info("ALCHEMY NO-EXP player=" + p.getName() + " recipe=" + match.id() + " exp=" + total + "/" + match.exp() + (isNew ? " undiscovered" : ""));
            result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, message(isNew ? "no-exp-locked" : "no-exp", isNew ? null : match, 0, ""));
            send(p, AlchemyProtocol.KIND_STATE, ""); return;
        }
        // results first: an unknown result item must not eat the ingredients
        var outputs = new ArrayList<ItemStack>();
        for (int left = match.amount(); left > 0; ) {
            // [기능:alchemy-overhaul 시작] 편집기가 저장한 장비 아이템(등록 정보 포함)이 있으면 그것을 복사
            ItemStack stack = plus.resultItem(match.id(), left);
            if (stack == null) stack = create(match.result(), left);
            // [기능:alchemy-overhaul 끝]
            if (stack == null) { plugin.getLogger().warning("ALCHEMY RESULT MISSING recipe=" + match.id() + " result=" + match.result()); result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, "결과 아이템을 만들 수 없습니다. 관리자에게 문의해 주세요."); return; }
            outputs.add(stack); left -= stack.getAmount();
        }
        // 결과물이 인벤토리에 다 들어가지 않으면 제작하지 않는다 (재료·경험치 차감 없음, 우편 대체 없음 — 2026-10-21 오너 결정)
        var storage = p.getInventory();
        // 공통 지급 칸 판정 (RewardDelivery, 2026-10-26): 아공간 배낭 쪽을 넘기는 중이거나 잠긴 쪽을 보고 있으면 27칸(9~35)에서
        // 재료를 빼거나 결과물을 넣지 않는다 — 이때는 "공간 부족"과 같이 제작하지 않는다(재료·경험치 차감 없음).
        if (!RewardDelivery.mainUsable(plugin, p) || !fitsAfterTaking(storage, want, outputs)) {
            plugin.getLogger().info("ALCHEMY NO-ROOM player=" + p.getName() + " recipe=" + match.id() + " amount=" + match.amount());
            String m = message("no-room", match, match.amount(), "");
            if (m.isBlank()) m = "인벤토리에 결과물을 넣을 자리가 없어요. 공간을 비운 뒤 다시 시도해 주세요.";
            result(p, seq, AlchemyProtocol.REFUSED, null, 0, false, m);
            CmdKit.notify(p, "warning", "alchemy-no-room", "인벤토리 공간이 부족해요", "결과물이 들어갈 자리를 비운 뒤 다시 제작해 주세요.", 0);
            send(p, AlchemyProtocol.KIND_STATE, ""); return;
        }
        // take ingredients (verified above) and experience
        for (var e : want.entrySet()) {
            int need = e.getValue();
            for (int i = 0; i < 36 && need > 0; i++) {
                ItemStack item = storage.getItem(i);
                if (!e.getKey().equals(keyOf(item))) continue;
                int take = Math.min(need, item.getAmount());
                need -= take;
                if (take == item.getAmount()) storage.setItem(i, null); else item.setAmount(item.getAmount() - take);
            }
        }
        long after = total - cost;
        float[] level = AlchemyRules.fromTotal(after);
        p.setLevel((int) level[0]); p.setExp(Math.min(level[1], 0.9999f)); p.setTotalExperience((int) Math.min(Integer.MAX_VALUE, after));
        var leftover = storage.addItem(outputs.toArray(ItemStack[]::new)).values();
        String craftId = UUID.randomUUID().toString();
        // fitsAfterTaking 이 자리를 확인했으므로 남는 양은 없어야 한다. 그래도 남으면(다른 플러그인 개입 등) 버리지 않고 기록만 남긴다.
        if (!leftover.isEmpty()) plugin.getLogger().severe("ALCHEMY LEFTOVER (자리 계산과 실제 지급이 다름) player=" + p.getUniqueId() + " craft=" + craftId + " items=" + leftover);
        p.updateInventory();
        plugin.getLogger().info("ALCHEMY CRAFT player=" + p.getName() + " recipe=" + match.id() + " amount=" + match.amount() + " exp=" + match.exp()
                + (isNew ? " new" : "") + (preheat ? " preheated=" + cost : "") + " craft=" + craftId);
        AuditStats.add(p.getUniqueId(), "연금술제작", 1);
        AuditLog.log(p, AuditLog.연금술, "연금술 제작 · " + match.name() + " x" + match.amount() + (isNew ? " (레시피 발견)" : "") + " · 경험치 " + (preheat ? cost : match.exp()),
                "레시피", match.id(), "이름", match.name(), "결과", match.result(), "수량", match.amount(), "경험치", preheat ? cost : match.exp(), "발견", isNew ? "예" : "아니오", "제작번호", craftId);
        if (preheat && LifeSpells.take(p.getUniqueId(), "alchemy_preheat"))
            CmdKit.notify(p, "system", "life-alchemy_preheat", "연금로 예열 효과", "경험치를 " + match.exp() + " 대신 " + cost + "만 썼어요", 0);
        String notice = message(isNew ? "discovered" : "crafted", match, match.amount(), "");
        if (isNew) discover(p, match);
        // [기능:alchemy-overhaul 시작] 서버 전체 최초 발견이면 칭호 + 공지
        if (isNew) plus.discovered(p, match.id(), match.name());
        // [기능:alchemy-overhaul 끝]
        result(p, seq, isNew ? AlchemyProtocol.DISCOVERED : AlchemyProtocol.CRAFTED, match, match.amount(), false, notice);
        send(p, AlchemyProtocol.KIND_STATE, "");
        // 스토리 "연금술성공": 재료·경험치를 걷고 결과물을 지급(인벤토리/우편)한 뒤에만. 경험치 부족·틀린 조합·결과 아이템 오류는 위에서 끝난다.
        StoryProgressEvent.fire(p, StoryProgressEvent.ALCHEMY);
    }

    /** 384: the most put-in ingredient kinds that one recipe (discovered or not) uses. */
    private int closest(Map<String, Integer> want) {
        int best = 0;
        for (Recipe r : recipes) {
            int n = 0;
            for (String k : want.keySet()) if (r.ingredients().containsKey(k)) n++;
            best = Math.max(best, n);
        }
        return best;
    }

    /** 재료를 뺀 뒤의 인벤토리(0~35칸)에 결과물이 전부 들어가는지. 실제 인벤토리는 건드리지 않는다. */
    private boolean fitsAfterTaking(org.bukkit.inventory.PlayerInventory storage, Map<String, Integer> want, List<ItemStack> outputs) {
        if (outputs.isEmpty()) return true;
        int[] after = new int[36], max = new int[36]; boolean[] similar = new boolean[36];
        var need = new HashMap<String, Integer>(want);
        ItemStack sample = outputs.getFirst();
        int total = outputs.stream().mapToInt(ItemStack::getAmount).sum();
        for (int i = 0; i < 36; i++) {
            ItemStack item = storage.getItem(i);
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            int amount = item.getAmount();
            String key = keyOf(item); Integer left = key == null ? null : need.get(key);
            if (left != null && left > 0) { int take = Math.min(left, amount); need.put(key, left - take); amount -= take; }
            after[i] = amount; max[i] = item.getMaxStackSize(); similar[i] = amount > 0 && item.isSimilar(sample);
        }
        return roomFor(after, max, similar, total, sample.getMaxStackSize());
    }

    /** 순수 계산: after[i]=재료를 뺀 뒤 칸 수량(0=빈칸), similar[i]=결과물과 합쳐지는 칸. 결과물 total 개가 들어갈 자리가 있는지. */
    static boolean roomFor(int[] after, int[] max, boolean[] similar, int total, int stack) {
        int size = Math.min(64, stack); if (total <= 0) return true; if (size <= 0) return false;
        long room = 0;
        for (int i = 0; i < after.length; i++) {
            if (after[i] <= 0) room += size;
            else if (similar[i]) room += Math.max(0, Math.min(size, Math.min(64, max[i])) - after[i]);
            if (room >= total) return true;
        }
        return false;
    }

    /** Results that did not fit: mailbox (9 attachments per mail). Without a mailbox they drop at the player's feet. */
    private void mail(Player p, List<ItemStack> items, String craftId, Recipe r) {
        var service = Bukkit.getServicesManager().load(MailService.class);
        if (service == null) { drop(p, items, "우편 기능 없음", craftId); return; }
        for (int part = 0; part * 9 < items.size(); part++) {
            var chunk = List.copyOf(items.subList(part * 9, Math.min(items.size(), part * 9 + 9)));
            String body = mailBody + " (" + r.name() + ")";
            try {
                service.sendSystem("alchemy:" + craftId + ":" + part, p.getUniqueId(), mailTitle, body, chunk).whenComplete((id, error) -> {
                    if (error != null) Bukkit.getScheduler().runTask(plugin, () -> drop(p, chunk, "우편 저장 실패 " + error.getClass().getSimpleName(), craftId));
                });
            } catch (RuntimeException e) { drop(p, chunk, "우편 거절 " + e.getMessage(), craftId); }
        }
    }

    private void drop(Player p, List<ItemStack> items, String why, String craftId) {
        plugin.getLogger().warning("ALCHEMY MAIL FAILED (" + why + ") player=" + p.getUniqueId() + " craft=" + craftId + " items=" + items);
        if (!p.isOnline()) return;
        for (ItemStack item : items) p.getWorld().dropItemNaturally(p.getLocation(), item);
        CmdKit.notify(p, "warning", "alchemy-drop", "연금술 결과물을 발밑에 떨어뜨렸어요", "우편으로 보내지 못했어요", 0);
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        viewers.remove(id); limits.remove(id); pendingGrants.remove(id);
    }

    // ------------------------------------------------------------------ commands

    int slots() { return slots; }
    // [기능:alchemy-overhaul 시작] AlchemyPlus 가 읽는 것 (Recipe 는 private 이라 값만 넘긴다)
    Map<String, Map<String, Integer>> recipeIngredients() { var out = new LinkedHashMap<String, Map<String, Integer>>(); for (Recipe r : recipes) out.put(r.id(), r.ingredients()); return out; }
    Map<String, String> recipeNameMap() { return recipeNames(); }
    boolean known(Player p, String id) { return discovered(p, id); }
    // [기능:alchemy-overhaul 끝]
    AlchemySharedConfig shared() { return shared; }

    /** This server's alchemy.yml text ("" when unreadable). */
    String localText() {
        try { return java.nio.file.Files.readString(file().toPath(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }

    /** Replace the local alchemy.yml with this text and reload; on a load error the previous file is put back. */
    String applyText(String text) {
        String before = localText();
        try { java.nio.file.Files.writeString(file().toPath(), text, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return "alchemy.yml 쓰기 실패: " + e.getMessage(); }
        String error = reloadAndPush();
        if (error != null) {
            try { java.nio.file.Files.writeString(file().toPath(), before, java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ignored) { }
            reloadAndPush();
        }
        return error;
    }

    void close() {
        if (shared != null) shared.close();
        // [기능:alchemy-overhaul 시작]
        plus.close();
        // [기능:alchemy-overhaul 끝]
    }
    File file() { return new File(plugin.getDataFolder(), "alchemy.yml"); }

    /** Reload alchemy.yml and refresh every open alchemy window; the error text, or null on success. */
    String reloadAndPush() {
        try { reload(); } catch (Exception e) { return String.valueOf(e.getMessage()); }
        for (var id : List.copyOf(viewers.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) send(p, AlchemyProtocol.KIND_STATE, ""); }
        return null;
    }

    // ------------------------------------------------------------------ 명령 (2026-10-20)

    private Map<String, String> recipeNames() { var out = new LinkedHashMap<String, String>(); for (Recipe r : recipes) out.put(r.id(), r.name()); return out; }
    private List<String> recipeChoices() { var out = new ArrayList<String>(); out.add("전체"); out.addAll(AdminCommandRules.catalogChoices(recipeNames())); return out; }

    private void commands() {
        ChacaApi.playerCommand(plugin, CmdKit.literal("연금술").executes(CmdKit.run(ctx -> { Player p = CmdKit.self(ctx); openWindow(p, p); })), "연금술 창 열기", List.of());
        var user = CmdKit.literal("연금술")
                .then(CmdKit.literal("레시피주기").then(CmdKit.word("레시피", this::recipeChoices).executes(CmdKit.run(ctx -> recipes(ctx, true)))))
                .then(CmdKit.literal("레시피회수").then(CmdKit.word("레시피", this::recipeChoices).executes(CmdKit.run(ctx -> recipes(ctx, false)))));
        ChacaApi.branch(plugin, ChacaApi.유저관리, user, "연금술 레시피 주기·회수", "연금술 레시피주기 전체");
        ChacaApi.branch(plugin, ChacaApi.컨텐츠관리, CmdKit.literal("연금술")
                .then(CmdKit.literal("레시피편집").executes(CmdKit.run(ctx -> editor.open(CmdKit.self(ctx)))))
                .then(CmdKit.literal("목록").executes(CmdKit.run(ctx -> {
                    var out = new ArrayList<String>();
                    out.add("── 연금술 레시피 (" + recipes.size() + "개) ──");
                    for (Recipe r : recipes) out.add(r.name() + "  (" + r.id() + ") → " + r.result() + " ×" + r.amount() + " · 경험치 " + r.exp() + " · 재료 " + r.ingredients());
                    CmdKit.lines(CmdKit.sender(ctx), out);
                }))), "연금술 레시피 편집 창·목록", "연금술 목록");
        var test = CmdKit.literal("연금술");
        for (String action : List.of("열기", "전체발견", "전체잊기")) {
            CmdKit.Body body = ctx -> {
                Player target = CmdKit.onlineOrSelf(ctx, "닉네임");
                if (action.equals("열기")) openWindow(CmdKit.sender(ctx), target);
                else change(CmdKit.sender(ctx), target.getUniqueId(), recipes.stream().map(Recipe::id).toList(), action.equals("전체발견"));
            };
            test.then(CmdKit.literal(action).executes(CmdKit.run(body)).then(CmdKit.player("닉네임").executes(CmdKit.run(body))));
        }
        ChacaApi.branch(plugin, ChacaApi.테스트, test, "연금술 창 열기·레시피 전체 발견·전체 잊기", "연금술 열기");
        ChacaApi.reload(plugin, "연금술", "alchemy.yml (연금술 레시피, 학교·야생 공유)", () -> {
            // this server's file (edited by hand) becomes the shared setting for both servers
            String error = reloadAndPush();
            if (error != null) throw new IllegalArgumentException("alchemy.yml: " + error);
            shared.publish(localText(), "reload", failed -> {
                if (failed != null) plugin.getLogger().warning("연금술: 이 서버에는 불러왔지만 다른 서버와 공유하지 못했습니다: " + failed);
            });
        });
    }

    private void recipes(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, boolean grant) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID target = ChacaApi.target(ctx);
        String input = CmdKit.text(ctx, "레시피");
        List<String> ids = input.equals("전체") ? recipes.stream().map(Recipe::id).toList() : List.of(CmdKit.pick(recipeNames(), input, "레시피"));
        change(CmdKit.sender(ctx), target, ids, grant);
    }

    /** 레시피 발견(LuckPerms 노드) 주기·회수. 오프라인 유저도 LuckPerms가 저장한다. */
    private void change(CommandSender sender, UUID target, List<String> ids, boolean grant) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (ids.isEmpty()) throw ChacaApi.error("등록된 레시피가 없어요");
        if (!ArtifactGrants.available()) throw ChacaApi.error("LuckPerms가 없어 레시피를 저장할 수 없어요");
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (String id : ids) {
            var future = grant ? ArtifactGrants.grant(target, PERMISSION + id) : ArtifactGrants.revoke(target, PERMISSION + id);
            if (future == null) throw ChacaApi.error("LuckPerms가 없어 레시피를 저장할 수 없어요");
            futures.add(future);
            var pending = pendingGrants.computeIfAbsent(target, k -> new HashSet<>());
            if (grant) pending.add(id); else pending.remove(id);
        }
        String who = CmdKit.name(target);
        String what = ids.size() == 1 ? recipeNames().getOrDefault(ids.getFirst(), ids.getFirst()) + " 레시피" : "레시피 " + ids.size() + "개";
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { CmdKit.fail(sender, "레시피를 저장하지 못했어요", error); return; }
            CmdKit.ok(sender, who + " 님" + (grant ? "에게 " : "의 ") + what + "을(를) " + (grant ? "주었어요" : "회수했어요"));
            Player online = Bukkit.getPlayer(target);
            if (online != null && viewers.containsKey(target)) send(online, AlchemyProtocol.KIND_STATE, "");
        }));
    }

    private void openWindow(CommandSender sender, Player target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!target.getListeningPluginChannels().contains(AlchemyProtocol.RESPONSE)) {
            if (sender == target) { CmdKit.notify(target, "warning", "alchemy-mod", "연금술 창을 열 수 없어요", "차카데미 모드를 업데이트해 주세요", 3000); return; }
            throw ChacaApi.error(plugin.names().name(target) + " 님은 차카데미 모드가 없어 연금술 창을 열 수 없어요");
        }
        send(target, AlchemyProtocol.KIND_OPEN, "");
    }
}
