package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerHeartbeatTest {
    @Test void serverIdFallsBackToFolderName() {
        assertEquals("school", ServerHeartbeat.serverId("", Path.of("C:/Chacademi/network/school")));
        assertEquals("tutorial", ServerHeartbeat.serverId(" ", Path.of("/srv/tutorial/")));
        assertEquals("wild", ServerHeartbeat.serverId("Wild", Path.of("/x/school")));
        assertEquals("a_b", ServerHeartbeat.serverId("a b", null));
        assertEquals("server", ServerHeartbeat.serverId("", null));
        assertEquals(48, ServerHeartbeat.serverId("x".repeat(80), null).length());
    }

    @Test void percentAndFallback() {
        assertEquals(25.0, ServerHeartbeat.percent(0.25));
        assertEquals(100.0, ServerHeartbeat.percent(1.7));
        assertEquals(-1, ServerHeartbeat.percent(-1));
        assertEquals(-1, ServerHeartbeat.percent(Double.NaN));
        assertEquals(19.5, ServerHeartbeat.pick(19.5, 20));
        assertEquals(20, ServerHeartbeat.pick(-1, 20));
        assertEquals(-1, ServerHeartbeat.round(Double.POSITIVE_INFINITY));
        assertEquals(12.35, ServerHeartbeat.round(12.3456));
    }

    @Test void intervalIsClamped() {
        assertEquals(5, ServerHeartbeat.clampInterval(1));
        assertEquals(10, ServerHeartbeat.clampInterval(10));
        assertEquals(60, ServerHeartbeat.clampInterval(600));
    }

    @Test void upsertMatchesTable() {
        String create = ServerHeartbeat.createSql();
        long placeholders = ServerHeartbeat.UPSERT.chars().filter(c -> c == '?').count();
        assertEquals(22, placeholders);
        for (String col : new String[]{"server_id", "state", "updated_at", "tps_10s", "mspt_p95", "cpu_system", "heap_max_mb", "entities", "version"}) {
            assertTrue(create.contains(col), col);
            assertTrue(ServerHeartbeat.UPSERT.contains(col), col);
        }
    }
}
