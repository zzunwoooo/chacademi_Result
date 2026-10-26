package school.chacademi.ops;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import school.magiccodex.database.DatabaseSettings;

/**
 * 공유 MariaDB 연결 하나 (읽기 위주). 끊기면 다음 사용 때 다시 연다. 모니터·검색 스레드가 같이 쓰므로 synchronized.
 * db.properties-file 이 있으면 게임 서버의 database.properties 를 그대로 읽는다 (비밀번호를 두 번 적지 않게).
 */
final class Db implements AutoCloseable {
    interface Work<T> { T run(Connection c) throws SQLException; }

    private final String url, user, password;
    private Connection current;
    private String lastError = "";

    private Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    static Db open(Config c) throws IOException {
        if (!c.dbConfigured()) return null;
        if (c.dbPropertiesFile != null) {
            DatabaseSettings s = DatabaseSettings.load(c.dbPropertiesFile);
            if (!s.mariaDb()) throw new IOException(c.dbPropertiesFile + " 가 MariaDB 설정이 아님");
            return new Db(s.url(), s.user(), s.password());
        }
        if (!c.dbName.matches("[A-Za-z0-9_]{1,64}") || !c.dbHost.matches("[A-Za-z0-9.:-]{1,253}"))
            throw new IOException("db.name / db.host 형식");
        return new Db("jdbc:mariadb://" + c.dbHost + ":" + c.dbPort + "/" + c.dbName + "?connectTimeout=4000&socketTimeout=10000",
                c.dbUser, c.dbPassword);
    }

    synchronized <T> T with(Work<T> work) throws SQLException {
        try {
            T out = work.run(connection());
            lastError = "";
            return out;
        } catch (SQLException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try { if (current != null) current.close(); } catch (SQLException ignored) { }
            current = null;
            throw e;
        }
    }

    private Connection connection() throws SQLException {
        if (current != null) {
            try { if (!current.isClosed() && current.isValid(2)) return current; } catch (SQLException ignored) { }
            try { current.close(); } catch (SQLException ignored) { }
            current = null;
        }
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (ClassNotFoundException e) { throw new SQLException("MariaDB 드라이버 없음"); }
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        current = DriverManager.getConnection(url, p);
        return current;
    }

    synchronized String lastError() { return lastError; }

    @Override public synchronized void close() {
        if (current != null) try { current.close(); } catch (SQLException ignored) { }
        current = null;
    }
}
