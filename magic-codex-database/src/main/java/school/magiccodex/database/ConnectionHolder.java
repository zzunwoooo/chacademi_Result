package school.magiccodex.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One JDBC connection owned by a store's single worker thread, reopened when the server dropped it
 * (MariaDB wait_timeout, restart, network blip). SQLite connections are local files and are only
 * reopened if something closed them. Session setup (PRAGMAs) is re-applied on every reopen.
 * Between {@link #begin()} and {@link #end()} the same connection is always returned, so a
 * transaction can fail but never silently continue on a fresh auto-commit connection.
 */
public final class ConnectionHolder implements AutoCloseable {
    public interface Opener { Connection open() throws Exception; }
    public interface Setup { void apply(Connection db) throws SQLException; }

    static final long CHECK_AFTER_NANOS = 5_000_000_000L;
    private final Opener opener;
    private final Setup setup;
    private final boolean remote;
    private final long checkAfterNanos;
    private Connection current;
    private long lastUse;
    private boolean closed;
    private boolean pinned;

    public ConnectionHolder(Opener opener, Setup setup, boolean remote) throws SQLException {
        this(opener, setup, remote, CHECK_AFTER_NANOS);
    }

    /** {@code checkAfterNanos}: idle time after which a remote connection is validated before reuse. */
    public ConnectionHolder(Opener opener, Setup setup, boolean remote, long checkAfterNanos) throws SQLException {
        this.checkAfterNanos = Math.max(0, checkAfterNanos);
        this.opener = opener;
        this.setup = setup;
        this.remote = remote;
        current = open();
        lastUse = System.nanoTime();
    }

    /** Returns a usable connection, reopening it when it is closed or (remote only) fails validation. */
    public synchronized Connection get() throws SQLException {
        if (closed) throw new SQLException("Connection holder closed");
        if (pinned) return current;
        long now = System.nanoTime();
        long idle = now - lastUse;
        lastUse = now;
        if (healthy(idle)) return current;
        try { current.close(); } catch (SQLException ignored) { }
        current = open();
        return current;
    }

    private boolean healthy(long idle) {
        try {
            if (current.isClosed()) return false;
            if (!remote) return true;
            return idle < checkAfterNanos || current.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Validates (or reopens) the connection, disables auto-commit and pins it until {@link #end()}. */
    public synchronized Connection begin() throws SQLException {
        if (pinned) throw new SQLException("Transaction already open");
        Connection db = get();
        db.setAutoCommit(false);
        pinned = true;
        return db;
    }

    /** Rolls back the pinned transaction; a connection that is already gone needs no rollback. */
    public synchronized void rollback() throws SQLException {
        try { current.rollback(); }
        catch (SQLException e) {
            boolean broken;
            try { broken = current.isClosed() || !current.isValid(1); } catch (SQLException ignored) { broken = true; }
            if (!broken) throw e;
        }
    }

    /** Restores auto-commit; a broken connection is closed here and reopened by the next {@link #get()}. */
    public synchronized void end() {
        pinned = false;
        try { current.setAutoCommit(true); }
        catch (SQLException e) { try { current.close(); } catch (SQLException ignored) { } }
    }

    public synchronized boolean inTransaction() { return pinned; }

    private Connection open() throws SQLException {
        Connection db;
        try { db = opener.open(); }
        catch (SQLException e) { throw e; }
        catch (Exception e) { throw new SQLException("Database connection failed", e); }
        try { setup.apply(db); }
        catch (SQLException | RuntimeException e) {
            try { db.close(); } catch (SQLException ignored) { }
            throw e;
        }
        return db;
    }

    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        current.close();
    }
}
