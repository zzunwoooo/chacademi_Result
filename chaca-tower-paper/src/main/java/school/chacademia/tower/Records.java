package school.chacademia.tower;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 탑 기록: 개인 최고 (도달 층이 높을수록, 같으면 빠를수록) + 판 장부. 공용 MariaDB (없으면 records.db).
 * DB 는 작업 스레드 하나에서만.
 */
final class Records implements AutoCloseable {
    record Best(int floor, long ms, int runs) {
        static final Best NONE = new Best(0, -1, 0);
        boolean better(int f, long t) { return f > floor || (f == floor && f > 0 && (ms < 0 || t < ms)); }
    }
    record Rank(UUID player, int floor, long ms) {}
    /** 판 끝 처리 결과. firstReach = 이번에 처음 도달한 층 (그 위로만). */
    record Result(Best before, boolean newBest) {}

    private final Logger log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "ChacaTower-DB"); t.setDaemon(true); return t; });
    private ConnectionHolder holder;

    Records(Logger log) { this.log = log; }

    CompletableFuture<Void> open(Path databaseFile, Path sqliteFile) {
        return CompletableFuture.runAsync(() -> {
            try {
                var settings = DatabaseSettings.load(databaseFile);
                holder = settings.holder(sqliteFile);
                String tail = settings.mariaDb() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
                try (var s = holder.get().createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS codex_tower_record(player VARCHAR(36) PRIMARY KEY,best_floor INT NOT NULL,"
                            + "best_ms BIGINT NOT NULL,runs INT NOT NULL,best_at BIGINT NOT NULL)" + tail);
                    s.execute("CREATE TABLE IF NOT EXISTS codex_tower_run(run_id VARCHAR(36) PRIMARY KEY,player VARCHAR(36) NOT NULL,"
                            + "floor INT NOT NULL,time_ms BIGINT NOT NULL,result VARCHAR(24) NOT NULL,cards TEXT NOT NULL,"
                            + "started_at BIGINT NOT NULL,ended_at BIGINT NOT NULL)" + tail);
                }
                log.info("기록 DB: " + settings.modeName());
            } catch (Exception e) { throw new IllegalStateException("기록 DB 열기 실패: " + e, e); }
        }, worker);
    }

    boolean ready() { return holder != null; }

    CompletableFuture<Best> best(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            if (holder == null) return Best.NONE;
            try (var ps = holder.get().prepareStatement("SELECT best_floor,best_ms,runs FROM codex_tower_record WHERE player=?")) {
                ps.setString(1, player.toString());
                try (var rs = ps.executeQuery()) { return rs.next() ? new Best(rs.getInt(1), rs.getLong(2), rs.getInt(3)) : Best.NONE; }
            } catch (Exception e) { log.warning("기록 읽기 실패: " + e); return Best.NONE; }
        }, worker);
    }

    /** 판 끝: 장부 한 줄 + 개인 최고 갱신. floor = 깬 층 수 (도달 층 - 1 이 아니라 마지막으로 깬 층). */
    CompletableFuture<Result> finish(Run run, int clearedFloor, String result, String cards) {
        long ms = run.elapsedMs();
        return CompletableFuture.supplyAsync(() -> {
            if (holder == null) return new Result(Best.NONE, false);
            try {
                var db = holder.get();
                try (var ps = db.prepareStatement("INSERT INTO codex_tower_run(run_id,player,floor,time_ms,result,cards,started_at,ended_at) VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, run.runId.toString()); ps.setString(2, run.player.toString()); ps.setInt(3, clearedFloor);
                    ps.setLong(4, ms); ps.setString(5, result); ps.setString(6, cards); ps.setLong(7, run.startedAt); ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                Best before = Best.NONE;
                try (var ps = db.prepareStatement("SELECT best_floor,best_ms,runs FROM codex_tower_record WHERE player=?")) {
                    ps.setString(1, run.player.toString());
                    try (var rs = ps.executeQuery()) { if (rs.next()) before = new Best(rs.getInt(1), rs.getLong(2), rs.getInt(3)); }
                }
                boolean better = before.better(clearedFloor, ms);
                if (before.runs() == 0 && before.floor() == 0 && before.ms() < 0) {
                    try (var ps = db.prepareStatement("INSERT INTO codex_tower_record(player,best_floor,best_ms,runs,best_at) VALUES(?,?,?,1,?)")) {
                        ps.setString(1, run.player.toString()); ps.setInt(2, clearedFloor); ps.setLong(3, clearedFloor > 0 ? ms : -1); ps.setLong(4, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                } else if (better) {
                    try (var ps = db.prepareStatement("UPDATE codex_tower_record SET best_floor=?,best_ms=?,runs=runs+1,best_at=? WHERE player=?")) {
                        ps.setInt(1, clearedFloor); ps.setLong(2, ms); ps.setLong(3, System.currentTimeMillis()); ps.setString(4, run.player.toString());
                        ps.executeUpdate();
                    }
                } else {
                    try (var ps = db.prepareStatement("UPDATE codex_tower_record SET runs=runs+1 WHERE player=?")) { ps.setString(1, run.player.toString()); ps.executeUpdate(); }
                }
                return new Result(before, better && clearedFloor > 0);
            } catch (Exception e) { log.warning("기록 저장 실패: " + e); return new Result(Best.NONE, false); }
        }, worker);
    }

    CompletableFuture<List<Rank>> top(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            var out = new ArrayList<Rank>();
            if (holder == null) return out;
            try (var ps = holder.get().prepareStatement("SELECT player,best_floor,best_ms FROM codex_tower_record WHERE best_floor>0 ORDER BY best_floor DESC,best_ms ASC LIMIT ?")) {
                ps.setInt(1, limit);
                try (var rs = ps.executeQuery()) { while (rs.next()) out.add(new Rank(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getLong(3))); }
            } catch (Exception e) { log.warning("랭킹 읽기 실패: " + e); }
            return out;
        }, worker);
    }

    @Override public void close() {
        worker.shutdown();
        try { worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (holder != null) holder.close(); } catch (Exception ignored) {}
    }
}
