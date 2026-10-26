package school.magiccodex.paper;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import school.magiccodex.database.DatabaseSettings;

/**
 * 낚시 도감 records, shared by school and wild through the player-state MariaDB (same connection file as the shared
 * player state; without MariaDB a local SQLite file plugins/MagicCodexBridge/fishing.db).
 *
 * codex_fishing(uuid, fish, first_at, best, count)  -- fish = fish id, or treasure_0..3 for opened chests
 * codex_fishing_claim(uuid, tier, at)               -- completion rewards taken (tier 0..3 = 25/50/75/100%)
 *
 * Rows are loaded when a player joins (async) and kept in memory while online; every catch is written at once on the
 * IO thread, so the other server reads the new state when the player moves there. JDBC never runs on the main thread.
 */
final class FishingStore implements AutoCloseable {
    record Rec(long firstAt, double best, int count) {}
    static final class Book {
        final Map<String, Rec> rows = new HashMap<>();
        final Set<Integer> claimed = new HashSet<>();
        volatile boolean loaded;
        boolean loading;                                  // main thread only
        final List<Runnable> waiting = new ArrayList<>();  // callbacks for the load in flight
    }

    private final MagicCodexBridge plugin;
    private final DatabaseSettings settings;
    private final java.nio.file.Path sqlite;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-fishing"); t.setDaemon(true); return t; });
    private final Map<UUID, Book> books = new ConcurrentHashMap<>();
    private Connection connection;

