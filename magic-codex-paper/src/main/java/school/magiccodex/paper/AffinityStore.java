package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * NPC 호감도 저장소. MagicCodexBridge가 소유한다.
 * MariaDB: codex_affinity / codex_affinity_daily / codex_affinity_gift (school·wild 공유).
 * SQLite fallback: 반드시 Bridge 데이터 폴더의 {@code affinity.db} (호출자가 경로를 명시해서 넘긴다).
 *
 * <p>하루 한도와 점수 갱신은 트랜잭션 + 조건부 UPDATE로 처리해 서버 간 동시 갱신에도 한도를 넘지 않는다.
 * 하나의 IO 스레드만 이 객체를 사용한다.
 */
final class AffinityStore implements AutoCloseable {

    /**
     * 점수 상한 (오너 결정 2026-10-23): 기본 0·최대 100, 30·60·90 에서 그 NPC 퀘스트를 깨야 다음 구간이 열린다.
     * heart = 깬 호감도 퀘스트 단계 (고정 대화 동작 "custom heart <npc> <단계>"): 0 → 30, 1 → 60, 2 → 90, 3 이상 → 100.
     * ChacaNPC AffinityStage·선물 상한 판단도 이 값과 같아야 한다.
     */
    static int gate(int heart) {
        int h = Math.max(0, Math.min(3, heart));
        return h >= 3 ? 100 : 30 * (h + 1);
    }

    record Row(int score, int heart, String nickname) {
        static final Row EMPTY = new Row(0, 0, "");
    }

    record AddResult(Row row, int applied) {
    }

    /** OK = 예약됨 · CAP = 오늘 선물 횟수 다 씀 · FULL = 호감도 상한(퀘스트 미완료)이라 받지 않음 (횟수 차감 없음) · DUPLICATE = 같은 토큰 */
    enum GiftBegin { OK, CAP, DUPLICATE, FULL }

    private final ConnectionHolder holder;
    private final boolean maria;
    private final String affinity;
    private final String daily;
    private final String gift;

