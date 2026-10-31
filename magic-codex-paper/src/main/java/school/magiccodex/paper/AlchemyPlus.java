package school.magiccodex.paper;

import java.io.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.AlchemyNoteProtocol;

// [기능:alchemy-overhaul 시작] 연금술 개편 (2026-10-31, 새 파일)
/**
 * 연금술 개편 (오너 2026-10-31). AlchemyBridge 는 표시한 몇 줄로만 여기를 부른다.
 *
 * - 단계별 근접 문구: 틀린 조합일 때, 아직 발견하지 않은 레시피 하나와 재료가 몇 종류 겹치는지 (messages.match-1~3).
 * - 한 번만 만드는 레시피: recipes.&lt;id&gt;.once: true → 이미 발견(=한 번 만든) 사람은 다시 만들 수 없다.
 * - 장비 결과물: recipes.&lt;id&gt;.result-item (연금술 편집기가 저장, 장비로 등록한 아이템 그대로) → 등록 정보가 붙은 채로 나온다.
 * - 최초 발견 칭호: recipes.&lt;id&gt;.first-title: &lt;titles.yml 칭호 ID&gt;. 서버 전체에서 처음 발견한 한 명만 (공유 MariaDB
 *   chaca_alchemy_first). 개편 전에 이미 누가 발견한 레시피(LuckPerms 권한 보유자)는 아무도 받지 않는다.
 *   공지 = 오너 문구 그대로, 모든 서버 (BungeeCord Forward ALL).
 * - 연금 쪽지 item:alchemy_note_1~4 우클릭 → 아직 발견하지 않은 레시피 하나의 힌트 (등급이 높을수록 자세히), 연금 수첩에 저장
 *   (공유 MariaDB chaca_alchemy_notes). recipes.&lt;id&gt;.note: false 면 쪽지에 나오지 않는다. hint: 한 줄 = 쪽지 맨 위 문장.
 */
final class AlchemyPlus implements Listener, PluginMessageListener, AutoCloseable {
    private static final String NOTE_PREFIX = "item:alchemy_note_", BUNGEE = "BungeeCord", SUBCHANNEL = "ChacaAlchemyFirst";
    private static final String NOTES = "chaca_alchemy_notes", FIRST = "chaca_alchemy_first";
    private static final String[] GRADE = {"", "일반", "고급", "유일", "전설"};

    private final MagicCodexBridge plugin;
    private final AlchemyBridge bridge;
    private final DatabaseSettings settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-alchemy-plus"); t.setDaemon(true); return t; });
    private Connection connection;
    private volatile boolean closed;

    // alchemy.yml (reload)
    private volatile Map<String, String> firstTitles = Map.of(), hints = Map.of();
    private volatile Set<String> once = Set.of(), noNote = Set.of();
    private volatile Map<String, byte[]> resultItems = Map.of();
    private volatile Map<String, String> texts = Map.of();
    private static final double[] DEFAULT_DROP = {0.02, 0.01, 0.005, 0.001};
    private volatile double[] noteDrop = DEFAULT_DROP.clone();
    private final Set<String> pendingFirst = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> noteLimits = new HashMap<>();

