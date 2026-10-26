package kr.chacademy.portrait.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChacaPortrait 테이블 (모두 cport_ 접두어). 이 클래스의 메서드는 DB 스레드 안에서만 호출한다 ({@link Database#call}).
 * 서버 간 공유(MariaDB)를 전제로, 예산·작업 잠금·다시 그리기 상태 변경은 모두 조건부 UPDATE로 한 번만 성공하게 한다.
 */
public final class PortraitStorage {

    public record Portrait(String sha, byte[] png, String model, String source, long createdAt) {
    }

    /** error = 실패 사유 (없으면 null), updatedAt = 마지막 상태 변경 시각. */
    public record Reroll(String token, UUID player, String server, byte[] item, String prompt, String state, long createdAt,
                         String error, long updatedAt) {
    }

    public record BudgetState(long spent, long reserved) {
    }

    public static final String R_PREPARED = "PREPARED"; // durable intent before inventory debit
    public static final String R_CANCELLED = "CANCELLED"; // intent never debited
    public static final String R_PENDING = "PENDING";   // 아이템 차감됨, 대기 중 (유료 호출 전 — 실패하면 반환)
    public static final String R_STARTED = "STARTED";   // 유료 생성 시작됨 — 이후 실패해도 아이템은 소모
    public static final String R_DONE = "DONE";         // 성공 (아이템 소모)
    public static final String R_CONSUMED = "CONSUMED"; // 생성 시작 후 실패 (아이템 소모, 반환 없음)
    public static final String R_REFUND = "REFUND_DUE"; // 시작 전 실패 → 아이템 돌려줘야 함
    public static final String R_REFUNDED = "REFUNDED";

    private final Database db;

    public PortraitStorage(Database db) {
        this.db = db;
    }

    private String blob() {
        return db.isMariaDb() ? "MEDIUMBLOB" : "BLOB";
    }

    public void init() throws SQLException {
        db.with(c -> {
            try (Statement s = c.createStatement()) {
                String text = db.textType();
                s.execute("CREATE TABLE IF NOT EXISTS cport_portrait (uuid VARCHAR(36) PRIMARY KEY, sha CHAR(64) NOT NULL, "
                        + "png " + blob() + " NOT NULL, model VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, prompt " + text + ", "
                        + "cost_micro BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS cport_state (uuid VARCHAR(36) PRIMARY KEY, auto_attempts INT NOT NULL DEFAULT 0, "
                        + "last_error " + text + ", running_server VARCHAR(32), running_since BIGINT NOT NULL DEFAULT 0, "
                        + "last_reroll BIGINT NOT NULL DEFAULT 0, reroll_day VARCHAR(10), reroll_count INT NOT NULL DEFAULT 0)");
                s.execute("CREATE TABLE IF NOT EXISTS cport_reroll (token VARCHAR(32) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, "
                        + "server VARCHAR(32) NOT NULL, item " + blob() + " NOT NULL, prompt " + text + ", state VARCHAR(16) NOT NULL, "
                        + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS cport_budget (id INT PRIMARY KEY, spent BIGINT NOT NULL DEFAULT 0, "
                        + "reserved BIGINT NOT NULL DEFAULT 0)");
                s.execute("CREATE TABLE IF NOT EXISTS cport_reservation (id VARCHAR(32) PRIMARY KEY, server VARCHAR(32) NOT NULL, "
                        + "amount BIGINT NOT NULL, created_at BIGINT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS cport_usage (id " + db.autoId() + ", at BIGINT NOT NULL, uuid VARCHAR(36), "
                        + "kind VARCHAR(16) NOT NULL, model VARCHAR(64) NOT NULL, text_in BIGINT NOT NULL, image_in BIGINT NOT NULL, "
                        + "output BIGINT NOT NULL, cost_micro BIGINT NOT NULL, ok INT NOT NULL)");
                try {
                    s.execute("CREATE INDEX cport_reroll_uuid ON cport_reroll (uuid, state)");
                } catch (SQLException ignored) {
                    // 이미 있음
                }
                // 추가 컬럼 (기존 테이블 호환: 이미 있으면 무시). fail_reason = 실패 사유, notified = 소모 안내를 플레이어에게 보냈는지.
                for (String column : new String[]{"fail_reason " + text, "notified INT NOT NULL DEFAULT 0"}) {
                    try {
                        s.execute("ALTER TABLE cport_reroll ADD COLUMN " + column);
                    } catch (SQLException ignored) {
                        // 이미 있음
                    }
                }
                // 추가 컬럼이 실제로 있는지 확인 (ALTER 권한이 없어 조용히 실패했다면 여기서 오류 → 플러그인이 켜지지 않음)
                try (ResultSet rs = s.executeQuery("SELECT fail_reason, notified FROM cport_reroll WHERE 1=0")) {
                    rs.next();
                }
                s.execute(db.isMariaDb() ? "INSERT IGNORE INTO cport_budget (id, spent, reserved) VALUES (1, 0, 0)"
                        : "INSERT OR IGNORE INTO cport_budget (id, spent, reserved) VALUES (1, 0, 0)");
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 일러스트

    public Portrait portrait(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sha, png, model, source, created_at FROM cport_portrait WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Portrait(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4), rs.getLong(5)) : null;
                }
            }
        });
    }

    public String sha(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT sha FROM cport_portrait WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** 여러 플레이어의 현재 SHA를 한 번에 (서버 간 완성 전달 확인용). 일러스트가 없는 플레이어는 결과에 없다. */
    public Map<UUID, String> shas(Collection<UUID> ids) throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<UUID> all = new ArrayList<>(ids);
        return db.with(c -> {
            for (int from = 0; from < all.size(); from += 200) {
                List<UUID> part = all.subList(from, Math.min(all.size(), from + 200));
                StringBuilder marks = new StringBuilder();
                for (int i = 0; i < part.size(); i++) {
                    marks.append(i == 0 ? "?" : ",?");
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, sha FROM cport_portrait WHERE uuid IN (" + marks + ")")) {
                    for (int i = 0; i < part.size(); i++) {
                        ps.setString(i + 1, part.get(i).toString());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                        }
                    }
                }
            }
            return out;
        });
    }

    public void savePortrait(UUID id, String sha, byte[] png, String model, String source, String prompt, long cost) throws SQLException {
        db.with(c -> {
            String sql = db.isMariaDb()
                    ? "INSERT INTO cport_portrait (uuid, sha, png, model, source, prompt, cost_micro, created_at) VALUES (?,?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE sha=VALUES(sha), png=VALUES(png), model=VALUES(model), source=VALUES(source), "
                    + "prompt=VALUES(prompt), cost_micro=VALUES(cost_micro), created_at=VALUES(created_at)"
                    : "INSERT INTO cport_portrait (uuid, sha, png, model, source, prompt, cost_micro, created_at) VALUES (?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET sha=excluded.sha, png=excluded.png, model=excluded.model, source=excluded.source, "
                    + "prompt=excluded.prompt, cost_micro=excluded.cost_micro, created_at=excluded.created_at";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id.toString());
                ps.setString(2, sha);
                ps.setBytes(3, png);
                ps.setString(4, model);
                ps.setString(5, source);
                ps.setString(6, prompt);
                ps.setLong(7, cost);
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 생성 결과 확정 (한 트랜잭션). 다시 그리기면 먼저 STARTED→DONE 전이를 시도하고, 이미 다른 상태(소모 처리됨 등)면
     * 아무것도 저장하지 않고 false. 성공하면 일러스트 저장·자동 시도 초기화까지 함께 커밋한다.
     * 다시 그리기 횟수·쿨다운은 시도 기준이라 {@link #markRerollStarted}에서 이미 기록했다 (day는 호환용으로만 받는다).
     */
    public boolean commitResult(UUID id, String rerollToken, String sha, byte[] png, String model, String source,
                                String prompt, long cost, String day, String owner, long generation) throws SQLException {
        return db.with(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // Lock the ownership row in the same transaction as the portrait overwrite.
                try (PreparedStatement ps = c.prepareStatement("SELECT running_server, running_since FROM cport_state WHERE uuid=?" + (db.isMariaDb() ? " FOR UPDATE" : ""))) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !owner.equals(rs.getString(1)) || generation != rs.getLong(2)) {
                            c.rollback(); return false;
                        }
                    }
                }
                if (rerollToken != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=? WHERE token=? AND state=?")) {
                        ps.setString(1, R_DONE);
                        ps.setLong(2, System.currentTimeMillis());
                        ps.setString(3, rerollToken);
                        ps.setString(4, R_STARTED);
                        if (ps.executeUpdate() != 1) {
                            c.rollback();
                            return false;
                        }
                    }
                }
                String sql = db.isMariaDb()
                        ? "INSERT INTO cport_portrait (uuid, sha, png, model, source, prompt, cost_micro, created_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE sha=VALUES(sha), png=VALUES(png), model=VALUES(model), source=VALUES(source), "
                        + "prompt=VALUES(prompt), cost_micro=VALUES(cost_micro), created_at=VALUES(created_at)"
                        : "INSERT INTO cport_portrait (uuid, sha, png, model, source, prompt, cost_micro, created_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET sha=excluded.sha, png=excluded.png, model=excluded.model, source=excluded.source, "
                        + "prompt=excluded.prompt, cost_micro=excluded.cost_micro, created_at=excluded.created_at";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, id.toString());
                    ps.setString(2, sha);
                    ps.setBytes(3, png);
                    ps.setString(4, model);
                    ps.setString(5, source);
                    ps.setString(6, prompt);
                    ps.setLong(7, cost);
                    ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                ensureState(c, id);
                try (PreparedStatement ps = c.prepareStatement("UPDATE cport_state SET auto_attempts=0, last_error=NULL WHERE uuid=?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    public boolean deletePortrait(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cport_portrait WHERE uuid=?")) {
                ps.setString(1, id.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------------ 상태·잠금

    private void ensureState(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.isMariaDb()
                ? "INSERT IGNORE INTO cport_state (uuid) VALUES (?)" : "INSERT OR IGNORE INTO cport_state (uuid) VALUES (?)")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    /** 작업 잠금. 다른 서버·작업이 잡고 있으면 false. staleMs보다 오래된 잠금은 죽은 것으로 보고 가져온다. */
    public long claimGeneration(UUID id, String server, long staleMs) throws SQLException {
        return db.with(c -> {
            ensureState(c, id);
            boolean auto=c.getAutoCommit();c.setAutoCommit(false);
            try {
                long now=System.currentTimeMillis();
                try (PreparedStatement ps=c.prepareStatement("UPDATE cport_state SET running_server=?, running_since=CASE WHEN running_since>=? THEN running_since+1 ELSE ? END WHERE uuid=? AND (running_server IS NULL OR running_since<?)")) {
                    ps.setString(1,server);ps.setLong(2,now);ps.setLong(3,now);ps.setString(4,id.toString());ps.setLong(5,now-staleMs);
                    if(ps.executeUpdate()!=1){c.rollback();return 0L;}
                }
                long token;
                try(PreparedStatement ps=c.prepareStatement("SELECT running_since FROM cport_state WHERE uuid=?")) {
                    ps.setString(1,id.toString());try(ResultSet rs=ps.executeQuery()){rs.next();token=rs.getLong(1);}
                }
                c.commit();return token;
            } catch(SQLException e){c.rollback();throw e;} finally {c.setAutoCommit(auto);}
        });
    }

    public void releaseGeneration(UUID id, String server, long generation) throws SQLException {
        db.with(c -> {try(PreparedStatement ps=c.prepareStatement("UPDATE cport_state SET running_server=NULL WHERE uuid=? AND running_server=? AND running_since=?")) {
            ps.setString(1,id.toString());ps.setString(2,server);ps.setLong(3,generation);ps.executeUpdate();
        }return null;});
    }

    /** 시작 시: 이 서버가 잡고 있던 잠금 해제 (지난 실행이 비정상 종료). */
    public int releaseAll(String server) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_state SET running_server=NULL WHERE running_server=?")) {
                ps.setString(1, server);
                return ps.executeUpdate();
            }
        });
    }

    public int autoAttempts(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT auto_attempts FROM cport_state WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public void recordAttempt(UUID id, boolean auto, String error) throws SQLException {
        db.with(c -> {
            ensureState(c, id);
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_state SET auto_attempts=auto_attempts+?, last_error=? WHERE uuid=?")) {
                ps.setInt(1, auto ? 1 : 0);
                ps.setString(2, error == null ? null : error.substring(0, Math.min(500, error.length())));
                ps.setString(3, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void resetState(UUID id) throws SQLException {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cport_state SET auto_attempts=0, last_error=NULL WHERE uuid=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public String lastError(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_error, auto_attempts, running_server FROM cport_state WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return "시도 " + rs.getInt(2) + "회, 진행 서버 " + (rs.getString(3) == null ? "없음" : rs.getString(3))
                            + (rs.getString(1) == null ? "" : ", 마지막 오류: " + rs.getString(1));
                }
            }
        });
    }

    /** {마지막 시도 시각, 오늘 시도 횟수} (day가 다르면 횟수 0). 유료 생성이 시작된 시도만 센다. */
    public long[] rerollUsage(UUID id, String day) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_reroll, reroll_day, reroll_count FROM cport_state WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new long[]{0, 0};
                    }
                    return new long[]{rs.getLong(1), day.equals(rs.getString(2)) ? rs.getInt(3) : 0};
                }
            }
        });
    }

    // ------------------------------------------------------------------ 다시 그리기 아이템 (2단계)

    public void insertReroll(String token, UUID id, String server, byte[] item, String prompt) throws SQLException {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cport_reroll (token, uuid, server, item, prompt, state, "
                    + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, token);
                ps.setString(2, id.toString());
                ps.setString(3, server);
                ps.setBytes(4, item);
                ps.setString(5, prompt);
                ps.setString(6, R_PREPARED);
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 상태 전이 (from → to). 정확히 한 번만 성공한다. */
    public boolean moveReroll(String token, String from, String to) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=? WHERE token=? AND state=?")) {
                ps.setString(1, to);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, token);
                ps.setString(4, from);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** 상태 전이 + 실패 사유 기록 (관리자 문의 대응용). 정확히 한 번만 성공한다. */
    public boolean moveReroll(String token, String from, String to, String error) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=?, fail_reason=? WHERE token=? AND state=?")) {
                ps.setString(1, to);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, clip(error));
                ps.setString(4, token);
                ps.setString(5, from);
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static String clip(String error) {
        return error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    /**
     * "생성 시작" 경계 (한 트랜잭션): PENDING→STARTED 전이와 함께 쿨다운 시각·오늘 시도 횟수를 기록한다.
     * 첫 유료 API 호출 직전에 부른다. 이미 다른 상태(종료 중 반환 처리 등)면 아무것도 바꾸지 않고 false → 호출 측은 유료 호출을 하지 않는다.
     * 이 전이가 성공한 뒤의 실패는 아이템을 돌려주지 않는다 ({@link #R_CONSUMED}).
     */
    public boolean markRerollStarted(String token, UUID id, String day) throws SQLException {
        return db.with(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=? WHERE token=? AND state=?")) {
                    ps.setString(1, R_STARTED);
                    ps.setLong(2, now);
                    ps.setString(3, token);
                    ps.setString(4, R_PENDING);
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return false;
                    }
                }
                ensureState(c, id);
                try (PreparedStatement ps = c.prepareStatement("UPDATE cport_state SET last_reroll=?, "
                        + "reroll_count=CASE WHEN reroll_day=? THEN reroll_count+1 ELSE 1 END, reroll_day=? WHERE uuid=?")) {
                    ps.setLong(1, now);
                    ps.setString(2, day);
                    ps.setString(3, day);
                    ps.setString(4, id.toString());
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** 생성 시작 후 실패: STARTED→CONSUMED (반환 없음). 정확히 한 번만 성공한다. */
    public boolean consumeReroll(String token, String error) throws SQLException {
        return moveReroll(token, R_STARTED, R_CONSUMED, error);
    }

    /** 시작·종료 시: 이 서버에서 대기 중이던(PENDING, 유료 호출 전) 다시 그리기 → 돌려줄 대상으로. */
    public int failPendingRerolls(String server) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=?, fail_reason=? WHERE server=? AND state=?")) {
                ps.setString(1, R_REFUND);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, "서버 재시작으로 중단 (생성 시작 전)");
                ps.setString(4, server);
                ps.setString(5, R_PENDING);
                return ps.executeUpdate();
            }
        });
    }

    /** 시작·종료 시: 이 서버에서 유료 생성이 시작된 채 끝나지 않은(STARTED) 다시 그리기 → 소모 처리 (반환 없음, 다음 접속 때 안내). */
    public int consumeStartedRerolls(String server) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=?, fail_reason=? WHERE server=? AND state=?")) {
                ps.setString(1, R_CONSUMED);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, "서버 재시작으로 중단 (생성 시작 후)");
                ps.setString(4, server);
                ps.setString(5, R_STARTED);
                return ps.executeUpdate();
            }
        });
    }

    /** 아직 안내하지 않은 소모 실패가 있으면 안내 완료로 바꾸고 그 수를 돌려준다 (접속 시 1회 안내용). */
    public int takeConsumedNotices(UUID id) throws SQLException {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET notified=1 WHERE uuid=? AND state=? AND notified=0")) {
                ps.setString(1, id.toString());
                ps.setString(2, R_CONSUMED);
                return ps.executeUpdate();
            }
        });
    }

    /** 소모 실패 안내를 이미 보냈음 (접속 중에 바로 안내한 경우). */
    public void markConsumedNotified(String token) throws SQLException {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET notified=1 WHERE token=?")) {
                ps.setString(1, token);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 관리자 반환 (/유저관리 <닉네임> 초상화 환불): 이 서버에서 차감했던 가장 최근 소모 실패(CONSUMED) 1건을 REFUND_DUE로 되돌린다.
     * 실제 지급은 기존 반환 경로(영수증 확인)가 한다. 대상이 없으면 null, 있으면 토큰.
     */
    public String refundConsumed(UUID id, String server) throws SQLException {
        return db.with(c -> {
            String token = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT token FROM cport_reroll WHERE uuid=? AND server=? AND state=? "
                    + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, id.toString());
                ps.setString(2, server);
                ps.setString(3, R_CONSUMED);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        token = rs.getString(1);
                    }
                }
            }
            if (token == null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE cport_reroll SET state=?, updated_at=? WHERE token=? AND state=?")) {
                ps.setString(1, R_REFUND);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, token);
                ps.setString(4, R_CONSUMED);
                return ps.executeUpdate() == 1 ? token : null;
            }
        });
    }

    private static final String REROLL_COLUMNS = "token, uuid, server, item, prompt, state, created_at, fail_reason, updated_at";

    private static Reroll reroll(ResultSet rs) throws SQLException {
        return new Reroll(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getBytes(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getLong(9));
    }

    public List<Reroll> refundsDue(UUID id, String server) throws SQLException {
        return db.with(c -> {
            List<Reroll> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REROLL_COLUMNS + " FROM cport_reroll "
                    + "WHERE uuid=? AND server=? AND state IN (?,?)")) {
                ps.setString(1, id.toString());
                ps.setString(2, server);
                ps.setString(3, R_REFUND);
                ps.setString(4, R_PREPARED);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(reroll(rs));
                    }
                }
            }
            return out;
        });
    }

    /** 최근 다시 그리기 기록 (모든 서버, 최신순). 관리자 조회용. */
    public List<Reroll> recentRerolls(UUID id, int limit) throws SQLException {
        return db.with(c -> {
            List<Reroll> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REROLL_COLUMNS + " FROM cport_reroll "
                    + "WHERE uuid=? ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(50, limit)))) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(reroll(rs));
                    }
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 예산 (서버 간 공유, 조건부 갱신)

    /** 예약. 총예산을 넘으면 false. */
    public boolean reserve(String id, String server, long amount, long cap) throws SQLException {
        return db.with(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int n;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cport_budget SET reserved=reserved+? WHERE id=1 AND spent+reserved+? <= ?")) {
                    ps.setLong(1, amount);
                    ps.setLong(2, amount);
                    ps.setLong(3, cap);
                    n = ps.executeUpdate();
                }
                if (n != 1) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cport_reservation (id, server, amount, created_at) VALUES (?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, server);
                    ps.setLong(3, amount);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** 정산. 같은 예약을 두 번 정산하지 않는다 (예약 행 삭제가 성공한 쪽만 반영). */
    public boolean settle(String id, long actual) throws SQLException {
        return db.with(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                long amount;
                try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM cport_reservation WHERE id=?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return false;
                        }
                        amount = rs.getLong(1);
                    }
                }
                int deleted;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cport_reservation WHERE id=?")) {
                    ps.setString(1, id);
                    deleted = ps.executeUpdate();
                }
                if (deleted != 1) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cport_budget SET reserved=CASE WHEN reserved>=? THEN reserved-? ELSE 0 END, spent=spent+? WHERE id=1")) {
                    ps.setLong(1, amount);
                    ps.setLong(2, amount);
                    ps.setLong(3, Math.max(0, actual));
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** 시작 시: 이 서버가 남긴 예약을 예약액 그대로 정산 (뒤늦은 응답 비용 누락 방지, 보수적). */
    public int settleStale(String server) throws SQLException {
        List<String[]> rows = db.with(c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, amount FROM cport_reservation WHERE server=?")) {
                ps.setString(1, server);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[]{rs.getString(1), Long.toString(rs.getLong(2))});
                    }
                }
            }
            return out;
        });
        int n = 0;
        for (String[] r : rows) {
            if (settle(r[0], Long.parseLong(r[1]))) {
                n++;
            }
        }
        return n;
    }

    /** 관리자 장부 보정 (/서버관리 초상화 예산조정): 사용액에 delta(micro-USD, 음수 가능)를 더한다. 0 아래로는 내려가지 않는다. */
    public BudgetState adjustSpent(long delta) throws SQLException {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cport_budget SET spent=CASE WHEN spent+?<0 THEN 0 ELSE spent+? END WHERE id=1")) {
                ps.setLong(1, delta);
                ps.setLong(2, delta);
                ps.executeUpdate();
            }
            return null;
        });
        return budget();
    }

    public BudgetState budget() throws SQLException {
        return db.with(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT spent, reserved FROM cport_budget WHERE id=1")) {
                return rs.next() ? new BudgetState(rs.getLong(1), rs.getLong(2)) : new BudgetState(0, 0);
            }
        });
    }

    public void logUsage(UUID id, String kind, String model, long textIn, long imageIn, long output, long cost, boolean ok) throws SQLException {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cport_usage (at, uuid, kind, model, text_in, image_in, output, "
                    + "cost_micro, ok) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, id == null ? null : id.toString());
                ps.setString(3, kind);
                ps.setString(4, model);
                ps.setLong(5, textIn);
                ps.setLong(6, imageIn);
                ps.setLong(7, output);
                ps.setLong(8, cost);
                ps.setInt(9, ok ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
