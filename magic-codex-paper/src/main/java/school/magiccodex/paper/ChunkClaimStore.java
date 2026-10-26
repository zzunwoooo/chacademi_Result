package school.magiccodex.paper;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import school.magiccodex.database.ConnectionHolder;
import school.magiccodex.database.DatabaseSettings;

/**
 * 야생 청크 점유 저장소 (메모 #88). 칭호 장부(TitleStore)와 같은 방식: database.properties(일반 도메인, GenericDatabaseFile)를 따라
 * MariaDB 면 공유 표, 아니면 이 서버의 chunk-claims.db (SQLite). 연결 하나를 저장소 전용 작업 스레드 하나가 쓴다 (서버 스레드에서 부르지 않는다).
 *
 * <pre>
 * codex_chunk_claim (server, world, cx, cz) PK · owner · claimed_at   — INDEX(owner)
 * codex_chunk_trust (owner, trusted) PK · added_at                      — 주인 기준 허용 명단 (주인의 모든 청크에 적용)
 * </pre>
 * 같은 청크를 두 사람이 동시에 점유하지 못하게 PK + INSERT IGNORE(OR IGNORE) 로 넣고, 넣은 줄 수로 성공을 판정한다.
 * 1인 최대 청크 수는 같은 거래 안에서 센다. 판매·해제는 주인까지 맞아야 지운다 (지운 줄 수로 판정).
 *
 * <p>검수 2026-10-26: 야생1(wild)·야생2(wild2)는 서로 다른 서버인데 세계 이름이 둘 다 "world" 라, 키에 server 가 없으면
 * 한쪽의 점유가 다른 쪽에도 읽히고(남의 땅으로 보호) 같은 좌표를 점유하지 못한다. 그래서 키·읽기·지우기 모두 server 로 가른다.
 * 예전 키(world, cx, cz)로 이미 만들어진 표는 열 때 한 번 키를 바꾼다.</p>
 */
final class ChunkClaimStore implements AutoCloseable {
    enum ClaimResult { CLAIMED, TAKEN, LIMIT }
    record Row(String world, int cx, int cz, UUID owner, long at, String server) {}

    private final ConnectionHolder holder;
    private final boolean maria;
    private final String ignore;

    ChunkClaimStore(DatabaseSettings settings, Path sqlite) throws Exception {
        maria = settings.mariaDb();
        ignore = maria ? "INSERT IGNORE" : "INSERT OR IGNORE";
        holder = settings.holder(sqlite);
        try (var s = db().createStatement()) {
            String tail = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
            s.execute("CREATE TABLE IF NOT EXISTS codex_chunk_claim(" + CLAIM_COLUMNS + ",PRIMARY KEY(server,world,cx,cz))" + tail);
            s.execute("CREATE TABLE IF NOT EXISTS codex_chunk_trust(owner VARCHAR(36) NOT NULL,trusted VARCHAR(36) NOT NULL,added_at BIGINT NOT NULL,PRIMARY KEY(owner,trusted))" + tail);
            migrateServerKey(s, tail);
            try { s.execute(maria ? "CREATE INDEX codex_chunk_claim_owner ON codex_chunk_claim(owner)" : "CREATE INDEX IF NOT EXISTS codex_chunk_claim_owner ON codex_chunk_claim(owner)"); }
            catch (SQLException exists) { /* MariaDB: 이미 있음 (Duplicate key name) */ }
        }
    }
    private static final String CLAIM_COLUMNS = "world VARCHAR(64) NOT NULL,cx INT NOT NULL,cz INT NOT NULL,owner VARCHAR(36) NOT NULL,claimed_at BIGINT NOT NULL,server VARCHAR(32) NOT NULL";

    /** 예전 키 (world, cx, cz) 로 만들어진 표면 기본 키에 server 를 넣는다 (한 번만). */
    private void migrateServerKey(Statement s, String tail) throws SQLException {
        if (maria) {
            boolean hasServer;
            try (var r = s.executeQuery("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='codex_chunk_claim' AND CONSTRAINT_NAME='PRIMARY' AND COLUMN_NAME='server'")) {
                hasServer = r.next() && r.getInt(1) > 0;
            }
            if (!hasServer) s.execute("ALTER TABLE codex_chunk_claim DROP PRIMARY KEY, ADD PRIMARY KEY(server,world,cx,cz)");
            return;
        }
        boolean hasServer = false;
        try (var r = s.executeQuery("PRAGMA table_info(codex_chunk_claim)")) {
            while (r.next()) if ("server".equals(r.getString("name")) && r.getInt("pk") > 0) hasServer = true;
        }
        if (hasServer) return;
        s.execute("ALTER TABLE codex_chunk_claim RENAME TO codex_chunk_claim_old");
        s.execute("CREATE TABLE codex_chunk_claim(" + CLAIM_COLUMNS + ",PRIMARY KEY(server,world,cx,cz))" + tail);
        s.execute("INSERT OR IGNORE INTO codex_chunk_claim(world,cx,cz,owner,claimed_at,server) SELECT world,cx,cz,owner,claimed_at,server FROM codex_chunk_claim_old");
        s.execute("DROP TABLE codex_chunk_claim_old");
    }
    private Connection db() throws SQLException { return holder.get(); }
    boolean shared() { return maria; }