    AlchemyPlus(MagicCodexBridge plugin, AlchemyBridge bridge) {
        this.plugin = plugin; this.bridge = bridge;
        DatabaseSettings s = null;
        try {
            var folder = plugin.getDataFolder().toPath();
            var dedicated = folder.resolve("player-state-database.properties");
            s = DatabaseSettings.loadShared(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"), "alchemy-plus");
            if (!s.mariaDb()) { plugin.getLogger().warning("연금 쪽지·최초 발견 꺼짐: 공유 MariaDB 가 아님"); s = null; }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("연금 쪽지·최초 발견 꺼짐: 공유 DB 설정을 읽지 못함 — " + e.getMessage());
            s = null;
        }
        settings = s;
        if (settings != null) submit(() -> {
            try (var st = db().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + NOTES + "(uuid VARCHAR(36) NOT NULL,recipe_id VARCHAR(64) NOT NULL,grade INT NOT NULL,at BIGINT NOT NULL,"
                        + "PRIMARY KEY(uuid,recipe_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci");
                st.execute("CREATE TABLE IF NOT EXISTS " + FIRST + "(recipe_id VARCHAR(64) NOT NULL PRIMARY KEY,uuid VARCHAR(36) NOT NULL,name VARCHAR(64) NOT NULL,"
                        + "title VARCHAR(64) NOT NULL,at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci");
            } catch (Exception e) { plugin.getLogger().warning("연금 쪽지 표 준비 실패: " + e.getMessage()); }
        });
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, AlchemyNoteProtocol.REQUEST, this);
        messenger.registerOutgoingPluginChannel(plugin, AlchemyNoteProtocol.RESPONSE);
        messenger.registerOutgoingPluginChannel(plugin, BUNGEE);
        messenger.registerIncomingPluginChannel(plugin, BUNGEE, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ alchemy.yml

    /** AlchemyBridge.reload() 가 레시피를 다 읽은 뒤 부른다. 실패하면 예외 → 그 reload 전체가 거절된다. */
    void load(YamlConfiguration yaml) {
        var titles = new HashMap<String, String>(); var hintMap = new HashMap<String, String>(); var items = new HashMap<String, byte[]>();
        var onceSet = new HashSet<String>(); var hidden = new HashSet<String>();
        ConfigurationSection root = yaml.getConfigurationSection("recipes");
        if (root != null) for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            String t = s.getString("first-title", "").strip(); if (!t.isEmpty()) titles.put(id, t);
            String h = s.getString("hint", "").strip(); if (!h.isEmpty()) hintMap.put(id, h);
            if (s.getBoolean("once", false)) onceSet.add(id);
            if (!s.getBoolean("note", true)) hidden.add(id);
            String b64 = s.getString("result-item", "").strip();
            if (!b64.isEmpty()) {
                try { byte[] raw = Base64.getDecoder().decode(b64); ItemStack.deserializeBytes(raw); items.put(id, raw); }
                catch (RuntimeException e) { throw new IllegalArgumentException(id + ".result-item 을 읽지 못함 (연금술 편집기로 다시 저장해 주세요)"); }
            }
        }
        var rates = yaml.getDoubleList("settings.note-drop");
        double[] drop = rates.size() == 4 ? new double[]{rates.get(0), rates.get(1), rates.get(2), rates.get(3)} : DEFAULT_DROP.clone();
        double sum = 0; for (double r : drop) { if (r < 0 || r > 1) throw new IllegalArgumentException("settings.note-drop 은 0~1 네 개 (일반·고급·유일·전설)"); sum += r; }
        if (sum > 1) throw new IllegalArgumentException("settings.note-drop 합이 1(100%)을 넘음");
        var ms = yaml.getConfigurationSection("messages"); var m = new HashMap<String, String>();
        for (String k : List.of("match-1", "match-2", "match-3", "once")) m.put(k, ms == null ? "" : ms.getString(k, ""));
        firstTitles = Map.copyOf(titles); hints = Map.copyOf(hintMap); once = Set.copyOf(onceSet); noNote = Set.copyOf(hidden);
        resultItems = Map.copyOf(items); texts = Map.copyOf(m); noteDrop = drop;
    }

    // ------------------------------------------------------------------ note drops

