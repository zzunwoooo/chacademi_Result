package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.File;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import school.magiccodex.database.DatabaseSettings;

/**
 * 스토리 신호 (2026-10-29, 오너 2장: 큐디치·결투·교화·원소 반응·던전을 학교 스토리가 센다).
 * <ul>
 *   <li><b>서버 간 전달</b> — 스토리 진행은 학교 서버에만 있다. 학교가 아닌 서버(야생·아레나·던전)에서 난
 *       {@link StoryProgressEvent} 는 5초마다 묶어서 공유 MariaDB 표 {@code chaca_story_signal} 에 적고, 학교 서버는 3초마다
 *       접속 중인 유저 몫을 읽어 같은 신호로 다시 발사한 뒤 지운다 (그 유저가 학교에 오면 받음). 받는 서버 =
 *       config.yml {@code story-relay.receiver} (기본 school), 이 서버 이름 = {@code story-relay.server-id} → server-heartbeat.yml
 *       server-id → 서버 폴더 이름.</li>
 *   <li><b>새 신호</b> — 원소반응(MagicMythic MagicReactionEvent, 시전자가 유저일 때), 변색종교화(LuckPerms 에
 *       chacademia.pet.…shiny 권한이 새로 붙을 때 = 변색종 교화 저장), 결투승리·큐디치참가({@link ChacaApi#stat}).</li>
 *   <li><b>명령</b> — {@code /유저관리 <닉> 스토리신호 <신호> [개수]}: 책 끝 명령·던전 보스 처치 등에서 신호를 보낸다
 *       (학교가 아니면 위 전달로 학교에 감).</li>
 *   <li><b>PlaceholderAPI</b> — {@code %chacastory_petdex%} 펫 도감 수(chacademia.pet.* 권한), {@code %chacastory_accessories%}
 *       반지·귀걸이·목걸이 중 장착한 칸 수, {@code %chacastory_spells%} 배운 마법 수(magic.learned.* 권한). 스토리 값 조건·대사에 씀.</li>
 * </ul>
 * MagicCodexBridge 본체를 바꾸지 않으려고 {@link AscensionBridge} 가 만들고 닫는다 (배포 jar 본체가 소스와 달라서).
 */
final class StorySignals implements Listener, AutoCloseable {
    static final String REACTION = "원소반응", SHINY = "변색종교화", DUEL_WIN = "결투승리", KYUDICHI = "큐디치참가";
    static final String TABLE = "chaca_story_signal";
    private static volatile StorySignals instance;

    private final MagicCodexBridge plugin;
    private final String server;
    private final boolean receiver;
    private final DatabaseSettings settings;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-story-signal"); t.setDaemon(true); return t; });
    private Connection connection;
    private volatile boolean closed;
    /** 보낼 신호 묶음: "uuid|신호" → 개수 (메인 스레드만). */
    private final Map<String, Integer> outbox = new LinkedHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private volatile boolean polling;
    private long lastCleanup;
    private Plugin mythic;
    private PlaceholderExpansion expansion;
    private Object lpSubscription;