    /** 시작할 때: 이 서버(server)에 있는 세계들의 점유 전부. 다른 서버의 같은 이름 세계는 섞이지 않는다. */
    List<Row> load(String server, Collection<String> worlds) throws SQLException {
        var out = new ArrayList<Row>();
        if (worlds.isEmpty()) return out;
        try (var s = db().prepareStatement("SELECT world,cx,cz,owner,claimed_at,server FROM codex_chunk_claim WHERE server=? AND world=?")) {
            for (String w : worlds) {
                s.setString(1, server); s.setString(2, w);
                try (var r = s.executeQuery()) { while (r.next()) { var row = row(r); if (row != null) out.add(row); } }
            }
        }
        return out;
    }
    /** 허용 명단 전부 (주인 → 허용된 사람). 작은 표라 통째로 읽는다. */
    Map<UUID, List<UUID>> trust() throws SQLException {
        var out = new HashMap<UUID, List<UUID>>();
        try (var s = db().prepareStatement("SELECT owner,trusted FROM codex_chunk_trust"); var r = s.executeQuery()) {
            while (r.next()) {
                UUID o = uuid(r.getString(1)), t = uuid(r.getString(2));
                if (o != null && t != null) out.computeIfAbsent(o, k -> new ArrayList<>()).add(t);
            }
        }
        return out;
    }
    /** 한 사람의 모든 점유 (모든 서버·세계, 관리 명령용). */
    List<Row> claimsOf(UUID owner) throws SQLException {
        var out = new ArrayList<Row>();
        try (var s = db().prepareStatement("SELECT world,cx,cz,owner,claimed_at,server FROM codex_chunk_claim WHERE owner=? ORDER BY claimed_at")) {
            s.setString(1, owner.toString());
            try (var r = s.executeQuery()) { while (r.next()) { var row = row(r); if (row != null) out.add(row); } }
        }
        return out;
    }

    /** 점유. max 0 = 제한 없음. 같은 청크를 먼저 넣은 쪽만 CLAIMED (PK). */
    ClaimResult claim(String world, int cx, int cz, UUID owner, int max, String server, long now) throws Exception {
        for (int attempt = 0; ; attempt++) {
            holder.begin();
            try {
                if (max > 0) {
                    try (var s = db().prepareStatement("SELECT COUNT(*) FROM codex_chunk_claim WHERE owner=?")) {
                        s.setString(1, owner.toString());
                        try (var r = s.executeQuery()) { if (r.next() && r.getInt(1) >= max) { db().rollback(); return ClaimResult.LIMIT; } }
                    }
                }
                int inserted;
                try (var s = db().prepareStatement(ignore + " INTO codex_chunk_claim(world,cx,cz,owner,claimed_at,server) VALUES(?,?,?,?,?,?)")) {
                    s.setString(1, world); s.setInt(2, cx); s.setInt(3, cz); s.setString(4, owner.toString()); s.setLong(5, now); s.setString(6, server);
                    inserted = s.executeUpdate();
                }
                db().commit();
                return inserted > 0 ? ClaimResult.CLAIMED : ClaimResult.TAKEN;
            } catch (Exception e) {
                holder.rollback();
                if (attempt < 3 && e instanceof SQLException s && (s.getErrorCode() == 1213 || s.getErrorCode() == 1205 || "40001".equals(s.getSQLState()))) { Thread.sleep(10L * (attempt + 1)); continue; }
                throw e;
            } finally { holder.end(); }
        }
    }
    /** 판매(주인 지정) 또는 관리자 해제(owner null). 이 서버(server)의 청크만. 지운 줄이 있으면 그 주인, 없으면 null. */
    UUID release(String server, String world, int cx, int cz, UUID owner) throws SQLException {
        UUID found = null;
        try (var s = db().prepareStatement("SELECT owner FROM codex_chunk_claim WHERE server=? AND world=? AND cx=? AND cz=?")) {
            s.setString(1, server); s.setString(2, world); s.setInt(3, cx); s.setInt(4, cz);
            try (var r = s.executeQuery()) { if (r.next()) found = uuid(r.getString(1)); }
        }
        if (found == null || owner != null && !owner.equals(found)) return null;
        try (var s = db().prepareStatement("DELETE FROM codex_chunk_claim WHERE server=? AND world=? AND cx=? AND cz=? AND owner=?")) {
            s.setString(1, server); s.setString(2, world); s.setInt(3, cx); s.setInt(4, cz); s.setString(5, found.toString());
            return s.executeUpdate() > 0 ? found : null;
        }
    }
    /** 허용 넣기·빼기. 실제로 바뀌었으면 true. */
    boolean trust(UUID owner, UUID player, boolean add, long now) throws SQLException {
        if (add) {
            try (var s = db().prepareStatement(ignore + " INTO codex_chunk_trust(owner,trusted,added_at) VALUES(?,?,?)")) {
                s.setString(1, owner.toString()); s.setString(2, player.toString()); s.setLong(3, now);
                return s.executeUpdate() > 0;
            }
        }
        try (var s = db().prepareStatement("DELETE FROM codex_chunk_trust WHERE owner=? AND trusted=?")) {
            s.setString(1, owner.toString()); s.setString(2, player.toString());
            return s.executeUpdate() > 0;
        }
    }

    private static Row row(ResultSet r) throws SQLException {
        UUID owner = uuid(r.getString(4));
        return owner == null ? null : new Row(r.getString(1), r.getInt(2), r.getInt(3), owner, r.getLong(5), r.getString(6));
    }
    private static UUID uuid(String s) { try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException bad) { return null; } }

    @Override public void close() throws SQLException { holder.close(); }
}
