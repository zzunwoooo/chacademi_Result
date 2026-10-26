package school.magiccodex.paper;

import java.sql.*;
import java.util.UUID;

/**
 * 아공간 배낭 tables (2026-10-24).
 * <ul>
 *   <li>{@code codex_bag_unlock}: pages opened with 가방 확장권 — one row per player, shared by every server.</li>
 *   <li>{@code codex_bag_pages}: stored pages per player and server-id. The page in the vanilla inventory (active) is
 *   not stored here; the other pages are. Rows only move forward (seq), so a replayed journal never undoes a newer
 *   save.</li>
 * </ul>
 */
final class BagPagesSql {
    record Saved(int active, long seq, byte[][] pages) {}

    private final Connection c;
    private final boolean maria;

    BagPagesSql(Connection connection, boolean maria) { this.c = connection; this.maria = maria; }

    void init() throws SQLException {
        String engine = maria ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci" : "";
        String blob = maria ? "MEDIUMBLOB" : "BLOB";
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS codex_bag_unlock(uuid VARCHAR(36) PRIMARY KEY,pages INT NOT NULL)" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS codex_bag_pages(uuid VARCHAR(36) NOT NULL,server_id VARCHAR(48) NOT NULL,active INT NOT NULL,seq BIGINT NOT NULL,"
                    + "page1 " + blob + ",page2 " + blob + ",page3 " + blob + ",PRIMARY KEY(uuid,server_id))" + engine);
        }
    }

    interface Work<T> { T run() throws SQLException; }

    private <T> T tx(Work<T> work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try { T out = work.run(); c.commit(); return out; }
        catch (SQLException | RuntimeException e) { try { c.rollback(); } catch (SQLException s) { e.addSuppressed(s); } throw e; }
        finally { c.setAutoCommit(auto); }
    }

    int unlocked(UUID id) throws SQLException {
        try (var p = c.prepareStatement("SELECT pages FROM codex_bag_unlock WHERE uuid=?")) {
            p.setString(1, id.toString());
            try (var r = p.executeQuery()) { return r.next() ? Math.max(1, r.getInt(1)) : 1; }
        }
    }

    /** One more page (never above max). Returns the new count, or -1 when already at max. */
    int unlockOne(UUID id, int max) throws SQLException {
        return tx(() -> {
            ensureUnlockRow(id);
            try (var p = c.prepareStatement("UPDATE codex_bag_unlock SET pages=pages+1 WHERE uuid=? AND pages<?")) {
                p.setString(1, id.toString()); p.setInt(2, max);
                if (p.executeUpdate() != 1) return -1;
            }
            return unlocked(id);
        });
    }

    void setUnlocked(UUID id, int pages) throws SQLException {
        tx(() -> {
            ensureUnlockRow(id);
            try (var p = c.prepareStatement("UPDATE codex_bag_unlock SET pages=? WHERE uuid=?")) { p.setInt(1, pages); p.setString(2, id.toString()); p.executeUpdate(); }
            return null;
        });
    }

    private void ensureUnlockRow(UUID id) throws SQLException {
        try (var p = c.prepareStatement(maria ? "INSERT IGNORE INTO codex_bag_unlock(uuid,pages) VALUES(?,1)" : "INSERT OR IGNORE INTO codex_bag_unlock(uuid,pages) VALUES(?,1)")) {
            p.setString(1, id.toString()); p.executeUpdate();
        }
        try (var p = c.prepareStatement("SELECT pages FROM codex_bag_unlock WHERE uuid=?" + (maria ? " FOR UPDATE" : ""))) {
            p.setString(1, id.toString()); try (var r = p.executeQuery()) { if (!r.next()) throw new SQLException("Missing bag unlock row"); }
        }
    }

    Saved load(UUID id, String server) throws SQLException {
        try (var p = c.prepareStatement("SELECT active,seq,page1,page2,page3 FROM codex_bag_pages WHERE uuid=? AND server_id=?")) {
            p.setString(1, id.toString()); p.setString(2, server);
            try (var r = p.executeQuery()) {
                if (!r.next()) return null;
                return new Saved(r.getInt(1), r.getLong(2), new byte[][]{bytes(r.getBytes(3)), bytes(r.getBytes(4)), bytes(r.getBytes(5))});
            }
        }
    }

    private static byte[] bytes(byte[] b) { return b == null ? new byte[0] : b; }

    /** Stores the state unless the database already has the same or a newer seq. True when stored or already newer. */
    boolean save(UUID id, String server, Saved s) throws SQLException {
        return tx(() -> {
            Long current = null;
            try (var p = c.prepareStatement("SELECT seq FROM codex_bag_pages WHERE uuid=? AND server_id=?" + (maria ? " FOR UPDATE" : ""))) {
                p.setString(1, id.toString()); p.setString(2, server);
                try (var r = p.executeQuery()) { if (r.next()) current = r.getLong(1); }
            }
            if (current != null && current >= s.seq()) return true;
            String sql = current == null
                    ? "INSERT INTO codex_bag_pages(active,seq,page1,page2,page3,uuid,server_id) VALUES(?,?,?,?,?,?,?)"
                    : "UPDATE codex_bag_pages SET active=?,seq=?,page1=?,page2=?,page3=? WHERE uuid=? AND server_id=?";
            try (var p = c.prepareStatement(sql)) {
                p.setInt(1, s.active()); p.setLong(2, s.seq());
                for (int i = 0; i < 3; i++) p.setBytes(3 + i, s.pages()[i]);
                p.setString(6, id.toString()); p.setString(7, server);
                if (p.executeUpdate() != 1) throw new SQLException("Bag page row not written");
            }
            return true;
        });
    }
}
