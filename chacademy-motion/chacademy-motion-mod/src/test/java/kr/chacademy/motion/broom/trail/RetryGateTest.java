package kr.chacademy.motion.broom.trail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryGateTest {
    @Test void backoffGrowsAndCaps() {
        var g = new RetryGate();
        assertTrue(g.allowed(0));
        long now = 0;
        long[] expected = {20, 40, 80, 160, 320, 400, 400};
        for (long wait : expected) {
            g.failed(now);
            assertFalse(g.allowed(now + wait - 1));
            assertTrue(g.allowed(now + wait));
            now += wait;
        }
    }

    @Test void shortLivedInstanceCountsAsFailureLongLivedResets() {
        var g = new RetryGate();
        g.ended(100, 5);
        assertEquals(1, g.failures());
        assertFalse(g.allowed(110));
        g.ended(1000, RetryGate.HEALTHY_TICKS);
        assertEquals(0, g.failures());
        assertTrue(g.allowed(1000));
    }

    @Test void tooManyRequestsAreBounded() {
        // 24명이 계속 실패해도 400틱(20초)에 한 번 이하로만 시도
        var g = new RetryGate();
        int attempts = 0;
        for (long t = 0; t < 20 * 60 * 10; t++) {
            if (g.allowed(t)) { attempts++; g.failed(t); }
        }
        assertTrue(attempts < 40, "attempts " + attempts);
    }
}
