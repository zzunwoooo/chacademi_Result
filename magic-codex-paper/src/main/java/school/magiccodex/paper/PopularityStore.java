package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 인기도 저장소 (메모 #87). PopularityBridge 의 IO 스레드 하나만 쓴다.
 * MariaDB: codex_popularity (점수) / codex_popularity_daily (오늘치 상·하락 기록) — 학교·야생 공유.
 * SQLite: Bridge 데이터 폴더의 popularity.db (단독 서버).
 *
 * <p>올리기·내리기는 한 거래 안에서 "오늘 (행위자→대상) 기록 확인 → 기록 추가 → 점수 증감" 을 한다.
 * MariaDB 는 오늘 기록을 FOR UPDATE 로 읽어 두 서버가 같은 순간 눌러도 하루 1번을 넘지 않는다.
 */
final class PopularityStore implements AutoCloseable {
    /** 오늘치 기록 한 줄. delta +1 올림 · -1 내림, ticket = 해제권을 써서 한 번 더 한 것. */
    record Daily(UUID actor, UUID target, int delta, boolean ticket, long at) {}
    record Result(PopularityRules.Outcome outcome, int score) {}

    private final ConnectionHolder holder;
    private final boolean maria;
    private final String scores, daily;

    PopularityStore(DatabaseSettings settings, Path sqliteFile) throws Exception {
        maria = settings.mariaDb();
        scores = settings.table("popularity", "popularity");
        daily = settings.table("popularity_daily", "popularity_daily");
        holder = settings.holder(sqliteFile);
        String engine = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (var s = holder.get().createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + scores + " (player CHAR(36) NOT NULL PRIMARY KEY, score INT NOT NULL DEFAULT 0,"
                    + " updated BIGINT NOT NULL DEFAULT 0)" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS " + daily + " (actor CHAR(36) NOT NULL, target CHAR(36) NOT NULL, day CHAR(10) NOT NULL,"
                    + " seq INT NOT NULL, delta INT NOT NULL, ticket INT NOT NULL DEFAULT 0, created BIGINT NOT NULL,"
                    + " PRIMARY KEY (actor, target, day, seq))" + engine);
        }
    }

    private interface Tx<T> { T run(Connection db) throws SQLException; }

    /** 트랜잭션. MariaDB 교착(1213)·직렬화 실패(40001)는 최대 3번 다시 시도 (AffinityStore 와 같은 방식). */
    private <T> T tx(Tx<T> body) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            Connection db = holder.begin();
            try {
                T result = body.run(db);
                db.commit();
                return result;
            } catch (SQLException e) {
                holder.rollback();
                boolean retry = (e.getErrorCode() == 1213 || "40001".equals(e.getSQLState())) && attempt < 2;
                if (!retry) throw e;
            } catch (RuntimeException e) {
                holder.rollback();
                throw e;
            } finally {
                holder.end();
            }
        }
    }

    private void ensure(Connection db, UUID player) throws SQLException {
        String sql = maria
                ? "INSERT INTO " + scores + " (player, score, updated) VALUES (?,0,0) ON DUPLICATE KEY UPDATE score=score"
                : "INSERT OR IGNORE INTO " + scores + " (player, score, updated) VALUES (?,0,0)";
        try (PreparedStatement s = db.prepareStatement(sql)) { s.setString(1, player.toString()); s.executeUpdate(); }
    }

    private int read(Connection db, UUID player, boolean lock) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT score FROM " + scores + " WHERE player=?" + (lock && maria ? " FOR UPDATE" : ""))) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    /**
     * 올리기(up)·내리기. allowTicket = 서버가 해제권 1개를 미리 떼어 둔 경우만 true.
     * 결과가 LIMIT·SELF 면 아무것도 바꾸지 않는다.
     */
    Result vote(UUID actor, UUID target, String day, boolean up, boolean allowTicket, long now) throws SQLException {
        return tx(db -> {
            if (actor.equals(target)) return new Result(PopularityRules.Outcome.SELF, read(db, target, false));
            // 대상 점수 행을 먼저 잠근다: 같은 대상에게 동시에 오는 요청이 차례로 처리된다.
            ensure(db, target);
            int before = read(db, target, true);
            int count;
            try (PreparedStatement s = db.prepareStatement("SELECT COUNT(*) FROM " + daily + " WHERE actor=? AND target=? AND day=?" + (maria ? " FOR UPDATE" : ""))) {
                s.setString(1, actor.toString()); s.setString(2, target.toString()); s.setString(3, day);
                try (ResultSet r = s.executeQuery()) { count = r.next() ? r.getInt(1) : 0; }
            }
            var outcome = PopularityRules.decide(actor, target, count > 0, allowTicket);
            if (outcome == PopularityRules.Outcome.LIMIT) return new Result(outcome, before);
            int delta = PopularityRules.delta(up);
            try (PreparedStatement s = db.prepareStatement("INSERT INTO " + daily + " (actor, target, day, seq, delta, ticket, created) VALUES (?,?,?,?,?,?,?)")) {
                s.setString(1, actor.toString()); s.setString(2, target.toString()); s.setString(3, day); s.setInt(4, count);
                s.setInt(5, delta); s.setInt(6, outcome == PopularityRules.Outcome.TICKET ? 1 : 0); s.setLong(7, now);
                s.executeUpdate();
            }
            int after = PopularityRules.clamp((long) before + delta);
            try (PreparedStatement s = db.prepareStatement("UPDATE " + scores + " SET score=?, updated=? WHERE player=?")) {
                s.setInt(1, after); s.setLong(2, now); s.setString(3, target.toString()); s.executeUpdate();
            }
            return new Result(outcome, after);
        });
    }

    /** 점수 (행이 없으면 0). */
    int score(UUID player) throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement("SELECT score FROM " + scores + " WHERE player=?")) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    /** 관리: 점수 설정 (초기화 = 0). */
    void setScore(UUID player, int score, long now) throws SQLException {
        tx(db -> {
            ensure(db, player);
            try (PreparedStatement s = db.prepareStatement("UPDATE " + scores + " SET score=?, updated=? WHERE player=?")) {
                s.setInt(1, PopularityRules.clamp(score)); s.setLong(2, now); s.setString(3, player.toString()); s.executeUpdate();
            }
            return null;
        });
    }

    /** 그날 이 사람이 조절한 기록 (행위자 기준, 시각 순). */
    List<Daily> today(UUID actor, String day) throws SQLException {
        var out = new ArrayList<Daily>();
        try (PreparedStatement s = holder.get().prepareStatement("SELECT target, delta, ticket, created FROM " + daily + " WHERE actor=? AND day=? ORDER BY created, seq")) {
            s.setString(1, actor.toString()); s.setString(2, day);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Daily(actor, UUID.fromString(r.getString(1)), r.getInt(2), r.getInt(3) != 0, r.getLong(4)));
            }
        }
        return out;
    }

    /** 그날 이 사람(행위자)의 기록 지우기 = 오늘 제한 초기화. 점수는 그대로. 반환 = 지운 줄 수. */
    int resetDay(UUID actor, String day) throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement("DELETE FROM " + daily + " WHERE actor=? AND day=?")) {
            s.setString(1, actor.toString()); s.setString(2, day);
            return s.executeUpdate();
        }
    }

    /** 오래된 기록 정리 (오늘 이전 keepDays 일보다 오래된 것). day 는 yyyy-MM-dd 라 문자열 비교로 된다. */
    int prune(String beforeDay) throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement("DELETE FROM " + daily + " WHERE day<?")) {
            s.setString(1, beforeDay);
            return s.executeUpdate();
        }
    }

    @Override public void close() throws SQLException { holder.close(); }
}
