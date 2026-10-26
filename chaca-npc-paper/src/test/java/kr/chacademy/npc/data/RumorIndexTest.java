package kr.chacademy.npc.data;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RumorIndexTest {
    @Test void createsUniqueIndexAndRepeatedInitializationIsSafe() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cnpc_rumors(id INTEGER PRIMARY KEY, event_id BIGINT NOT NULL, heard_by TEXT NOT NULL)");
            st.execute("INSERT INTO cnpc_rumors VALUES (1, 10, 'npc')");
            Storage.uniqueRumorIndex(st);
            Storage.uniqueRumorIndex(st);
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO cnpc_rumors VALUES (2, 10, 'npc')"));
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cnpc_rumors")) { assertTrue(rs.next()); assertEquals(1, rs.getLong(1)); }
        }
    }
    @Test void duplicatesFailInitializationAndPreserveEveryRow() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cnpc_rumors(id INTEGER PRIMARY KEY, event_id BIGINT NOT NULL, heard_by TEXT NOT NULL)");
            st.execute("INSERT INTO cnpc_rumors VALUES (1, 10, 'private-npc'), (2, 10, 'private-npc'), (3, 10, 'private-npc')");
            SQLException e = assertThrows(SQLException.class, () -> Storage.uniqueRumorIndex(st));
            assertTrue(e.getMessage().contains("duplicateGroups=1, excessRows=2"));
            assertFalse(e.getMessage().contains("private-npc")); assertNull(e.getCause());
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(id) FROM cnpc_rumors")) { assertTrue(rs.next()); assertEquals(3, rs.getLong(1)); assertEquals(6, rs.getLong(2)); }
        }
    }
    @Test void permissionFailureIsNotSuccessAndNeverDeletes() { injectedFailure(1142, "permission"); }
    @Test void syntaxFailureIsNotSuccessAndNeverDeletes() { injectedFailure(1064, "syntax"); }
    @Test void duplicateIndexNameErrorIsNotSilentlyAccepted() { injectedFailure(1061, "database"); }
    @Test void duplicateFailureWithoutDiagnosticPermissionStillReportsFailure() { injectedFailure(1062, "constraint/duplicate"); }
    private void injectedFailure(int code, String kind) {
        List<String> sql = new ArrayList<>();
        Statement st = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            if (method.getName().equals("execute")) { sql.add((String) args[0]); throw new SQLException("secret-row-value", "42000", code); }
            if (method.getName().equals("executeQuery")) { sql.add((String) args[0]); throw new SQLException("secret-diagnostic", "42000", 1142); }
            throw new AssertionError("Unexpected database operation: " + method.getName());
        });
        SQLException e = assertThrows(SQLException.class, () -> Storage.uniqueRumorIndex(st));
        assertTrue(e.getMessage().contains(kind)); assertTrue(e.getMessage().contains("counts unavailable"));
        assertFalse(e.getMessage().contains("secret")); assertEquals(code, e.getErrorCode()); assertNull(e.getCause());
        assertEquals(2, sql.size()); assertTrue(sql.get(0).startsWith("CREATE UNIQUE INDEX")); assertTrue(sql.get(1).startsWith("SELECT"));
    }
}
