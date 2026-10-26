package kr.chacademy.npc.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 오너 결정 6 (2026-10-21): 1인 하루 60회, 같은 DB를 쓰는 서버끼리 합산 (조건부 UPDATE 한 번). */
class DailyCallLimitTest {
    @Test
    void sharedCounterStopsAtLimitAcrossServers(@TempDir Path dir) throws Exception {
        // two "servers" on the same SQLite file (no database.properties → SQLite chaca-npc.db)
        Database a = new Database(dir, Logger.getLogger("a"));
        Database b = new Database(dir, Logger.getLogger("b"));
        try {
            Storage sa = new Storage(a), sb = new Storage(b);
            a.call(() -> { sa.createTables(); return null; }).get();
            int granted = 0;
            for (int i = 0; i < 40; i++) {
                if (a.call(() -> sa.tryAddCall("2026-10-26", "p1", 60)).get()) granted++;
                if (b.call(() -> sb.tryAddCall("2026-10-26", "p1", 60)).get()) granted++;
            }
            assertEquals(60, granted);
            assertFalse(a.call(() -> sa.tryAddCall("2026-10-26", "p1", 60)).get());
            // refund frees one slot, never goes below zero
            b.run(() -> sb.refundCall("2026-10-26", "p1")).get();
            assertTrue(a.call(() -> sa.tryAddCall("2026-10-26", "p1", 60)).get());
            b.run(() -> sb.refundCall("2026-10-26", "nobody")).get();
            // another day / player has its own count
            assertTrue(b.call(() -> sb.tryAddCall("2026-10-27", "p1", 60)).get());
            assertTrue(b.call(() -> sb.tryAddCall("2026-10-26", "p2", 60)).get());
        } finally {
            a.close();
            b.close();
        }
    }
}
