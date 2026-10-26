package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 운영 기록 표 codex_audit_log (메모 #92). 기록 스레드 하나만 이 객체를 쓴다 (게임 스레드 IO 없음).
 * SQLite(서버 단독, audit.db) / MariaDB(공유) 둘 다 같은 표 이름·같은 칸. 칭호 장부(codex_title_awards)와 같은 방식.
 *
 * <pre>
 * codex_audit_log
 *   id          BIGINT 자동 증가 (기본 키, 들어온 순서)
 *   at_ms       BIGINT  시각 (유닉스 ms, UTC 기준 숫자)
 *   server      VARCHAR(48)  기록한 서버 (audit.yml server-name, 비우면 quests.yml server-id)
 *   player      VARCHAR(36)  플레이어 UUID (시스템 기록은 "")
 *   player_name VARCHAR(64)  그때의 한글 닉네임 (없으면 계정 이름)
 *   kind        VARCHAR(32)  종류 (마법·상점·퀘스트… AuditLog.KINDS)
 *   summary     VARCHAR(255) 한 줄 한국어 설명
 *   detail      TEXT         상세 JSON 객체 {"키":"값"} (값은 전부 문자열)
 * 인덱스: (player, at_ms) · (kind, at_ms) · (at_ms)
 * </pre>
 */
final class AuditStore implements AutoCloseable {
    static final String TABLE = "codex_audit_log";
    /** 서버 통계 (메모 #96): player VARCHAR(36), stat_key VARCHAR(96), count BIGINT(누적), updated_at BIGINT(ms). 기본 키 (player, stat_key). */
    static final String STATS = "codex_player_stats";
    record Row(long id, long at, String server, String player, String name, String kind, String summary, String detail) {}

    private final ConnectionHolder holder;
    private final boolean maria;

    AuditStore(DatabaseSettings settings, Path sqlite) throws Exception {
        maria = settings.mariaDb();
        holder = settings.holder(sqlite);
        try (var s = holder.get().createStatement()) {
            if (maria) {
                s.execute("CREATE TABLE IF NOT EXISTS " + TABLE + "(id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,at_ms BIGINT NOT NULL,"
                        + "server VARCHAR(48) NOT NULL,player VARCHAR(36) NOT NULL,player_name VARCHAR(64) NOT NULL,kind VARCHAR(32) NOT NULL,"
                        + "summary VARCHAR(255) NOT NULL,detail TEXT NOT NULL,"
                        + "INDEX idx_audit_player(player,at_ms),INDEX idx_audit_kind(kind,at_ms),INDEX idx_audit_time(at_ms))"
                        + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            } else {
                s.execute("CREATE TABLE IF NOT EXISTS " + TABLE + "(id INTEGER PRIMARY KEY AUTOINCREMENT,at_ms BIGINT NOT NULL,"
                        + "server VARCHAR(48) NOT NULL,player VARCHAR(36) NOT NULL,player_name VARCHAR(64) NOT NULL,kind VARCHAR(32) NOT NULL,"
                        + "summary VARCHAR(255) NOT NULL,detail TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_audit_player ON " + TABLE + "(player,at_ms)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_audit_kind ON " + TABLE + "(kind,at_ms)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON " + TABLE + "(at_ms)");
            }
            // 서버 통계 누적 카운터 (메모 #96): 플레이어 × 항목 한 줄, count 는 누적 합.
            s.execute("CREATE TABLE IF NOT EXISTS " + STATS + "(player VARCHAR(36) NOT NULL,stat_key VARCHAR(96) NOT NULL,count BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL,PRIMARY KEY(player,stat_key))" + (maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : ""));
        }
    }

    boolean shared() { return maria; }

    /** 한 묶음을 한 트랜잭션으로 넣는다. 실패하면 되돌리고 예외를 던진다 (호출 쪽이 파일로 남긴다). */
    void insert(List<AuditLog.Entry> rows) throws SQLException {
        if (rows.isEmpty()) return;
        var db = holder.begin();
        try (var s = db.prepareStatement("INSERT INTO " + TABLE + "(at_ms,server,player,player_name,kind,summary,detail) VALUES(?,?,?,?,?,?,?)")) {
            for (var r : rows) {
                s.setLong(1, r.at()); s.setString(2, r.server()); s.setString(3, r.player()); s.setString(4, r.name());
                s.setString(5, r.kind()); s.setString(6, r.summary()); s.setString(7, r.detail());
                s.addBatch();
            }
            s.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException e) {
            try { holder.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            holder.end();
        }
    }

    /** 통계 증가분을 한 트랜잭션으로 더한다 (있으면 count += 증가분). 실패하면 되돌리고 예외. */
    void addStats(List<AuditStats.Delta> rows, long now) throws SQLException {
        if (rows.isEmpty()) return;
        String sql = maria
                ? "INSERT INTO " + STATS + "(player,stat_key,count,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE count=count+VALUES(count),updated_at=VALUES(updated_at)"
                : "INSERT INTO " + STATS + "(player,stat_key,count,updated_at) VALUES(?,?,?,?) ON CONFLICT(player,stat_key) DO UPDATE SET count=count+excluded.count,updated_at=excluded.updated_at";
        var db = holder.begin();
        try (var s = db.prepareStatement(sql)) {
            for (var r : rows) { s.setString(1, r.player()); s.setString(2, r.key()); s.setLong(3, r.amount()); s.setLong(4, now); s.addBatch(); }
            s.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException e) {
            try { holder.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            holder.end();
        }
    }

    /** 오래된 기록 지우기: 5000줄씩 나눠 지운다 (긴 잠금 방지). 지운 줄 수. */
    int prune(long beforeMs) throws SQLException {
        int total = 0;
        String sql = maria ? "DELETE FROM " + TABLE + " WHERE at_ms<? LIMIT 5000"
                : "DELETE FROM " + TABLE + " WHERE id IN (SELECT id FROM " + TABLE + " WHERE at_ms<? LIMIT 5000)";
        for (int round = 0; round < 400; round++) {
            int n;
            try (var s = holder.get().prepareStatement(sql)) { s.setLong(1, beforeMs); n = s.executeUpdate(); }
            total += n;
            if (n < 5000) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return total;
    }

    /** 한 유저의 기록 (최근 것부터). kind 가 null 이면 전체 종류. */
    List<Row> player(String player, String kind, long sinceMs, int limit, int offset) throws SQLException {
        String sql = "SELECT id,at_ms,server,player,player_name,kind,summary,detail FROM " + TABLE + " WHERE player=? AND at_ms>=?"
                + (kind == null ? "" : " AND kind=?") + " ORDER BY at_ms DESC,id DESC LIMIT ? OFFSET ?";
        try (var s = holder.get().prepareStatement(sql)) {
            int i = 1;
            s.setString(i++, player); s.setLong(i++, sinceMs);
            if (kind != null) s.setString(i++, kind);
            s.setInt(i++, limit); s.setInt(i, offset);
            return rows(s);
        }
    }

    /** 글자 검색 (설명·상세·닉네임에 포함). 기간을 꼭 걸어 오래된 줄까지 훑지 않게 한다. */
    List<Row> search(String text, long sinceMs, int limit) throws SQLException {
        // 이스케이프 문자는 '!' (MariaDB 는 '\' 를 따옴표 안에서 다르게 읽어서 백슬래시를 쓰지 않는다)
        String like = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String sql = "SELECT id,at_ms,server,player,player_name,kind,summary,detail FROM " + TABLE
                + " WHERE at_ms>=? AND (summary LIKE ? ESCAPE '!' OR detail LIKE ? ESCAPE '!' OR player_name LIKE ? ESCAPE '!')"
                + " ORDER BY at_ms DESC,id DESC LIMIT ?";
        try (var s = holder.get().prepareStatement(sql)) {
            s.setLong(1, sinceMs); s.setString(2, like); s.setString(3, like); s.setString(4, like); s.setInt(5, limit);
            return rows(s);
        }
    }

    private static List<Row> rows(java.sql.PreparedStatement s) throws SQLException {
        var out = new ArrayList<Row>();
        try (var r = s.executeQuery()) {
            while (r.next()) out.add(new Row(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
        }
        return out;
    }

    @Override public void close() throws SQLException { holder.close(); }
}
