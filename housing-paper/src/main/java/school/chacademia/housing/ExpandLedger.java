package school.chacademia.housing;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 방 확장 결제 원장 (A03, 2026-10-21). 돈을 빼기 전에 거래를 먼저 기록하고, 단계를 하나씩 옮긴다.
 * <pre>
 * prepared ─(Vault 출금 거절)→ cancelled
 *          ─(출금 결과 모름: 예외)→ uncertain
 *          ─(출금 확인)→ withdrawn ─(한 트랜잭션: 방 크기 from→to)→ applied → done
 *                                  └(크기가 이미 바뀜)→ refund_pending ─(입금 확인)→ done
 *                                                                     └(입금 실패·모름)→ uncertain
 * </pre>
 * 서버가 시작될 때 이 서버(origin)의 prepared·withdrawn·refund_pending 은 돈이 움직였는지 알 수 없으므로 uncertain 으로 둔다
 * (자동 환불·재출금 없음). applied 는 done 으로 닫는다. uncertain 은 관리자가 /유저관리 &lt;닉네임&gt; 방 확장기록 으로 보고 처리한다.
 * SQL 은 HousingStore 작업 스레드에서만 부른다 (Connection 을 받음).
 */
final class ExpandLedger {
    static final String PREPARED = "prepared", WITHDRAWN = "withdrawn", APPLIED = "applied", REFUND_PENDING = "refund_pending",
            DONE = "done", UNCERTAIN = "uncertain", CANCELLED = "cancelled";
    record Tx(String id, UUID owner, int from, int to, long cost, String state, String origin, String note, long created, long updated) {}

    private final String table, room;
    ExpandLedger(String table, String room) { this.table = table; this.room = room; }

