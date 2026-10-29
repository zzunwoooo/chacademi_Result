package school.chacademia.dungeon;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import school.chacademia.dungeon.protocol.DungeonProtocol.Holder;
import school.chacademia.dungeon.protocol.DungeonProtocol.Title;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 클리어 기록 (개인 최고·서버 최고) 과 판(run) 장부. 공용 MariaDB(MagicCodexBridge 설정 파일)에 둔다 → 나중에 던전 서버로 옮겨도 같은 기록.
 * DB 는 전용 작업 스레드 하나 + 연결 하나만 쓴다 (플레이어별 연결 없음). 메인 스레드에서는 결과만 받는다.
 * 판 장부(run_id UNIQUE)가 "이 판의 클리어는 한 번만 처리" 를 보장한다.
 */
final class Records implements AutoCloseable {
    record Personal(int clears, long bestMs) {
        static final Personal NONE = new Personal(0, -1);
    }

    /** 클리어 처리 결과. duplicate = 이미 처리된 판(보상 다시 주지 않음). */
    record Clear(boolean duplicate, boolean firstClear, long previousBestMs, boolean newPersonal, boolean newServer, Holder best) {}

    private final Logger log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ChacaDungeon-DB");
        t.setDaemon(true);
        return t;
    });
    private ConnectionHolder holder;
    private boolean maria;
    private volatile Map<String, Title> titles = Map.of();
    /** 이름 조회는 이 플러그인 쪽(메인 스레드)에서 대체값으로 쓴다. */
    private final java.util.function.Function<UUID, String> fallbackName;

    Records(Logger log, java.util.function.Function<UUID, String> fallbackName) { this.log = log; this.fallbackName = fallbackName; }

    CompletableFuture<Void> open(Path databaseFile, Path sqliteFile) {
        return CompletableFuture.runAsync(() -> {
            try {
                var settings = DatabaseSettings.load(databaseFile);
                maria = settings.mariaDb();
                holder = settings.holder(sqliteFile);
                String tail = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
                try (var s = holder.get().createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS codex_dungeon_record(dungeon VARCHAR(48) NOT NULL,player VARCHAR(36) NOT NULL,"
                            + "clears INT NOT NULL,best_ms BIGINT NOT NULL,best_at BIGINT NOT NULL,first_at BIGINT NOT NULL,PRIMARY KEY(dungeon,player))" + tail);
                    s.execute("CREATE TABLE IF NOT EXISTS codex_dungeon_run(run_id VARCHAR(36) PRIMARY KEY,dungeon VARCHAR(48) NOT NULL,"
                            + "player VARCHAR(36) NOT NULL,slot INT NOT NULL,result VARCHAR(24) NOT NULL,time_ms BIGINT NOT NULL,"
                            + "started_at BIGINT NOT NULL,ended_at BIGINT NOT NULL,first_clear INT NOT NULL)" + tail);
                }
                log.info("기록 DB: " + settings.modeName() + (maria ? " " + settings.target() : " " + sqliteFile.getFileName()));
            } catch (Exception e) {
                throw new IllegalStateException("기록 DB 열기 실패: " + e, e);
            }
        }, worker);
    }

    boolean ready() { return holder != null; }

    void loadTitles(Path titlesFile) {
        var out = new HashMap<String, Title>();
        var yml = YamlConfiguration.loadConfiguration(titlesFile.toFile());
        var section = yml.getConfigurationSection("titles");
        if (section != null) for (String id : section.getKeys(false)) {
            String name = section.getString(id + ".name", "");
            int color = 0xFFFFFF;
            String hex = section.getString(id + ".color", "");
            if (hex.matches("#[0-9A-Fa-f]{6}")) color = Integer.parseInt(hex.substring(1), 16);
            if (!name.isBlank()) out.put(id, new Title(name, color));
        }
        titles = Map.copyOf(out);
    }

    CompletableFuture<Personal> personal(String dungeon, UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try (var s = holder.get().prepareStatement("SELECT clears,best_ms FROM codex_dungeon_record WHERE dungeon=? AND player=?")) {
                s.setString(1, dungeon); s.setString(2, player.toString());
                try (var r = s.executeQuery()) { return r.next() ? new Personal(r.getInt(1), r.getLong(2)) : Personal.NONE; }
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }, worker);
    }

    /** 서버 최고 기록 + 보유자 한글 닉네임 + 지금 장착한 접두·접미 칭호 (오프라인이어도 DB 에서). */
    CompletableFuture<Holder> serverBest(String dungeon) {
        return CompletableFuture.supplyAsync(() -> {
            try { return best(dungeon); } catch (SQLException e) { throw new IllegalStateException(e); }
        }, worker);
    }

    private Holder best(String dungeon) throws SQLException {
        UUID who; long ms;
        try (var s = holder.get().prepareStatement("SELECT player,best_ms FROM codex_dungeon_record WHERE dungeon=? AND clears>0 ORDER BY best_ms ASC,best_at ASC LIMIT 1")) {
            s.setString(1, dungeon);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Holder.NONE;
                who = UUID.fromString(r.getString(1)); ms = r.getLong(2);
            }
        }
        String name = "";
        try (var s = holder.get().prepareStatement("SELECT nickname FROM codex_nicknames WHERE owner=?")) {
            s.setString(1, who.toString());
            try (var r = s.executeQuery()) { if (r.next()) name = r.getString(1); }
        } catch (SQLException missingTable) { /* 닉네임 표가 다른 DB 면 아래 대체값 */ }
        Title prefix = Title.NONE, suffix = Title.NONE;
        try (var s = holder.get().prepareStatement("SELECT prefix_id,suffix_id FROM codex_title_player WHERE player=?")) {
            s.setString(1, who.toString());
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    prefix = titles.getOrDefault(r.getString(1), Title.NONE);
                    suffix = titles.getOrDefault(r.getString(2), Title.NONE);
                }
            }
        } catch (SQLException missingTable) { /* 칭호 없음 */ }
        if (name == null || name.isBlank()) name = fallbackName.apply(who);
        return new Holder(ms, name == null ? "" : name, prefix, suffix);
    }

    /** 클리어 확정: 판 장부에 적고(처음일 때만) 개인 기록 갱신, 서버 최고와 비교. 한 트랜잭션. */
    CompletableFuture<Clear> clear(UUID runId, String dungeon, UUID player, int slot, long timeMs, long startedAt, long endedAt) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; ; attempt++) {
                try {
                    return clearOnce(runId, dungeon, player, slot, timeMs, startedAt, endedAt);
                } catch (SQLException e) {
                    if (attempt < 3 && (e.getErrorCode() == 1213 || e.getErrorCode() == 1205 || "40001".equals(e.getSQLState()))) continue;
                    throw new IllegalStateException(e);
                }
            }
        }, worker);
    }

    private Clear clearOnce(UUID runId, String dungeon, UUID player, int slot, long timeMs, long startedAt, long endedAt) throws SQLException {
        Holder before = best(dungeon);
        var db = holder.begin();
        boolean committed = false;
        try {
            int inserted;
            try (var s = db.prepareStatement("INSERT " + (maria ? "IGNORE" : "OR IGNORE")
                    + " INTO codex_dungeon_run(run_id,dungeon,player,slot,result,time_ms,started_at,ended_at,first_clear) VALUES(?,?,?,?,'clear',?,?,?,0)")) {
                s.setString(1, runId.toString()); s.setString(2, dungeon); s.setString(3, player.toString()); s.setInt(4, slot);
                s.setLong(5, timeMs); s.setLong(6, startedAt); s.setLong(7, endedAt);
                inserted = s.executeUpdate();
            }
            if (inserted == 0) { db.commit(); committed = true; return new Clear(true, false, -1, false, false, before); }
            int clears = 0; long prev = -1;
            try (var s = db.prepareStatement("SELECT clears,best_ms FROM codex_dungeon_record WHERE dungeon=? AND player=?" + (maria ? " FOR UPDATE" : ""))) {
                s.setString(1, dungeon); s.setString(2, player.toString());
                try (var r = s.executeQuery()) { if (r.next()) { clears = r.getInt(1); prev = r.getLong(2); } }
            }
            boolean first = clears == 0;
            boolean newPersonal = prev < 0 || timeMs < prev;
            if (clears == 0 && prev < 0) {
                try (var s = db.prepareStatement("INSERT INTO codex_dungeon_record(dungeon,player,clears,best_ms,best_at,first_at) VALUES(?,?,1,?,?,?)")) {
                    s.setString(1, dungeon); s.setString(2, player.toString()); s.setLong(3, timeMs); s.setLong(4, endedAt); s.setLong(5, endedAt);
                    s.executeUpdate();
                }
            } else {
                try (var s = db.prepareStatement("UPDATE codex_dungeon_record SET clears=clears+1,"
                        + "best_at=CASE WHEN ?<best_ms THEN ? ELSE best_at END,best_ms=CASE WHEN ?<best_ms THEN ? ELSE best_ms END WHERE dungeon=? AND player=?")) {
                    s.setLong(1, timeMs); s.setLong(2, endedAt); s.setLong(3, timeMs); s.setLong(4, timeMs);
                    s.setString(5, dungeon); s.setString(6, player.toString());
                    s.executeUpdate();
                }
            }
            if (first) try (var s = db.prepareStatement("UPDATE codex_dungeon_run SET first_clear=1 WHERE run_id=?")) {
                s.setString(1, runId.toString()); s.executeUpdate();
            }
            db.commit();
            committed = true;
            holder.end();
            boolean newServer = before.ms() < 0 || timeMs < before.ms();
            return new Clear(false, first, prev, newPersonal, newServer, best(dungeon));
        } catch (SQLException | RuntimeException e) {
            if (!committed) holder.rollback();
            throw e;
        } finally {
            if (holder.inTransaction()) holder.end();
        }
    }

    /** 실패·포기·종료 판도 장부에 남긴다 (조사용, 보상과 무관). */
    void logEnd(UUID runId, String dungeon, UUID player, int slot, String result, long startedAt, long endedAt) {
        if (holder == null) return;
        worker.execute(() -> {
            try (var s = holder.get().prepareStatement("INSERT " + (maria ? "IGNORE" : "OR IGNORE")
                    + " INTO codex_dungeon_run(run_id,dungeon,player,slot,result,time_ms,started_at,ended_at,first_clear) VALUES(?,?,?,?,?,0,?,?,0)")) {
                s.setString(1, runId.toString()); s.setString(2, dungeon); s.setString(3, player.toString()); s.setInt(4, slot);
                s.setString(5, result); s.setLong(6, startedAt); s.setLong(7, endedAt);
                s.executeUpdate();
            } catch (SQLException e) { log.warning("판 장부 기록 실패 " + runId + ": " + e); }
        });
    }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (holder != null) try { holder.close(); } catch (Exception ignored) {}
    }
}
