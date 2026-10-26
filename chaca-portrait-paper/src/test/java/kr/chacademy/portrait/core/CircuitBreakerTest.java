package kr.chacademy.portrait.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void opensAfterFiveConsecutiveFailuresAndReportsOnce() {
        CircuitBreaker b = new CircuitBreaker(5, 60_000L, 600_000L);
        long now = 1_000_000L;
        for (int i = 0; i < 4; i++) {
            assertFalse(b.failure(now));
        }
        assertFalse(b.paused(now));
        assertEquals(0, b.waitMs(now));
        assertTrue(b.failure(now), "다섯 번째에 열림 (로그 1회)");
        assertFalse(b.failure(now + 1), "이미 열려 있으면 다시 알리지 않음");
        assertTrue(b.paused(now + 1));
        assertEquals(60_000L, b.waitMs(now));
        assertEquals(0, b.waitMs(now + 60_000L));
    }

    @Test
    void successResetsTheCount() {
        CircuitBreaker b = new CircuitBreaker(5, 60_000L, 600_000L);
        for (int i = 0; i < 4; i++) {
            b.failure(0);
        }
        assertFalse(b.success(), "열린 적이 없으면 닫힘 알림도 없음");
        for (int i = 0; i < 4; i++) {
            assertFalse(b.failure(0));
        }
        assertFalse(b.paused(0));
    }

    @Test
    void failedProbeDoublesThePauseUpToTheCap() {
        CircuitBreaker b = new CircuitBreaker(5, 60_000L, 600_000L);
        long now = 0;
        for (int i = 0; i < 5; i++) {
            b.failure(now);
        }
        long[] expected = {120_000L, 240_000L, 480_000L, 600_000L, 600_000L};
        for (long cool : expected) {
            now += b.waitMs(now); // 쉬는 시간이 지나 시험 작업을 보냄
            assertFalse(b.paused(now));
            assertFalse(b.failure(now), "시험 작업 실패는 새로 열린 것이 아님");
            assertEquals(cool, b.coolMs());
            assertEquals(cool, b.waitMs(now));
        }
        assertTrue(b.success(), "정상 응답이 오면 닫힘 (로그 1회)");
        assertFalse(b.success());
        assertFalse(b.paused(now));
        for (int i = 0; i < 4; i++) {
            assertFalse(b.failure(now));
        }
        assertTrue(b.failure(now));
        assertEquals(60_000L, b.coolMs(), "다시 열리면 처음 길이부터");
    }
}