    void create(Connection c, String engine) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + table + "(id CHAR(36) NOT NULL PRIMARY KEY, owner CHAR(36) NOT NULL, from_size INT NOT NULL, to_size INT NOT NULL,"
                    + " cost BIGINT NOT NULL, state VARCHAR(16) NOT NULL, origin VARCHAR(32) NOT NULL, note VARCHAR(64) NOT NULL, created BIGINT NOT NULL, updated BIGINT NOT NULL)" + engine);
        }
        try (var s = c.createStatement()) { s.execute("CREATE INDEX IF NOT EXISTS ix_housing_expand_owner ON " + table + "(owner, created)"); }
        catch (SQLException ignored) { /* 성능용 색인: 못 만들어도 동작에는 영향 없음 */ }
    }

    void prepare(Connection c, String id, UUID owner, int from, int to, long cost, String origin) throws SQLException {
        long now = System.currentTimeMillis();
        try (var s = c.prepareStatement("INSERT INTO " + table + "(id,owner,from_size,to_size,cost,state,origin,note,created,updated) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, id); s.setString(2, owner.toString()); s.setInt(3, from); s.setInt(4, to); s.setLong(5, cost);
            s.setString(6, PREPARED); s.setString(7, origin); s.setString(8, "출금 전"); s.setLong(9, now); s.setLong(10, now);
            s.executeUpdate();
        }
    }

    /** 지금 단계가 from 일 때만 to 로 옮긴다. @return 옮겼으면 true (같은 요청을 두 번 해도 한 번만) */
    boolean move(Connection c, String id, String from, String to, String note) throws SQLException {
        try (var s = c.prepareStatement("UPDATE " + table + " SET state=?,note=?,updated=? WHERE id=? AND state=?")) {
            s.setString(1, to); s.setString(2, cut(note)); s.setLong(3, System.currentTimeMillis()); s.setString(4, id); s.setString(5, from);
            return s.executeUpdate() == 1;
        }
    }

    /**
     * 출금된 거래를 방에 반영한다. 호출하는 쪽이 트랜잭션으로 감싼다 (방 크기와 거래 단계가 함께 바뀌거나 함께 안 바뀜).
     * @return 새 단계 (applied / refund_pending). 이미 처리된 거래면 지금 단계를 그대로 돌려준다(아무것도 안 바꿈).
     */
    String apply(Connection c, String id, boolean lock) throws SQLException {
        Tx t = get(c, id, lock);
        if (t == null) throw new SQLException("expand tx missing " + id);
        if (!t.state().equals(WITHDRAWN) && !t.state().equals(PREPARED)) return t.state();
        int n;
        try (var s = c.prepareStatement("UPDATE " + room + " SET size=?,rev=rev+1 WHERE owner=? AND size=?")) {
            s.setInt(1, t.to()); s.setString(2, t.owner().toString()); s.setInt(3, t.from()); n = s.executeUpdate();
        }
        String next = n == 1 ? APPLIED : REFUND_PENDING;
        if (!move(c, id, t.state(), next, n == 1 ? "넓힘" : "방 크기가 이미 바뀜")) throw new SQLException("expand tx moved concurrently " + id);
        return next;
    }

    /** 시작 시 이 서버 거래 정리. @return {uncertain 으로 바꾼 수, done 으로 닫은 수} */
    int[] recover(Connection c, String origin) throws SQLException {
        int uncertain, closed; long now = System.currentTimeMillis();
        try (var s = c.prepareStatement("UPDATE " + table + " SET state=?,note=?,updated=? WHERE origin=? AND state IN (?,?,?)")) {
            s.setString(1, UNCERTAIN); s.setString(2, "재시작: 돈 이동 확인 필요"); s.setLong(3, now); s.setString(4, origin);
            s.setString(5, PREPARED); s.setString(6, WITHDRAWN); s.setString(7, REFUND_PENDING); uncertain = s.executeUpdate();
        }
        try (var s = c.prepareStatement("UPDATE " + table + " SET state=?,updated=? WHERE origin=? AND state=?")) {
            s.setString(1, DONE); s.setLong(2, now); s.setString(3, origin); s.setString(4, APPLIED); closed = s.executeUpdate();
        }
        return new int[]{uncertain, closed};
    }

    Tx get(Connection c, String id, boolean lock) throws SQLException {
        try (var s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, id);
            try (var r = s.executeQuery()) { return r.next() ? row(r) : null; }
        }
    }

    /** 최근 거래 (관리자 확인용). */
    List<Tx> list(Connection c, UUID owner, int limit) throws SQLException {
        var out = new ArrayList<Tx>();
        try (var s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table + " WHERE owner=? ORDER BY created DESC LIMIT ?")) {
            s.setString(1, owner.toString()); s.setInt(2, limit);
            try (var r = s.executeQuery()) { while (r.next()) out.add(row(r)); }
        }
        return out;
    }

    private static final String COLUMNS = "id,owner,from_size,to_size,cost,state,origin,note,created,updated";
    private static Tx row(ResultSet r) throws SQLException {
        return new Tx(r.getString(1), UUID.fromString(r.getString(2)), r.getInt(3), r.getInt(4), r.getLong(5), r.getString(6), r.getString(7), r.getString(8), r.getLong(9), r.getLong(10));
    }
    private static String cut(String s) { return s == null ? "" : s.length() > 64 ? s.substring(0, 64) : s; }

    /** 유저 안내 문구 (순수 함수, 테스트용). state = 거래의 마지막 단계(환불까지 끝났으면 "refunded"). 환불이 확인된 경우에만 "돌려드렸어요". */
    static String message(String state, int to, long cost) {
        return switch (state) {
            case APPLIED, DONE -> "방이 " + to + "×" + to + " 크기로 넓어졌어요!";
            case CANCELLED -> "돈이 부족해요. (필요: " + String.format("%,d", cost) + ")";
            case "refunded" -> "방 크기가 이미 바뀌어서 넓히지 않았어요. 돈은 돌려드렸어요.";
            case UNCERTAIN -> "넓히지 못했고 결제·환불 확인이 필요해요. 다시 시도하지 말고 관리자에게 알려 주세요.";
            default -> "넓히지 못했어요. 잠시 후 다시 시도해 주세요.";
        };
    }
}
