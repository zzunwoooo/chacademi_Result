package school.magiccodex.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Storage choice shared by our Paper plugins. No database traffic occurs on the game thread. */
public record DatabaseSettings(boolean mariaDb, String url, String user, String password) {
    public static DatabaseSettings load(Path file) throws IOException {
        if (!Files.exists(file)) return new DatabaseSettings(false, "", "", "");
        var p = new Properties();
        try (var input = Files.newInputStream(file)) { p.load(input); }
        String mode = p.getProperty("mode", "sqlite").trim();
        if (mode.equalsIgnoreCase("sqlite")) return new DatabaseSettings(false, "", "", "");
        if (!mode.equalsIgnoreCase("mariadb")) throw new IOException("Unknown database mode: " + mode);
        String host = p.getProperty("host", "127.0.0.1").trim();
        String database = p.getProperty("database", "chacademia").trim();
        String port = p.getProperty("port", "3306").trim();
        String user = p.getProperty("user", "").trim();
        String password = p.getProperty("password", "");
        // Optional TLS for hosted MariaDB: disable | trust | verify-ca | verify-full (MariaDB Connector/J sslMode).
        String sslMode = p.getProperty("ssl-mode", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!host.matches("[A-Za-z0-9.:-]{1,253}") || !port.matches("[0-9]{1,5}")
                || !database.matches("[A-Za-z0-9_]{1,64}") || user.isBlank() || password.isBlank())
            throw new IOException("Invalid MariaDB configuration");
        if (!sslMode.isEmpty() && !sslMode.matches("disable|trust|verify-ca|verify-full"))
            throw new IOException("Invalid ssl-mode: " + sslMode);
        return new DatabaseSettings(true,
                "jdbc:mariadb://" + host + ":" + port + "/" + database
                        + "?connectTimeout=5000&socketTimeout=10000"
                        + (sslMode.isEmpty() ? "" : "&sslMode=" + sslMode), user, password);
    }

    /** Required shared domains must never silently create a new per-server SQLite store. */
    public static DatabaseSettings loadShared(Path file, String domain) throws IOException {
        if (domain == null || !domain.matches("[a-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid shared storage domain");
        if (!Files.isRegularFile(file))
            throw new IOException("Shared DB configuration missing for " + domain + ": " + file.getFileName()
                    + "; local SQLite fallback refused");
        DatabaseSettings settings = load(file);
        if (!settings.mariaDb())
            throw new IOException("Shared DB domain " + domain + " requires MariaDB; local SQLite fallback refused");
        return settings;
    }

    /** Human-readable active mode: {@code "MariaDB"} or {@code "SQLite"}. Never touches the database. */
    public String modeName() {
        return mariaDb ? "MariaDB" : "SQLite";
    }

    /** {@code host:port/database} of the MariaDB target (no credentials, no options); empty for SQLite. */
    public String target() {
        if (!mariaDb) return "";
        String text = url.startsWith("jdbc:mariadb://") ? url.substring("jdbc:mariadb://".length()) : url;
        int query = text.indexOf('?');
        return query < 0 ? text : text.substring(0, query);
    }

    /**
     * One startup log line for a settings file: file name, whether it exists and the mode that results.
     * A missing file silently means SQLite in {@link #load(Path)}; callers log this line so that is visible.
     * Credentials are never included.
     */
    public static String describe(Path file, DatabaseSettings settings) {
        boolean exists = Files.exists(file);
        return "DB 설정 " + file.getFileName() + ": " + (exists ? "파일 있음" : "파일 없음 → SQLite 기본값")
                + ", 모드=" + settings.modeName() + (settings.mariaDb() ? " (" + settings.target() + ")" : "");
    }

    /** Loads the file and returns it together with its {@link #describe} line. */
    public static String describe(Path file) throws IOException {
        return describe(file, load(file));
    }

    public Connection connect(Path sqliteFile) throws Exception {
        if (mariaDb) {
            Class.forName("org.mariadb.jdbc.Driver");
            return DriverManager.getConnection(url, user, password);
        }
        Files.createDirectories(sqliteFile.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
    }

    /** Connection with reconnect-on-demand; {@link #configure} is re-applied to every reopened connection. */
    public ConnectionHolder holder(Path sqliteFile) throws SQLException {
        return new ConnectionHolder(() -> connect(sqliteFile), this::configure, mariaDb);
    }

    public String table(String suffix, String sqliteName) {
        return mariaDb ? "codex_" + suffix : sqliteName;
    }

    public void configure(Connection db) throws SQLException {
        if (mariaDb) return;
        try (var s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
        }
    }
}