    StorySignals(MagicCodexBridge plugin) {
        this.plugin = plugin;
        var cfg = plugin.getConfig();
        server = serverId(plugin, cfg.getString("story-relay.server-id", ""));
        receiver = server.equalsIgnoreCase(cfg.getString("story-relay.receiver", "school").strip());
        DatabaseSettings s = null;
        try {
            var folder = plugin.getDataFolder().toPath();
            var dedicated = folder.resolve("player-state-database.properties");
            s = DatabaseSettings.loadShared(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"), "story-signal");
            if (!s.mariaDb()) { plugin.getLogger().warning("스토리 신호 전달 꺼짐: 공유 MariaDB 가 아님 (이 서버 안의 신호만 스토리에 감)"); s = null; }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("스토리 신호 전달 꺼짐: 공유 DB 설정을 읽지 못함 — " + e.getMessage());
            s = null;
        }
        settings = s;
        if (settings != null) io.execute(() -> {
            try (var st = db().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + "(id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,uuid VARCHAR(36) NOT NULL,signal_key VARCHAR(32) NOT NULL,"
                        + "amount INT NOT NULL,server VARCHAR(48) NOT NULL,at BIGINT NOT NULL,INDEX idx_story_signal_uuid(uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci");
            } catch (Exception e) { plugin.getLogger().warning("스토리 신호 표 준비 실패: " + e.getMessage()); }
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (settings != null) {
            if (receiver) tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 60L, 60L));
            else tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 100L, 100L));
        }
        hookReactions();
        hookShiny();
        registerExpansion();
        registerCommands();
        instance = this;
        plugin.getLogger().info("스토리 신호: 이 서버 " + server + (settings == null ? " (전달 꺼짐)" : receiver ? " = 받는 서버" : " → " + cfg.getString("story-relay.receiver", "school") + " 로 전달"));
    }

    /** config 값 → server-heartbeat.yml server-id → 서버 폴더 이름 (C:\Chacademi\network\school → school). */
    private static String serverId(MagicCodexBridge plugin, String configured) {
        if (configured != null && !configured.isBlank()) return configured.strip();
        File hb = new File(plugin.getDataFolder(), "server-heartbeat.yml");
        if (hb.isFile()) {
            String sid = YamlConfiguration.loadConfiguration(hb).getString("server-id", "").strip();
            if (!sid.isEmpty()) return sid;
        }
        try { return Bukkit.getWorldContainer().getCanonicalFile().getName(); }
        catch (java.io.IOException e) { return Bukkit.getWorldContainer().getAbsoluteFile().getName(); }
    }

    private Connection db() throws Exception {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            connection = settings.connect(plugin.getDataFolder().toPath().resolve("story-signal.db"));
            settings.configure(connection);
        }
        return connection;
    }

    private void submit(Runnable r) { if (!closed) try { io.execute(r); } catch (RejectedExecutionException ignored) { } }

    // ------------------------------------------------------------------ 보내는 쪽 (학교가 아닌 서버)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSignal(StoryProgressEvent e) {
        if (receiver || settings == null || e.relayed() || e.getPlayer() == null) return;
        String key = e.getKey();
        if (key == null || key.isEmpty() || key.length() > 32 || e.getAmount() <= 0) return;
        outbox.merge(e.getPlayer().getUniqueId() + "|" + key, e.getAmount(), (a, b) -> (int) Math.min(100_000L, (long) a + b));
    }

    /** 5초마다 (메인 스레드에서 묶음을 떼어) 한 번에 적는다. 실패하면 다음 번에 다시 (메모리에 되돌림). */
    private void flush() {
        if (outbox.isEmpty() || closed) return;
        var batch = new LinkedHashMap<>(outbox);
        outbox.clear();
        submit(() -> {
            try (var q = db().prepareStatement("INSERT INTO " + TABLE + "(uuid,signal_key,amount,server,at) VALUES(?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                for (var e : batch.entrySet()) {
                    int bar = e.getKey().indexOf('|');
                    q.setString(1, e.getKey().substring(0, bar)); q.setString(2, e.getKey().substring(bar + 1));
                    q.setInt(3, e.getValue()); q.setString(4, server); q.setLong(5, now);
                    q.addBatch();
                }
                q.executeBatch();
            } catch (Exception ex) {
                plugin.getLogger().warning("스토리 신호 " + batch.size() + "건 전달 실패 (다음에 다시): " + ex.getMessage());
                if (!closed && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> batch.forEach((k, v) -> outbox.merge(k, v, Integer::sum)));
            }
        });
    }

    // ------------------------------------------------------------------ 받는 쪽 (학교)

    private record Row(long id, UUID player, String key, int amount) {}

    /** 3초마다: 접속 중인 유저 몫을 읽어 신호로 발사하고, 발사한 줄만 지운다. 그사이 나간 유저 몫은 남겨 둔다. */
    private void poll() {
        if (polling || closed) return;
        var online = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId().toString());
        boolean cleanup = System.currentTimeMillis() - lastCleanup > 3_600_000L;
        if (online.isEmpty() && !cleanup) return;
        polling = true;
        if (cleanup) lastCleanup = System.currentTimeMillis();
        submit(() -> {
            var rows = new ArrayList<Row>();
            try {
                if (cleanup) try (var q = db().prepareStatement("DELETE FROM " + TABLE + " WHERE at<?")) {
                    q.setLong(1, System.currentTimeMillis() - 14L * 24 * 3_600_000L); q.executeUpdate();
                }
                for (int from = 0; from < online.size(); from += 200) {
                    var part = online.subList(from, Math.min(online.size(), from + 200));
                    String marks = String.join(",", Collections.nCopies(part.size(), "?"));
                    try (var q = db().prepareStatement("SELECT id,uuid,signal_key,amount FROM " + TABLE + " WHERE uuid IN (" + marks + ") ORDER BY id LIMIT 500")) {
                        for (int i = 0; i < part.size(); i++) q.setString(i + 1, part.get(i));
                        try (var r = q.executeQuery()) {
                            while (r.next()) {
                                try { rows.add(new Row(r.getLong(1), UUID.fromString(r.getString(2)), r.getString(3), r.getInt(4))); }
                                catch (IllegalArgumentException ignored) { }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("스토리 신호 받기 실패: " + ex.getMessage());
            }
            if (rows.isEmpty() || closed || !plugin.isEnabled()) { polling = false; return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                var done = new ArrayList<Long>();
                for (Row row : rows) {
                    Player p = Bukkit.getPlayer(row.player());
                    if (p == null || !p.isOnline()) continue;
                    try { Bukkit.getPluginManager().callEvent(new StoryProgressEvent(p, row.key(), Math.max(1, row.amount()), true)); }
                    catch (RuntimeException ex) { plugin.getLogger().warning("스토리 신호 발사 실패 " + row.key() + ": " + ex); }
                    done.add(row.id());
                }
                if (done.isEmpty()) { polling = false; return; }
                submit(() -> {
                    try (var q = db().prepareStatement("DELETE FROM " + TABLE + " WHERE id IN (" + String.join(",", Collections.nCopies(done.size(), "?")) + ")")) {
                        for (int i = 0; i < done.size(); i++) q.setLong(i + 1, done.get(i));
                        q.executeUpdate();
                    } catch (Exception ex) { plugin.getLogger().warning("스토리 신호 지우기 실패 (다시 받을 수 있음): " + ex.getMessage()); }
                    finally { polling = false; }
                });
            });
        });
    }

    // ------------------------------------------------------------------ 새 신호

    /** {@link ChacaApi#stat} 이 부른다 (아레나 큐디치: 결투승리·큐디치참가). 접속 중일 때만. */
    static void stat(UUID player, String key) {
        String signal = switch (key) { case "결투승리" -> DUEL_WIN; case "큐디치참가" -> KYUDICHI; default -> null; };
        if (signal == null) return;
        var self = instance;
        if (self == null || self.closed) return;
        Runnable fire = () -> { Player p = Bukkit.getPlayer(player); if (p != null && p.isOnline()) StoryProgressEvent.fire(p, signal); };
        if (Bukkit.isPrimaryThread()) fire.run(); else Bukkit.getScheduler().runTask(self.plugin, fire);
    }

    /** MagicMythic 원소 반응 (시전자가 유저면 1회). MagicMythic 이 나중에 켜져도 연결한다. */
    private void hookReactions() {
        var mm = Bukkit.getPluginManager().getPlugin("ChAcademiaMagicMythic");
        if (mm == null || !mm.isEnabled() || mm == mythic) return;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("school.chacademia.magicmythic.MagicReactionEvent", true, mm.getClass().getClassLoader());
            Method caster = type.getMethod("getCaster");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (l, ev) -> {
                if (!type.isInstance(ev)) return;
                try { if (caster.invoke(ev) instanceof Player p) StoryProgressEvent.fire(p, REACTION); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }, plugin, false);
            mythic = mm;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("스토리 원소반응 신호 연결 실패 (MagicMythic 버전 확인): " + e);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) { if (e.getPlugin().getName().equals("ChAcademiaMagicMythic")) hookReactions(); }

    /** 변색종 교화 = LuckPerms 에 chacademia.pet.…shiny 권한이 이 서버에서 새로 붙음 (TamingBridge 가 교화 성공 때 저장). */
    private void hookShiny() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return;
        try {
            var bus = net.luckperms.api.LuckPermsProvider.get().getEventBus();
            lpSubscription = bus.subscribe(plugin, net.luckperms.api.event.node.NodeAddEvent.class, e -> {
                if (!(e.getTarget() instanceof net.luckperms.api.model.user.User user)) return;
                var node = e.getNode();
                if (!node.getValue() || node.hasExpiry()) return;
                String k = node.getKey().toLowerCase(Locale.ROOT);
                if (!k.startsWith("chacademia.pet.") || !k.endsWith("shiny")) return;
                UUID id = user.getUniqueId();
                if (closed || !plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) StoryProgressEvent.fire(p, SHINY); });
            });
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("스토리 변색종 신호 연결 실패: " + e);
        }
    }

    // ------------------------------------------------------------------ 명령·자리표시자

    private void registerCommands() {
        if (!CodexCommands.ready(plugin, "/유저관리 스토리신호")) return;
        ChacaApi.branch(plugin, ChacaApi.유저관리, Commands.literal("스토리신호")
                .then(Commands.argument("신호", ChacaApi.word(() -> List.of("던전클리어", "몬스터기록", REACTION, SHINY, DUEL_WIN, KYUDICHI, "교화성공", "연금술성공")))
                        .executes(ctx -> send(ctx, 1))
                        .then(Commands.argument("개수", IntegerArgumentType.integer(1, 1000)).executes(ctx -> send(ctx, IntegerArgumentType.getInteger(ctx, "개수"))))),
                "스토리 신호 보내기 (학교가 아닌 서버면 학교 스토리로 전달)", "스토리신호 던전클리어");
    }

    private int send(CommandContext<CommandSourceStack> ctx, int amount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player p = ChacaApi.targetOnline(ctx);
        String key = ctx.getArgument("신호", String.class).strip();
        if (key.isEmpty() || key.length() > 32) throw ChacaApi.error("신호 이름은 1~32자예요");
        Bukkit.getPluginManager().callEvent(new StoryProgressEvent(p, key, amount));
        ChacaApi.ok(CodexCommands.sender(ctx), ChacaApi.name(p.getUniqueId()) + " 님에게 스토리 신호 " + key + (amount > 1 ? " ×" + amount : "") + (receiver ? "" : " (학교로 전달)"));
        return Command.SINGLE_SUCCESS;
    }

    private void registerExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            expansion = new Expansion(plugin);
            if (!expansion.register()) plugin.getLogger().warning("PlaceholderAPI chacastory 등록 실패");
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("PlaceholderAPI chacastory 등록 실패: " + e);
            expansion = null;
        }
    }

    static final class Expansion extends PlaceholderExpansion {
        private final MagicCodexBridge plugin;
        Expansion(MagicCodexBridge plugin) { this.plugin = plugin; }
        @Override public String getIdentifier() { return "chacastory"; }
        @Override public String getAuthor() { return "Chacademia"; }
        @Override public String getVersion() { return "0.1.0"; }
        @Override public boolean persist() { return true; }
        @Override public String onRequest(OfflinePlayer player, String params) {
            Player p = player == null ? null : player.getPlayer();
            return switch (params) {
                case "petdex" -> Integer.toString(p == null ? 0 : count(p, "chacademia.pet."));
                case "spells" -> Integer.toString(p == null ? 0 : count(p, "magic.learned."));
                case "accessories" -> Integer.toString(p == null ? 0 : accessories(plugin, p));
                default -> null;
            };
        }
    }

    /** 이 접두사로 시작하는 켜진 권한 수 (별표 권한 제외). */
    static int count(Player p, String prefix) {
        int n = 0;
        for (PermissionAttachmentInfo i : p.getEffectivePermissions()) {
            String k = i.getPermission();
            if (i.getValue() && k.startsWith(prefix) && k.length() > prefix.length() && !k.endsWith("*")) n++;
        }
        return n;
    }

    /** 반지·귀걸이·목걸이 칸 (EquipmentBridge 1~3) 중 장착한 칸 수. */
    static int accessories(MagicCodexBridge plugin, Player p) {
        var eq = plugin.equipmentBridge();
        if (eq == null) return 0;
        int n = 0;
        for (int i = 1; i <= 3; i++) {
            var item = eq.accessoryItem(p, i);
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) n++;
        }
        return n;
    }

    @Override
    public void close() {
        if (closed) return;
        if (instance == this) instance = null;
        for (var t : tasks) t.cancel();
        tasks.clear();
        HandlerList.unregisterAll(this);
        if (expansion != null) try { expansion.unregister(); } catch (RuntimeException | LinkageError ignored) { }
        if (lpSubscription instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        if (settings != null && !receiver && !outbox.isEmpty()) {
            var batch = new LinkedHashMap<>(outbox);
            outbox.clear();
            io.execute(() -> {
                try (var q = db().prepareStatement("INSERT INTO " + TABLE + "(uuid,signal_key,amount,server,at) VALUES(?,?,?,?,?)")) {
                    for (var e : batch.entrySet()) {
                        int bar = e.getKey().indexOf('|');
                        q.setString(1, e.getKey().substring(0, bar)); q.setString(2, e.getKey().substring(bar + 1));
                        q.setInt(3, e.getValue()); q.setString(4, server); q.setLong(5, System.currentTimeMillis());
                        q.addBatch();
                    }
                    q.executeBatch();
                } catch (Exception ex) { plugin.getLogger().warning("스토리 신호 마지막 전달 실패 " + batch.size() + "건: " + ex.getMessage()); }
            });
        }
        closed = true;
        io.shutdown();
        try { io.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (connection != null) try { connection.close(); } catch (Exception ignored) { }
    }
}