    /** 모든 몬스터(적대 몹 + MythicMobs 몹)를 플레이어가 잡으면 한 번 굴려 등급 하나 (오너 2026-10-31: 2% · 1% · 0.5% · 0.1%). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void death(org.bukkit.event.entity.EntityDeathEvent e) {
        var mob = e.getEntity();
        if (mob instanceof Player || mob.getKiller() == null) return;
        if (!(mob instanceof org.bukkit.entity.Enemy) && !mythic(mob)) return;
        double roll = ThreadLocalRandom.current().nextDouble(), edge = 0;
        double[] rates = noteDrop;
        // 높은 등급부터: 전설 0.1% → 유일 0.5% → 고급 1% → 일반 2% (서로 겹치지 않음)
        for (int grade = 4; grade >= 1; grade--) {
            edge += rates[grade - 1];
            if (roll >= edge) continue;
            ItemStack note = bridge.create(NOTE_PREFIX + grade, 1);
            if (note == null) { plugin.getLogger().warning("연금 쪽지 아이템이 없음: " + NOTE_PREFIX + grade + " (ItemsAdder 설정 확인)"); return; }
            e.getDrops().add(note);
            plugin.getLogger().info("ALCHEMY NOTE DROP player=" + mob.getKiller().getName() + " grade=" + grade + " mob=" + mob.getType());
            return;
        }
    }

    private static boolean mythic(org.bukkit.entity.Entity entity) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return false;
        try { return io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().isMythicMob(entity); }
        catch (RuntimeException | LinkageError e) { return false; }
    }

    private String text(String key, String fallback) { String v = texts.getOrDefault(key, ""); return v.isBlank() ? fallback : v; }

    // ------------------------------------------------------------------ craft hooks

    /** 틀린 조합: 아직 발견하지 않은 레시피 하나와 재료가 몇 종류 겹치는지에 따른 문구, 겹치는 게 없으면 null. */
    String matchMessage(Player p, Map<String, Integer> want) {
        int best = 0;
        for (var e : bridge.recipeIngredients().entrySet()) {
            if (bridge.known(p, e.getKey())) continue;
            int n = 0; for (String k : want.keySet()) if (e.getValue().containsKey(k)) n++;
            best = Math.max(best, n);
        }
        if (best <= 0) return null;
        return switch (best) {
            case 1 -> text("match-1", "마법진이 아주 희미하게 떨린다. 재료 하나가 무언가에 닿은 것 같다…");
            case 2 -> text("match-2", "마법진이 잠깐 빛나다 사그라든다. 재료 두 가지가 서로 끌리는 것 같다…");
            default -> text("match-3", "마법진이 크게 일렁인다. 여러 재료가 맞물리는 느낌이다…");
        };
    }

    /** 한 번만 만드는 레시피를 이미 만든 경우의 거절 문구, 만들 수 있으면 null. */
    String onceRefusal(String recipeId, boolean isNew) {
        return once.contains(recipeId) && !isNew ? text("once", "이 조합식은 한 번만 만들 수 있습니다.") : null;
    }

    boolean once(String recipeId) { return once.contains(recipeId); }

    /** 결과물: 편집기가 저장한 장비 아이템이 있으면 그 복사본, 없으면 null (AlchemyBridge 가 원래대로 만든다). */
    ItemStack resultItem(String recipeId, int amount) {
        byte[] raw = resultItems.get(recipeId);
        if (raw == null) return null;
        try {
            ItemStack item = ItemStack.deserializeBytes(raw);
            item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
            return item;
        } catch (RuntimeException e) { plugin.getLogger().warning("연금술 결과 아이템 복원 실패 recipe=" + recipeId + " " + e); return null; }
    }