    AffinityStore(DatabaseSettings settings, Path sqliteFile) throws Exception {
        maria = settings.mariaDb();
        affinity = settings.table("affinity", "npc_affinity");
        daily = settings.table("affinity_daily", "npc_affinity_daily");
        gift = settings.table("affinity_gift", "npc_affinity_gift");
        holder = settings.holder(sqliteFile);
        String engine = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        String uuid = maria ? "CHAR(36)" : "TEXT";
        try (var s = holder.get().createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + affinity + " (owner " + uuid + " NOT NULL, npc VARCHAR(48) NOT NULL,"
                    + " score INT NOT NULL DEFAULT 0, heart INT NOT NULL DEFAULT 0, nickname VARCHAR(32) NOT NULL DEFAULT '',"
                    + " updated BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (owner, npc))" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS " + daily + " (owner " + uuid + " NOT NULL, npc VARCHAR(48) NOT NULL,"
                    + " day CHAR(10) NOT NULL, source VARCHAR(16) NOT NULL, amount INT NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY (owner, npc, day, source))" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS " + gift + " (token CHAR(36) NOT NULL PRIMARY KEY, owner " + uuid + " NOT NULL,"
                    + " npc VARCHAR(48) NOT NULL, day CHAR(10) NOT NULL, delta INT NOT NULL, status VARCHAR(12) NOT NULL,"
                    + " created BIGINT NOT NULL)" + engine);
        }
        // A08 (2026-10-21): 선물을 시작한 서버(origin). 추가 전용. 옛 행은 '' (자동 처리하지 않고 관리자 확인).
        try (var s = holder.get().createStatement(); var r = s.executeQuery("SELECT origin FROM " + gift + " WHERE 1=0")) {
            // 이미 있음
        } catch (SQLException missing) {
            try (var s = holder.get().createStatement()) { s.execute("ALTER TABLE " + gift + " ADD COLUMN origin VARCHAR(36) NOT NULL DEFAULT ''"); }
        }
    }

    private String lock() {
        return maria ? " FOR UPDATE" : "";
    }

    // ------------------------------------------------------------------ 읽기

    Row load(UUID owner, String npc) throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement(
                "SELECT score, heart, nickname FROM " + affinity + " WHERE owner=? AND npc=?")) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new Row(r.getInt(1), r.getInt(2), r.getString(3)) : Row.EMPTY;
            }
        }
    }

    Map<String, Row> loadAll(UUID owner) throws SQLException {
        Map<String, Row> out = new HashMap<>();
        try (PreparedStatement s = holder.get().prepareStatement(
                "SELECT npc, score, heart, nickname FROM " + affinity + " WHERE owner=?")) {
            s.setString(1, owner.toString());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.put(r.getString(1), new Row(r.getInt(2), r.getInt(3), r.getString(4)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 쓰기 (트랜잭션)

    private interface Tx<T> {
        T run(Connection db) throws SQLException;
    }

    /** 트랜잭션. MariaDB 교착(1213)·직렬화 실패(40001)는 최대 3번 다시 시도한다. */
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
                if (!retry) {
                    throw e;
                }
            } catch (RuntimeException e) {
                holder.rollback();
                throw e;
            } finally {
                holder.end();
            }
        }
    }

    private void ensureRow(Connection db, UUID owner, String npc) throws SQLException {
        // MariaDB: INSERT IGNORE는 공유 잠금 후 FOR UPDATE로 올라가며 교착이 날 수 있어 ON DUPLICATE KEY 사용
        String sql = maria
                ? "INSERT INTO " + affinity + " (owner, npc, score, heart, nickname, updated) VALUES (?,?,0,0,'',0)"
                + " ON DUPLICATE KEY UPDATE score=score"
                : "INSERT OR IGNORE INTO " + affinity + " (owner, npc, score, heart, nickname, updated) VALUES (?,?,0,0,'',0)";
        try (PreparedStatement s = db.prepareStatement(sql)) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            s.executeUpdate();
        }
    }

    /** 하루 한도 칸을 잠그고, 이번에 쓸 수 있는 양만큼 조건부로 늘린다. 반환 = 실제로 늘린 양. */
    private int consumeDaily(Connection db, UUID owner, String npc, String day, String source, int want, int cap)
            throws SQLException {
        if (want <= 0 || cap <= 0) {
            return 0;
        }
        String ensure = maria
                ? "INSERT INTO " + daily + " (owner, npc, day, source, amount) VALUES (?,?,?,?,0) ON DUPLICATE KEY UPDATE amount=amount"
                : "INSERT OR IGNORE INTO " + daily + " (owner, npc, day, source, amount) VALUES (?,?,?,?,0)";
        try (PreparedStatement s = db.prepareStatement(ensure)) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            s.setString(3, day);
            s.setString(4, source);
            s.executeUpdate();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            int used;
            try (PreparedStatement s = db.prepareStatement("SELECT amount FROM " + daily
                    + " WHERE owner=? AND npc=? AND day=? AND source=?" + lock())) {
                s.setString(1, owner.toString());
                s.setString(2, npc);
                s.setString(3, day);
                s.setString(4, source);
                try (ResultSet r = s.executeQuery()) {
                    used = r.next() ? r.getInt(1) : 0;
                }
            }
            int allowed = Math.max(0, Math.min(want, cap - used));
            if (allowed == 0) {
                return 0;
            }
            try (PreparedStatement s = db.prepareStatement("UPDATE " + daily + " SET amount=amount+?"
                    + " WHERE owner=? AND npc=? AND day=? AND source=? AND amount=?")) {
                s.setInt(1, allowed);
                s.setString(2, owner.toString());
                s.setString(3, npc);
                s.setString(4, day);
                s.setString(5, source);
                s.setInt(6, used);
                if (s.executeUpdate() == 1) {
                    return allowed;
                }
            }
        }
        throw new SQLException("affinity daily limit contention");
    }

    // ------------------------------------------------------------------ 일일 제한 관리 (오너 2026-10-26, 메모 #90)

    record DailyRow(String npc, String source, int amount) {}

    /** 이 사람의 그날 일일 기록 전부 (선물·AI 대화 보상·마음 읽기 등). */
    java.util.List<DailyRow> dailyRows(UUID owner, String day) throws SQLException {
        var out = new java.util.ArrayList<DailyRow>();
        try (PreparedStatement s = holder.get().prepareStatement("SELECT npc, source, amount FROM " + daily + " WHERE owner=? AND day=? ORDER BY source, npc")) {
            s.setString(1, owner.toString());
            s.setString(2, day);
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new DailyRow(r.getString(1), r.getString(2), r.getInt(3))); }
        }
        return out;
    }

    /** 그날 기록 지우기 (source 가 null 이면 전부). 반환 = 지운 줄 수. 진행 중인 선물(gift 표)은 건드리지 않는다. */
    int resetDaily(UUID owner, String day, String source) throws SQLException {
        String sql = "DELETE FROM " + daily + " WHERE owner=? AND day=?" + (source == null ? "" : " AND source=?");
        try (PreparedStatement s = holder.get().prepareStatement(sql)) {
            s.setString(1, owner.toString());
            s.setString(2, day);
            if (source != null) s.setString(3, source);
            return s.executeUpdate();
        }
    }

    /** 하루 1번짜리 기록 (예: 마음 읽기). 오늘 처음이면 true (기록함), 이미 있으면 false. 서버 간 동시에도 1번만 (기본 키). */
    boolean markOnce(UUID owner, String npc, String day, String source) throws SQLException {
        String sql = maria
                ? "INSERT IGNORE INTO " + daily + " (owner, npc, day, source, amount) VALUES (?,?,?,?,1)"
                : "INSERT OR IGNORE INTO " + daily + " (owner, npc, day, source, amount) VALUES (?,?,?,?,1)";
        try (PreparedStatement s = holder.get().prepareStatement(sql)) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            s.setString(3, day);
            s.setString(4, source);
            return s.executeUpdate() == 1;
        }
    }

    private void releaseDaily(Connection db, UUID owner, String npc, String day, String source, int amount) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("UPDATE " + daily + " SET amount=amount-?"
                + " WHERE owner=? AND npc=? AND day=? AND source=? AND amount>=?")) {
            s.setInt(1, amount);
            s.setString(2, owner.toString());
            s.setString(3, npc);
            s.setString(4, day);
            s.setString(5, source);
            s.setInt(6, amount);
            s.executeUpdate();
        }
    }

    /** 점수를 조건부로 바꾼다 (하트 단계 상한 적용). 반환 = 새 Row와 실제 변화량. */
    private AddResult applyScore(Connection db, UUID owner, String npc, int delta, long now) throws SQLException {
        for (int attempt = 0; attempt < 3; attempt++) {
            Row cur;
            try (PreparedStatement s = db.prepareStatement("SELECT score, heart, nickname FROM " + affinity
                    + " WHERE owner=? AND npc=?" + lock())) {
                s.setString(1, owner.toString());
                s.setString(2, npc);
                try (ResultSet r = s.executeQuery()) {
                    cur = r.next() ? new Row(r.getInt(1), r.getInt(2), r.getString(3)) : Row.EMPTY;
                }
            }
            int next = Math.max(0, Math.min(gate(cur.heart()), cur.score() + delta));
            if (next == cur.score()) {
                return new AddResult(cur, 0);
            }
            try (PreparedStatement s = db.prepareStatement("UPDATE " + affinity + " SET score=?, updated=?"
                    + " WHERE owner=? AND npc=? AND score=?")) {
                s.setInt(1, next);
                s.setLong(2, now);
                s.setString(3, owner.toString());
                s.setString(4, npc);
                s.setInt(5, cur.score());
                if (s.executeUpdate() == 1) {
                    return new AddResult(new Row(next, cur.heart(), cur.nickname()), next - cur.score());
                }
            }
        }
        throw new SQLException("affinity score contention");
    }

    /**
     * 호감도 변화. 양수이고 cap > 0 이면 출처별 하루 한도(cap) 안에서만 오른다. 음수는 한도 없음.
     */
    AddResult add(UUID owner, String npc, String source, int amount, int cap, String day, long now) throws SQLException {
        return tx(db -> {
            ensureRow(db, owner, npc);
            int delta = amount;
            if (amount > 0) {
                // 하트 단계 상한까지 남은 만큼만 (상한에 막혀 오르지 않으면 하루 한도도 쓰지 않음)
                Row cur = lockRow(db, owner, npc);
                delta = Math.min(amount, gate(cur.heart()) - cur.score());
                if (delta <= 0) {
                    return new AddResult(cur, 0);
                }
                if (cap > 0) {
                    delta = consumeDaily(db, owner, npc, day, source, delta, cap);
                    if (delta == 0) {
                        return new AddResult(cur, 0);
                    }
                }
            }
            return applyScore(db, owner, npc, delta, now);
        });
    }

    /**
     * 횟수로 세는 호감도 상승 (#83 호의적 AI 대화: NPC 하나당 하루 count 번, 한 번에 amount).
     * 하루 칸(daily 표, source 행)의 amount 열에 "쓴 횟수"를 적는다. 상한(30·60·90, 퀘스트 미완료)에 닿아 있으면
     * 오르지 않고 횟수도 쓰지 않는다 (선물과 같은 규칙). 상한까지 남은 양이 amount 보다 적으면 남은 만큼만 오른다.
     */
    AddResult addCounted(UUID owner, String npc, String source, int amount, int count, String day, long now) throws SQLException {
        return tx(db -> {
            ensureRow(db, owner, npc);
            Row cur = lockRow(db, owner, npc);
            int delta = Math.min(amount, gate(cur.heart()) - cur.score());
            if (amount <= 0 || count <= 0 || delta <= 0) {
                return new AddResult(cur, 0);
            }
            if (consumeDaily(db, owner, npc, day, source, 1, count) == 0) {
                return new AddResult(cur, 0);
            }
            return applyScore(db, owner, npc, delta, now);
        });
    }

    /** 오늘 이 출처로 쓴 양 (횟수형 출처면 횟수). 관리·검수용. */
    int usedToday(UUID owner, String npc, String source, String day) throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement("SELECT amount FROM " + daily
                + " WHERE owner=? AND npc=? AND day=? AND source=?")) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            s.setString(3, day);
            s.setString(4, source);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    /**
     * 관리자 점수 변경. 행 잠금 안에서 최신 점수로 계산하며 하루 한도는 소비하거나 초기화하지 않는다.
     * 하트 단계·호칭은 그대로 두고 점수만 현재 하트 상한 안으로 제한한다.
     */
    Row adminChange(UUID owner, String npc, String action, int value, long now) throws SQLException {
        if (value < 0) {
            throw new IllegalArgumentException("호감도 값은 0 이상이어야 합니다.");
        }
        if (!"set".equals(action) && !"add".equals(action) && !"remove".equals(action)) {
            throw new IllegalArgumentException("지원하지 않는 호감도 변경입니다.");
        }
        return tx(db -> {
            ensureRow(db, owner, npc);
            Row current = lockRow(db, owner, npc);
            long requested = switch (action) {
                case "set" -> value;
                case "add" -> (long) current.score() + value;
                default -> (long) current.score() - value;
            };
            int score = (int) Math.max(0L, Math.min((long) gate(current.heart()), requested));
            if (score != current.score()) {
                try (PreparedStatement s = db.prepareStatement("UPDATE " + affinity
                        + " SET score=?, updated=? WHERE owner=? AND npc=?")) {
                    s.setInt(1, score);
                    s.setLong(2, now);
                    s.setString(3, owner.toString());
                    s.setString(4, npc);
                    if (s.executeUpdate() != 1) {
                        throw new SQLException("affinity admin row missing");
                    }
                }
            }
            return new Row(score, current.heart(), current.nickname());
        });
    }

    /** 점수 행을 먼저 잠근다 (affinity → daily 순서로 잠가 교착을 줄임). */
    private Row lockRow(Connection db, UUID owner, String npc) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT score, heart, nickname FROM " + affinity
                + " WHERE owner=? AND npc=?" + lock())) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new Row(r.getInt(1), r.getInt(2), r.getString(3)) : Row.EMPTY;
            }
        }
    }

    private Row loadIn(Connection db, UUID owner, String npc) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT score, heart, nickname FROM " + affinity
                + " WHERE owner=? AND npc=?")) {
            s.setString(1, owner.toString());
            s.setString(2, npc);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new Row(r.getInt(1), r.getInt(2), r.getString(3)) : Row.EMPTY;
            }
        }
    }

    /** 하트 이벤트 단계 기록 (내려가지 않음, 0~5). */
    Row setHeart(UUID owner, String npc, int level, long now) throws SQLException {
        int lv = Math.max(0, Math.min(5, level));
        return tx(db -> {
            ensureRow(db, owner, npc);
            try (PreparedStatement s = db.prepareStatement("UPDATE " + affinity + " SET heart=?, updated=?"
                    + " WHERE owner=? AND npc=? AND heart<?")) {
                s.setInt(1, lv);
                s.setLong(2, now);
                s.setString(3, owner.toString());
                s.setString(4, npc);
                s.setInt(5, lv);
                s.executeUpdate();
            }
            return loadIn(db, owner, npc);
        });
    }

    /** 관리자 하트 단계 설정 (올리기·내리기 모두). 내리면 점수도 새 상한 안으로 줄인다. */
    Row adminHeart(UUID owner, String npc, int level, long now) throws SQLException {
        if (level < 0 || level > 5) throw new IllegalArgumentException("하트 단계는 0~5입니다.");
        return tx(db -> {
            ensureRow(db, owner, npc);
            Row current = lockRow(db, owner, npc);
            int score = Math.min(current.score(), gate(level));
            try (PreparedStatement s = db.prepareStatement("UPDATE " + affinity + " SET heart=?, score=?, updated=? WHERE owner=? AND npc=?")) {
                s.setInt(1, level);
                s.setInt(2, score);
                s.setLong(3, now);
                s.setString(4, owner.toString());
                s.setString(5, npc);
                if (s.executeUpdate() != 1) throw new SQLException("affinity admin row missing");
            }
            return new Row(score, level, current.nickname());
        });
    }

    Row setNickname(UUID owner, String npc, String nickname, long now) throws SQLException {
        return tx(db -> {
            ensureRow(db, owner, npc);
            try (PreparedStatement s = db.prepareStatement("UPDATE " + affinity + " SET nickname=?, updated=?"
                    + " WHERE owner=? AND npc=?")) {
                s.setString(1, nickname == null ? "" : nickname);
                s.setLong(2, now);
                s.setString(3, owner.toString());
                s.setString(4, npc);
                s.executeUpdate();
            }
            return loadIn(db, owner, npc);
        });
    }

    // ------------------------------------------------------------------ 선물 (2단계)

    /**
     * 1단계: 하루 선물 횟수를 예약하고 pending 기록을 만든다. 아이템은 아직 차감하지 않는다.
     * 같은 토큰은 한 번만 처리된다.
     */
    GiftBegin beginGift(String token, UUID owner, String npc, int delta, String day, int dailyCount, long now, String origin)
            throws SQLException {
        return tx(db -> {
            try (PreparedStatement s = db.prepareStatement("SELECT status FROM " + gift + " WHERE token=?")) {
                s.setString(1, token);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        return GiftBegin.DUPLICATE;
                    }
                }
            }
            ensureRow(db, owner, npc);
            Row cur = lockRow(db, owner, npc);
            // 상한에 닿아 있으면(그 NPC 퀘스트를 아직 안 깸) 받지 않는다 — 하루 횟수도 쓰지 않는다
            if (cur.score() >= gate(cur.heart())) {
                return GiftBegin.FULL;
            }
            if (consumeDaily(db, owner, npc, day, "gift", 1, dailyCount) == 0) {
                return GiftBegin.CAP;
            }
            try (PreparedStatement s = db.prepareStatement("INSERT INTO " + gift
                    + " (token, owner, npc, day, delta, status, created, origin) VALUES (?,?,?,?,?,'pending',?,?)")) {
                s.setString(1, token);
                s.setString(2, owner.toString());
                s.setString(3, npc);
                s.setString(4, day);
                s.setInt(5, delta);
                s.setLong(6, now);
                s.setString(7, origin == null ? "" : origin);
                s.executeUpdate();
            }
            return GiftBegin.OK;
        });
    }

    /** 2단계: 아이템 차감 후 확정. pending이 아니면(이미 처리됨) null. */
    AddResult commitGift(String token, long now) throws SQLException {
        return tx(db -> commitIn(db, token, now));
    }

    private AddResult commitIn(Connection db, String token, long now) throws SQLException {
        String owner;
        String npc;
        int delta;
        try (PreparedStatement s = db.prepareStatement("SELECT owner, npc, delta FROM " + gift
                + " WHERE token=? AND status='pending'" + lock())) {
            s.setString(1, token);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                owner = r.getString(1);
                npc = r.getString(2);
                delta = r.getInt(3);
            }
        }
        try (PreparedStatement s = db.prepareStatement("UPDATE " + gift + " SET status='done' WHERE token=? AND status='pending'")) {
            s.setString(1, token);
            if (s.executeUpdate() != 1) {
                return null;
            }
        }
        return applyScore(db, UUID.fromString(owner), npc, delta, now);
    }

    /** 아이템 차감 전에 취소 (손에 아이템이 없어짐 등). 하루 횟수도 돌려준다. */
    boolean cancelGift(String token) throws SQLException {
        return tx(db -> {
            String owner;
            String npc;
            String day;
            try (PreparedStatement s = db.prepareStatement("SELECT owner, npc, day FROM " + gift
                    + " WHERE token=? AND status='pending'" + lock())) {
                s.setString(1, token);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        return false;
                    }
                    owner = r.getString(1);
                    npc = r.getString(2);
                    day = r.getString(3);
                }
            }
            try (PreparedStatement s = db.prepareStatement("UPDATE " + gift + " SET status='cancelled' WHERE token=? AND status='pending'")) {
                s.setString(1, token);
                if (s.executeUpdate() != 1) {
                    return false;
                }
            }
            releaseDaily(db, UUID.fromString(owner), npc, day, "gift", 1);
            return true;
        });
    }

    // ------------------------------------------------------------------ 선물 복구 (A08, 2026-10-21)

    /** 끝나지 않은 선물 한 건. origin = 선물을 시작한 서버 ('' = 옛 기록). */
    record PendingGift(String token, UUID owner, String npc, int delta, String origin, long created) {
    }

    enum Recovery { COMMIT, CANCEL, WAIT, SKIP, REVIEW }

    /**
     * 접속한 서버에서 pending 선물 하나를 어떻게 할지 (우편 수령과 같은 "같은 스냅샷 영수증" 규칙).
     * 아이템 차감과 영수증(PDC)은 같은 틱에 같은 플레이어 데이터에 저장되므로:
     * 영수증이 없으면 차감 전에 멈춘 것 → 취소(하루 횟수 돌려줌) / 영수증이 디스크에도 있으면 차감 확정 → 확정 /
     * 메모리에만 있으면 저장을 기다림 / 다른 서버 기록은 그 서버가 처리 / origin 이 없는 옛 기록은 관리자 확인.
     */
    static Recovery recovery(String origin, String here, boolean pdcReceipt, boolean diskReceipt) {
        if (origin == null || origin.isEmpty()) return Recovery.REVIEW;
        if (!origin.equals(here)) return Recovery.SKIP;
        if (!pdcReceipt) return Recovery.CANCEL;
        return diskReceipt ? Recovery.COMMIT : Recovery.WAIT;
    }

    /** 이 플레이어의 pending 선물 (createdBefore 보다 먼저 시작된 것만: 지금 진행 중인 선물은 건드리지 않는다). */
    java.util.List<PendingGift> pendingGifts(UUID owner, long createdBefore) throws SQLException {
        var out = new java.util.ArrayList<PendingGift>();
        try (PreparedStatement s = holder.get().prepareStatement("SELECT token, owner, npc, delta, origin, created FROM " + gift
                + " WHERE owner=? AND status='pending' AND created<? ORDER BY created")) {
            s.setString(1, owner.toString());
            s.setLong(2, createdBefore);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new PendingGift(r.getString(1), UUID.fromString(r.getString(2)), r.getString(3), r.getInt(4), r.getString(5), r.getLong(6)));
                }
            }
        }
        return out;
    }

    /** origin 이 없는 옛 pending 수 (시작 시 관리자 안내용). 자동으로 확정하지 않는다. */
    int legacyPendingGifts() throws SQLException {
        try (PreparedStatement s = holder.get().prepareStatement("SELECT COUNT(*) FROM " + gift + " WHERE status='pending' AND origin=''");
             ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        holder.close();
    }
}