    FishingStore(MagicCodexBridge plugin) {
        this.plugin = plugin;
        DatabaseSettings s;
        try {
            var folder = plugin.getDataFolder().toPath();
            var dedicated = folder.resolve("player-state-database.properties");
            s = DatabaseSettings.load(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"));
        } catch (Exception e) { s = new DatabaseSettings(false, "", "", ""); }
        settings = s;
        sqlite = plugin.getDataFolder().toPath().resolve("fishing.db");
        if (!settings.mariaDb()) plugin.getLogger().warning("낚시 도감: MariaDB 공유 연결이 없어 이 서버의 fishing.db 에만 저장합니다 (학교·야생 공유 안 됨).");
        io.execute(() -> {
            try (var st = db().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS codex_fishing(uuid VARCHAR(36) NOT NULL,fish VARCHAR(64) NOT NULL,first_at BIGINT NOT NULL,best DOUBLE NOT NULL,count INT NOT NULL,PRIMARY KEY(uuid,fish))" + (settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
                st.execute("CREATE TABLE IF NOT EXISTS codex_fishing_claim(uuid VARCHAR(36) NOT NULL,tier INT NOT NULL,at BIGINT NOT NULL,PRIMARY KEY(uuid,tier))" + (settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
            } catch (Exception e) { plugin.getLogger().warning("낚시 도감 테이블 준비 실패: " + e.getMessage()); }
        });
    }

    private Connection db() throws Exception {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) try { connection.close(); } catch (Exception ignored) {}
            connection = settings.connect(sqlite);
            settings.configure(connection);
        }
        return connection;
    }

    /** The cached book (empty until loaded). */
    Book book(UUID player) { return books.computeIfAbsent(player, k -> new Book()); }

    /** Loads once per join; a second call while loading only queues its callback (no double counts). Main thread. */
    void load(UUID player, Runnable done) {
        var book = book(player);
        if (done != null) book.waiting.add(done);
        if (book.loading || book.loaded) { if (book.loaded) runWaiting(book); return; }
        book.loading = true;
        io.execute(() -> {
            var rows = new HashMap<String, Rec>(); var claimed = new HashSet<Integer>();
            try (var p = db().prepareStatement("SELECT fish,first_at,best,count FROM codex_fishing WHERE uuid=?")) {
                p.setString(1, player.toString());
                try (var r = p.executeQuery()) { while (r.next()) rows.put(r.getString(1), new Rec(r.getLong(2), r.getDouble(3), r.getInt(4))); }
                try (var q = db().prepareStatement("SELECT tier FROM codex_fishing_claim WHERE uuid=?")) {
                    q.setString(1, player.toString());
                    try (var r = q.executeQuery()) { while (r.next()) claimed.add(r.getInt(1)); }
                }
            } catch (Exception e) { plugin.getLogger().warning("낚시 도감 읽기 실패 " + player + ": " + e.getMessage()); }
            Bukkit.getScheduler().runTask(plugin, () -> {
                synchronized (book) {
                    // keep anything caught before the load finished
                    for (var e : rows.entrySet()) book.rows.merge(e.getKey(), e.getValue(), FishingStore::merge);
                    book.claimed.addAll(claimed);
                    book.loaded = true;
                }
                book.loading = false;
                runWaiting(book);
            });
        });
    }
    private static void runWaiting(Book book) {
        var list = List.copyOf(book.waiting); book.waiting.clear();
        for (Runnable r : list) r.run();
    }
    void forget(UUID player) { books.remove(player); }

    private static Rec merge(Rec a, Rec b) { return new Rec(Math.min(a.firstAt(), b.firstAt()), Math.max(a.best(), b.best()), a.count() + b.count()); }

    /** Records one catch; returns {newEntry, record}. Main thread. */
    boolean[] record(UUID player, String key, double size) {
        var book = book(player);
        boolean fresh, record;
        long now = System.currentTimeMillis();
        synchronized (book) {
            var old = book.rows.get(key);
            fresh = old == null;
            record = old != null && size > old.best();
            book.rows.put(key, old == null ? new Rec(now, size, 1) : new Rec(old.firstAt(), Math.max(old.best(), size), old.count() + 1));
        }
        String sql = settings.mariaDb()
                ? "INSERT INTO codex_fishing(uuid,fish,first_at,best,count) VALUES(?,?,?,?,1) ON DUPLICATE KEY UPDATE best=GREATEST(best,VALUES(best)),count=count+1"
                : "INSERT INTO codex_fishing(uuid,fish,first_at,best,count) VALUES(?,?,?,?,1) ON CONFLICT(uuid,fish) DO UPDATE SET best=MAX(best,excluded.best),count=count+1";
        io.execute(() -> {
            try (var p = db().prepareStatement(sql)) {
                p.setString(1, player.toString()); p.setString(2, key); p.setLong(3, now); p.setDouble(4, size); p.executeUpdate();
            } catch (Exception e) { plugin.getLogger().warning("낚시 도감 저장 실패 " + player + " " + key + ": " + e.getMessage()); }
        });
        return new boolean[]{fresh, record};
    }

    /** Marks a completion tier as taken; the callback gets false if it was already taken (main thread). */
    void claim(UUID player, int tier, Consumer<Boolean> done) {
        var book = book(player);
        synchronized (book) { if (!book.claimed.add(tier)) { done.accept(false); return; } }
        String sql = settings.mariaDb() ? "INSERT IGNORE INTO codex_fishing_claim(uuid,tier,at) VALUES(?,?,?)" : "INSERT OR IGNORE INTO codex_fishing_claim(uuid,tier,at) VALUES(?,?,?)";
        io.execute(() -> {
            boolean ok;
            try (var p = db().prepareStatement(sql)) {
                p.setString(1, player.toString()); p.setInt(2, tier); p.setLong(3, System.currentTimeMillis());
                ok = p.executeUpdate() == 1;  // the other server may have taken it a moment ago
            } catch (Exception e) { ok = false; plugin.getLogger().warning("낚시 도감 보상 기록 실패 " + player + ": " + e.getMessage()); }
            boolean result = ok;
            // a failed insert stays claimed in memory too: a reward is never paid twice
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(result));
        });
    }

    /** Admin: wipe a player's codex. */
    void reset(UUID player) {
        var book = book(player);
        synchronized (book) { book.rows.clear(); book.claimed.clear(); }
        io.execute(() -> {
            try (var p = db().prepareStatement("DELETE FROM codex_fishing WHERE uuid=?"); var q = db().prepareStatement("DELETE FROM codex_fishing_claim WHERE uuid=?")) {
                p.setString(1, player.toString()); p.executeUpdate(); q.setString(1, player.toString()); q.executeUpdate();
            } catch (Exception e) { plugin.getLogger().warning("낚시 도감 초기화 실패 " + player + ": " + e.getMessage()); }
        });
    }

    @Override public void close() {
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