    /** 편집기 저장: 관리자 인벤토리에서 이 결과 키를 가진 아이템 중 장비로 등록된 것(magiccodex 데이터가 있는 것)을 찾아 그대로 저장. */
    static String templateOf(Player admin, String resultKey, AlchemyBridge bridge) {
        var inv = admin.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir() || !resultKey.equals(bridge.keyOf(item))) continue;
            var meta = item.getItemMeta();
            if (meta == null) continue;
            boolean ours = meta.getPersistentDataContainer().getKeys().stream().anyMatch(k -> k.getNamespace().equals("magiccodex"));
            if (!ours) continue;
            ItemStack one = item.clone(); one.setAmount(1);
            return Base64.getEncoder().encodeToString(one.serializeAsBytes());
        }
        return null;
    }

    // ------------------------------------------------------------------ first discovery

    /** 서버 스레드. 레시피를 처음 발견(첫 제작)한 직후. */
    void discovered(Player p, String recipeId, String recipeName) {
        String title = firstTitles.get(recipeId);
        if (title == null || settings == null || !pendingFirst.add(recipeId)) return;
        var titles = plugin.titleService();
        String titleName = titles == null ? null : titles.registeredTitles(false).get(title);
        if (titleName == null) { pendingFirst.remove(recipeId); plugin.getLogger().warning("연금술 최초 발견 칭호가 titles.yml 에 없거나 꺼짐: recipe=" + recipeId + " title=" + title); return; }
        UUID uid = p.getUniqueId(); String who = plugin.names().name(p);
        holdersBefore(recipeId, uid).whenComplete((others, error) -> submit(() -> {
            boolean won = false;
            try {
                if (error != null) throw new Exception(error);
                try (var q = db().prepareStatement("SELECT 1 FROM " + FIRST + " WHERE recipe_id=?")) { q.setString(1, recipeId); try (var r = q.executeQuery()) { if (r.next()) return; } }
                try (var ins = db().prepareStatement("INSERT IGNORE INTO " + FIRST + "(recipe_id,uuid,name,title,at) VALUES(?,?,?,?,?)")) {
                    ins.setString(1, recipeId); ins.setString(2, others ? "" : uid.toString()); ins.setString(3, others ? "" : who);
                    ins.setString(4, others ? "" : title); ins.setLong(5, System.currentTimeMillis());
                    won = ins.executeUpdate() == 1 && !others;
                }
            } catch (Exception e) { plugin.getLogger().warning("연금술 최초 발견 확인 실패 recipe=" + recipeId + " " + e.getMessage()); }
            finally { boolean award = won; Bukkit.getScheduler().runTask(plugin, () -> {
                pendingFirst.remove(recipeId);
                if (!award) return;
                plugin.titleService().grant(uid, title, false, ok -> { if (!ok) plugin.getLogger().warning("연금술 최초 발견 칭호 지급 실패 player=" + uid + " title=" + title); });
                String line = who + "님이 최초로 " + recipeName + "의 연금 조합식을 발견하여 " + titleName + "칭호를 획득하였습니다.";
                plugin.getLogger().info("ALCHEMY FIRST player=" + uid + " recipe=" + recipeId + " title=" + title);
                announce(line);
                Player via = Bukkit.getPlayer(uid);
                if (via != null) forward(via, line);
            }); }
        }));
    }

    /** 이 사람 말고 이미 이 레시피 권한을 가진 사람이 있는지 (개편 전 발견). LuckPerms 가 없으면 false. */
    private CompletableFuture<Boolean> holdersBefore(String recipeId, UUID self) {
        try {
            var lp = net.luckperms.api.LuckPermsProvider.get();
            return lp.getUserManager().searchAll(net.luckperms.api.node.matcher.NodeMatcher.key("magiccodex.alchemy.recipe." + recipeId))
                    .thenApply(map -> map.keySet().stream().anyMatch(id -> !id.equals(self)));
        } catch (IllegalStateException | LinkageError e) { return CompletableFuture.completedFuture(false); }
    }

    private static void announce(String line) {
        var c = Component.text(line, NamedTextColor.GOLD);
        for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(c);
    }

    private void forward(Player via, String line) {
        try (var payload = new ByteArrayOutputStream(); var pd = new DataOutputStream(payload);
             var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            pd.writeUTF(line); pd.flush();
            out.writeUTF("Forward"); out.writeUTF("ALL"); out.writeUTF(SUBCHANNEL);
            out.writeShort(payload.size()); out.write(payload.toByteArray()); out.flush();
            via.sendPluginMessage(plugin, BUNGEE, bytes.toByteArray());
        } catch (IOException | RuntimeException e) { plugin.getLogger().warning("연금술 최초 발견 공지 전달 실패: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ plugin messages

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (BUNGEE.equals(channel)) {
            try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
                if (!SUBCHANNEL.equals(in.readUTF())) return;
                byte[] inner = new byte[in.readUnsignedShort()]; in.readFully(inner);
                try (var pin = new DataInputStream(new ByteArrayInputStream(inner))) { announce(pin.readUTF()); }
            } catch (IOException | RuntimeException ignored) { }
            return;
        }
        if (!AlchemyNoteProtocol.REQUEST.equals(channel)) return;
        try { AlchemyNoteProtocol.request(data); } catch (IllegalArgumentException e) { return; }
        long now = System.currentTimeMillis();
        if (now < noteLimits.getOrDefault(p.getUniqueId(), 0L)) return;
        noteLimits.put(p.getUniqueId(), now + 500);
        openNotebook(p, "");
    }

    // ------------------------------------------------------------------ notes

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        String key = item == null ? null : bridge.keyOf(item);
        if (key == null || !key.startsWith(NOTE_PREFIX)) return;
        int grade;
        try { grade = Integer.parseInt(key.substring(NOTE_PREFIX.length())); } catch (NumberFormatException ex) { return; }
        if (grade < 1 || grade > 4) return;
        e.setCancelled(true);
        Player p = e.getPlayer(); EquipmentSlot hand = e.getHand();
        long now = System.currentTimeMillis();
        if (now < noteLimits.getOrDefault(p.getUniqueId(), 0L)) return;
        noteLimits.put(p.getUniqueId(), now + 1000);
        if (settings == null || !plugin.playerStateReady(p)) { CmdKit.notify(p, "warning", "alchemy-note", "지금은 쪽지를 펼칠 수 없어요", "잠시 뒤 다시 시도해 주세요.", 0); return; }
        UUID uid = p.getUniqueId();
        submit(() -> {
            Map<String, Integer> stored;
            try { stored = notes(uid); } catch (Exception ex) { plugin.getLogger().warning("연금 수첩 읽기 실패: " + ex.getMessage()); return; }
            Bukkit.getScheduler().runTask(plugin, () -> read(p, hand, key, grade, stored));
        });
    }

    /** 서버 스레드: 고를 레시피를 정하고 쪽지 한 장을 걷은 뒤 저장. */
    private void read(Player p, EquipmentSlot hand, String key, int grade, Map<String, Integer> stored) {
        if (!p.isOnline()) return;
        var candidates = new ArrayList<String>();
        for (String id : bridge.recipeIngredients().keySet())
            if (!noNote.contains(id) && !bridge.known(p, id) && stored.getOrDefault(id, 0) < grade) candidates.add(id);
        if (candidates.isEmpty()) { CmdKit.notify(p, "system", "alchemy-note", "쪽지에서 새로 알아낼 것이 없어요", "아직 모르는 조합식이 남아 있지 않습니다.", 0); return; }
        // 아직 쪽지를 한 번도 못 본 레시피를 먼저
        var fresh = candidates.stream().filter(id -> !stored.containsKey(id)).toList();
        var pool = fresh.isEmpty() ? candidates : fresh;
        String pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (held == null || !key.equals(bridge.keyOf(held))) return;
        if (held.getAmount() <= 1) { if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(null); else p.getInventory().setItemInMainHand(null); }
        else held.setAmount(held.getAmount() - 1);
        UUID uid = p.getUniqueId();
        plugin.getLogger().info("ALCHEMY NOTE player=" + p.getName() + " grade=" + grade + " recipe=" + pick);
        AuditLog.log(p, AuditLog.연금술, "연금 쪽지 · " + GRADE[grade] + " · " + pick, "레시피", pick, "등급", grade);
        submit(() -> {
            try (var up = db().prepareStatement("INSERT INTO " + NOTES + "(uuid,recipe_id,grade,at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE grade=GREATEST(grade,VALUES(grade)),at=VALUES(at)")) {
                up.setString(1, uid.toString()); up.setString(2, pick); up.setInt(3, grade); up.setLong(4, System.currentTimeMillis()); up.executeUpdate();
            } catch (Exception ex) { plugin.getLogger().warning("연금 수첩 저장 실패 player=" + uid + " recipe=" + pick + " " + ex.getMessage()); }
            Bukkit.getScheduler().runTask(plugin, () -> openNotebook(p, pick));
        });
    }

    /** 수첩 상태를 읽어 보낸다 (focus = 방금 읽은 레시피, 없으면 ""). 모드가 없으면 채팅으로 방금 읽은 힌트만. */
    void openNotebook(Player p, String focus) {
        if (settings == null) return;
        UUID uid = p.getUniqueId();
        submit(() -> {
            Map<String, Integer> stored; Map<String, String> firsts;
            try { stored = notes(uid); firsts = firsts(); } catch (Exception e) { plugin.getLogger().warning("연금 수첩 읽기 실패: " + e.getMessage()); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                var names = bridge.recipeNameMap(); var ingredients = bridge.recipeIngredients();
                var entries = new ArrayList<AlchemyNoteProtocol.Entry>();
                for (var e : stored.entrySet()) {
                    var ing = ingredients.get(e.getKey()); if (ing == null) continue;
                    boolean known = bridge.known(p, e.getKey());
                    int grade = Math.clamp(e.getValue(), 1, 4);
                    String title = known || grade >= 4 ? names.getOrDefault(e.getKey(), "조합식") : "알 수 없는 조합식";
                    entries.add(new AlchemyNoteProtocol.Entry(e.getKey(), title, grade, known, firsts.getOrDefault(e.getKey(), ""), lines(uid, e.getKey(), grade, ing, known, names)));
                }
                entries.sort(Comparator.comparing((AlchemyNoteProtocol.Entry x) -> !x.id().equals(focus)).thenComparing(x -> x.discovered()).thenComparing(AlchemyNoteProtocol.Entry::id));
                if (p.getListeningPluginChannels().contains(AlchemyNoteProtocol.RESPONSE)) {
                    try { p.sendPluginMessage(plugin, AlchemyNoteProtocol.RESPONSE, AlchemyNoteProtocol.encode(new AlchemyNoteProtocol.State(focus, entries))); }
                    catch (IllegalArgumentException ex) { plugin.getLogger().warning("연금 수첩 전송 실패 (" + p.getName() + "): " + ex.getMessage()); }
                } else if (!focus.isEmpty()) {
                    for (var e : entries) if (e.id().equals(focus)) { p.sendMessage(Component.text("[연금 쪽지] " + e.title(), NamedTextColor.GOLD)); for (String l : e.lines()) p.sendMessage(Component.text(l, NamedTextColor.WHITE)); }
                }
            });
        });
    }

    /** 쪽지 문장. 공개 범위: 1 재료 하나 · 2 재료 둘 · 3 재료 전부(개수 숨김) · 4 재료와 개수 전부 + 결과. 같은 사람·같은 레시피는 늘 같은 재료부터. */
    private List<String> lines(UUID uid, String id, int grade, Map<String, Integer> ing, boolean known, Map<String, String> names) {
        var out = new ArrayList<String>();
        String hint = hints.get(id); if (hint != null) out.add(hint);
        var keys = new ArrayList<>(ing.keySet());
        Collections.shuffle(keys, new Random((uid.toString() + ":" + id).hashCode()));
        int show = grade == 1 ? 1 : grade == 2 ? 2 : keys.size();
        show = Math.min(show, keys.size());
        out.add(grade >= 3 ? "필요한 재료 (모두 " + keys.size() + "가지)" : "필요한 재료 일부 (모두 " + keys.size() + "가지)");
        for (int i = 0; i < show; i++) {
            String k = keys.get(i); ItemStack sample = bridge.create(k, 1);
            String name = sample == null ? k : AlchemyBridge.displayName(sample);
            out.add("· " + name + (grade >= 4 ? " ×" + ing.get(k) : ""));
        }
        if (grade >= 4 || known) out.add("결과: " + names.getOrDefault(id, id));
        if (known) out.add("이미 발견한 조합식입니다.");
        return out;
    }

    private Map<String, Integer> notes(UUID uid) throws Exception {
        var out = new HashMap<String, Integer>();
        try (var q = db().prepareStatement("SELECT recipe_id,grade FROM " + NOTES + " WHERE uuid=?")) {
            q.setString(1, uid.toString());
            try (var r = q.executeQuery()) { while (r.next()) out.put(r.getString(1), r.getInt(2)); }
        }
        return out;
    }

    private Map<String, String> firsts() throws Exception {
        var out = new HashMap<String, String>();
        try (var st = db().createStatement(); var r = st.executeQuery("SELECT recipe_id,name FROM " + FIRST + " WHERE uuid<>''")) { while (r.next()) out.put(r.getString(1), r.getString(2)); }
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    private Connection db() throws Exception {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            connection = settings.connect(plugin.getDataFolder().toPath().resolve("alchemy-plus.db"));
            settings.configure(connection);
        }
        return connection;
    }

    private void submit(Runnable r) { if (!closed) try { worker.execute(r); } catch (RejectedExecutionException ignored) { } }

    @EventHandler public void quit(PlayerQuitEvent e) { noteLimits.remove(e.getPlayer().getUniqueId()); }

    @Override public void close() {
        closed = true; worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
// [기능:alchemy-overhaul 끝]
