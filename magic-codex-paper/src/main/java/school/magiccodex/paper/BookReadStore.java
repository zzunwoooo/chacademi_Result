package school.magiccodex.paper;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import school.magiccodex.database.DatabaseSettings;

/**
 * 책 보상 기록 (2026-10-23, owner: 책마다 평생 1번, 학교·야생 공유).
 * One row per (player, book) whose close commands already ran. The row is inserted BEFORE the commands run, and only the
 * call whose INSERT actually created the row may run them, so two servers / two packets can never both pay out.
 * Same DB file choice as the fishing codex: player-state-database.properties (shared MariaDB), else database.properties,
 * missing/invalid/shared-SQLite configuration is rejected (no local fallback; — logged at start).
 */
final class BookReadStore implements AutoCloseable {
    enum Claim { CLAIMED, ALREADY, ERROR }

    private final MagicCodexBridge plugin;
    private final DatabaseSettings settings;
    private final java.nio.file.Path sqlite;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-books"); t.setDaemon(true); return t; });
    private Connection connection;
    private volatile boolean closed;

    BookReadStore(MagicCodexBridge plugin) throws java.io.IOException {
        this.plugin = plugin;
        var folder = plugin.getDataFolder().toPath();
        var dedicated = folder.resolve("player-state-database.properties");
        settings = DatabaseSettings.loadShared(java.nio.file.Files.exists(dedicated)
                ? dedicated : folder.resolve("database.properties"), "book-read");
        sqlite = plugin.getDataFolder().toPath().resolve("books.db");
        io.execute(() -> {
            try (var st = db().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS codex_book_read(uuid VARCHAR(36) NOT NULL,book VARCHAR(48) NOT NULL,at BIGINT NOT NULL,PRIMARY KEY(uuid,book))"
                        + (settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
            } catch (Exception e) { plugin.getLogger().warning("책 보상 기록 테이블 준비 실패: " + e.getMessage()); }
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

    private void later(Runnable r) { if (!closed && plugin.isEnabled()) org.bukkit.Bukkit.getScheduler().runTask(plugin, r); }

    /** Inserts the row; CLAIMED only when this call created it. The callback runs on the main thread. */
    void claim(UUID player, String book, Consumer<Claim> done) {
        submit(() -> {
            try (var q = db().prepareStatement("INSERT " + (settings.mariaDb() ? "IGNORE" : "OR IGNORE") + " INTO codex_book_read(uuid,book,at) VALUES(?,?,?)")) {
                q.setString(1, player.toString()); q.setString(2, book); q.setLong(3, System.currentTimeMillis());
                Claim c = q.executeUpdate() == 1 ? Claim.CLAIMED : Claim.ALREADY;
                later(() -> done.accept(c));
            } catch (Exception e) {
                plugin.getLogger().warning("책 보상 기록 실패 (" + player + " / " + book + "): " + e.getMessage());
                later(() -> done.accept(Claim.ERROR));
            }
        }, () -> done.accept(Claim.ERROR));
    }

    /** Deletes the row (admin reset, or a claim whose player left before the commands could run). done(true) = deleted. */
    void release(UUID player, String book, Consumer<Boolean> done) {
        submit(() -> {
            try (var q = db().prepareStatement("DELETE FROM codex_book_read WHERE uuid=? AND book=?")) {
                q.setString(1, player.toString()); q.setString(2, book);
                boolean deleted = q.executeUpdate() > 0;
                if (done != null) later(() -> done.accept(deleted));
            } catch (Exception e) {
                plugin.getLogger().warning("책 보상 기록 삭제 실패 (" + player + " / " + book + "): " + e.getMessage());
                if (done != null) later(() -> done.accept(false));
            }
        }, () -> { if (done != null) done.accept(false); });
    }

    /** book id → time the commands ran. done(null) = read failure. */
    void list(UUID player, Consumer<Map<String, Long>> done) {
        submit(() -> {
            var out = new TreeMap<String, Long>();
            try (var q = db().prepareStatement("SELECT book,at FROM codex_book_read WHERE uuid=?")) {
                q.setString(1, player.toString());
                try (var r = q.executeQuery()) { while (r.next()) out.put(r.getString(1), r.getLong(2)); }
                later(() -> done.accept(out));
            } catch (Exception e) {
                plugin.getLogger().warning("책 보상 기록 읽기 실패 (" + player + "): " + e.getMessage());
                later(() -> done.accept(null));
            }
        }, () -> done.accept(null));
    }

    private void submit(Runnable job, Runnable rejected) {
        try { io.execute(job); } catch (RejectedExecutionException e) { rejected.run(); }
    }

    @Override public void close() {
        closed = true;
        io.shutdown();
        try { io.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
