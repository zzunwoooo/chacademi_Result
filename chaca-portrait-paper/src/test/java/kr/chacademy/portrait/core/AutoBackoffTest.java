package kr.chacademy.portrait.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AutoBackoffTest {

    private static final long GAP = 30 * 60 * 1000L;

    @Test
    void oneRetryPerGapAndFivePerDay() {
        AutoBackoff b = new AutoBackoff(GAP, 5);
        UUID id = UUID.randomUUID();
        long now = 0;
        assertTrue(b.allowed(id, now, "2026-10-11"));
        for (int i = 1; i <= 5; i++) {
            b.failed(id, now, "2026-10-11");
            assertFalse(b.allowed(id, now + GAP - 1, "2026-10-11"), "30분 안에는 다시 시도하지 않음");
            now += GAP;
            assertEquals(i < 5, b.allowed(id, now, "2026-10-11"), "하루 5번까지");
        }
        assertTrue(b.allowed(id, now, "2026-10-12"), "날짜가 바뀌면 다시 가능");
        b.failed(id, now, "2026-10-12");
        assertFalse(b.allowed(id, now + 1, "2026-10-12"));
        assertTrue(b.allowed(id, now + GAP, "2026-10-12"));
        assertTrue(b.allowed(UUID.randomUUID(), now, "2026-10-12"), "다른 플레이어와 무관");
    }

    @Test
    void clearRemovesTheLimit() {
        AutoBackoff b = new AutoBackoff(GAP, 5);
        UUID id = UUID.randomUUID();
        b.failed(id, 0, "d");
        assertFalse(b.allowed(id, 1, "d"));
        b.clear(id);
        assertTrue(b.allowed(id, 1, "d"));
    }
}
