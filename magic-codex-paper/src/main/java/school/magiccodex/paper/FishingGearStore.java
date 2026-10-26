package school.magiccodex.paper;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import school.magiccodex.database.DatabaseSettings;

/**
 * Fishing gear state in the database (owner 2026-10-20: "내구도 같은건 nbt가 아니라 내부 데이터 값으로, db에 저장").
 *
 * A gear item only carries its row id (PDC fishing_uid). The row holds the durability and, for a rod, what is fitted
 * to it (float + its durability, line + its durability, bait + count). Copies of an item share one row, so duplicating
 * gear gains nothing. Shared by school and wild through the player-state MariaDB (else the per-server fishing.db).
 *
 * codex_fishing_gear(uid, kind, gear_id, durability, float_id, float_dur, line_id, line_dur, bait_id, bait_count, updated_at)
 *
 * Reads are served from a main-thread cache that is filled asynchronously (join, gear window, first sight of an
 * item); writes update the cache at once and go to the database in order on the IO thread.
 */
final class FishingGearStore implements AutoCloseable {
    /** One gear row. Strings "" = nothing fitted. */
    record Row(String uid, int kind, String gearId, int durability, String floatId, int floatDur, String lineId, int lineDur, String baitId, int baitCount) {
        Row withDurability(int d) { return new Row(uid, kind, gearId, d, floatId, floatDur, lineId, lineDur, baitId, baitCount); }
    }

    private final MagicCodexBridge plugin;
    private final DatabaseSettings settings;
    private final java.nio.file.Path sqlite;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "MagicCodex-fishing-gear"); t.setDaemon(true); return t; });
    private final Map<String, Row> rows = new HashMap<>();          // main thread
    private final Set<String> missing = new HashSet<>();           // looked up, no row (a fresh item)
    private final Set<String> loading = new HashSet<>();
    private final Map<String, List<Runnable>> waiting = new HashMap<>();
    private Connection connection;

    FishingGearStore(MagicCodexBridge plugin) {
        this.plugin = plugin;
        DatabaseSettings s;
        try {
            var folder = plugin.getDataFolder().toPath();
            var dedicated = folder.resolve("player-state-database.properties");
            s = DatabaseSettings.load(java.nio.file.Files.exists(dedicated) ? dedicated : folder.resolve("database.properties"));
        } catch (Exception e) { s = new DatabaseSettings(false, "", "", ""); }
        settings = s;
        sqlite = plugin.getDataFolder().toPath().resolve("fishing.db");
        io.execute(() -> {
            try (var st = db().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS codex_fishing_gear(uid VARCHAR(36) PRIMARY KEY,kind INT NOT NULL,gear_id VARCHAR(64) NOT NULL,"
                        + "durability INT NOT NULL,float_id VARCHAR(64) NOT NULL,float_dur INT NOT NULL,line_id VARCHAR(64) NOT NULL,line_dur INT NOT NULL,"
                        + "bait_id VARCHAR(64) NOT NULL,bait_count INT NOT NULL,updated_at BIGINT NOT NULL)" + (settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
            } catch (Exception e) { plugin.getLogger().warning("낚시 장비 테이블 준비 실패: " + e.getMessage()); }
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

    // ------------------------------------------------------------------ reading (main thread)

    /** True when this uid's state is known (cached row or known to have none). */
    boolean known(String uid) { return uid == null || rows.containsKey(uid) || missing.contains(uid); }
    /** The cached row, or null (none, or not loaded yet: check known()). */
    Row row(String uid) { return uid == null ? null : rows.get(uid); }

    /** Loads these uids (fresh from the database even when cached), then runs done on the main thread. */
    void load(Collection<String> uids, Runnable done) {
        var todo = new ArrayList<String>();
        for (String u : uids) if (u != null && !u.isEmpty() && !loading.contains(u)) todo.add(u);
        var pending = new ArrayList<String>();
        for (String u : uids) if (u != null && loading.contains(u)) pending.add(u);
        if (todo.isEmpty() && pending.isEmpty()) { if (done != null) done.run(); return; }
        if (done != null) {
            // run done once everything asked for is loaded
            // run done once every asked uid finished loading (also after a failed read: callers check known() again)
            var left = new HashSet<>(todo); left.addAll(pending);
            int[] remaining = {left.size()};
            Runnable each = () -> { if (--remaining[0] == 0) done.run(); };
            for (String u : left) waiting.computeIfAbsent(u, k -> new ArrayList<>()).add(each);
        }
        if (todo.isEmpty()) return;
        loading.addAll(todo);
        io.execute(() -> {
            var found = new HashMap<String, Row>();
            boolean ok = true;
            try (var p = db().prepareStatement("SELECT uid,kind,gear_id,durability,float_id,float_dur,line_id,line_dur,bait_id,bait_count FROM codex_fishing_gear WHERE uid=?")) {
                for (String u : todo) {
                    p.setString(1, u);
                    try (var r = p.executeQuery()) {
                        if (r.next()) found.put(u, new Row(r.getString(1), r.getInt(2), r.getString(3), r.getInt(4), r.getString(5), r.getInt(6),
                                r.getString(7), r.getInt(8), r.getString(9), r.getInt(10)));
                    }
                }
            } catch (Exception e) { ok = false; plugin.getLogger().warning("낚시 장비 읽기 실패: " + e.getMessage()); }
            boolean read = ok;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String u : todo) {
                    loading.remove(u);
                    if (!read) continue;
                    var r = found.get(u);
                    if (r != null) { rows.put(u, r); missing.remove(u); } else if (!rows.containsKey(u)) missing.add(u);
                }
                for (String u : todo) { var list = waiting.remove(u); if (list != null) for (Runnable run : list) run.run(); }
                // a failed read leaves them unknown: callers ask again later
            });
        });
    }

    // ------------------------------------------------------------------ writing (main thread)

    void put(Row r) {
        rows.put(r.uid(), r); missing.remove(r.uid());
        io.execute(() -> {
            String sql = settings.mariaDb()
                    ? "INSERT INTO codex_fishing_gear(uid,kind,gear_id,durability,float_id,float_dur,line_id,line_dur,bait_id,bait_count,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) "
                      + "ON DUPLICATE KEY UPDATE kind=VALUES(kind),gear_id=VALUES(gear_id),durability=VALUES(durability),float_id=VALUES(float_id),float_dur=VALUES(float_dur),"
                      + "line_id=VALUES(line_id),line_dur=VALUES(line_dur),bait_id=VALUES(bait_id),bait_count=VALUES(bait_count),updated_at=VALUES(updated_at)"
                    : "INSERT OR REPLACE INTO codex_fishing_gear(uid,kind,gear_id,durability,float_id,float_dur,line_id,line_dur,bait_id,bait_count,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
            try (var p = db().prepareStatement(sql)) {
                p.setString(1, r.uid()); p.setInt(2, r.kind()); p.setString(3, r.gearId()); p.setInt(4, r.durability());
                p.setString(5, r.floatId()); p.setInt(6, r.floatDur()); p.setString(7, r.lineId()); p.setInt(8, r.lineDur());
                p.setString(9, r.baitId()); p.setInt(10, r.baitCount()); p.setLong(11, System.currentTimeMillis());
                p.executeUpdate();
            } catch (Exception e) { plugin.getLogger().severe("낚시 장비 저장 실패 " + r + ": " + e.getMessage()); }
        });
    }

    @Override public void close() {
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }
}
