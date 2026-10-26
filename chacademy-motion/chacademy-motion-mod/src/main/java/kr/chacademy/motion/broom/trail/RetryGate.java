package kr.chacademy.motion.broom.trail;

/**
 * 생성 실패 뒤 재시도 간격. 실패할수록 20 → 40 → 80 → 160 → 320 → 400틱(최대)으로 늘고,
 * 오래(200틱 이상) 살아 있었던 인스턴스가 끝나면 초기화한다. 마인크래프트 타입 없음.
 */
final class RetryGate {
    static final int BASE_TICKS = 20, MAX_TICKS = 400, HEALTHY_TICKS = 200;

    private int failures;
    private long nextAttempt = Long.MIN_VALUE;

    boolean allowed(long now) {
        return now >= nextAttempt;
    }

    void failed(long now) {
        failures = Math.min(failures + 1, 16);
        long wait = Math.min(MAX_TICKS, (long) BASE_TICKS << Math.min(failures - 1, 5));
        nextAttempt = now + wait;
    }

    /** 인스턴스가 사라졌을 때: 짧게 살았으면 실패, 오래 살았으면 정상 종료로 본다. */
    void ended(long now, long lived) {
        if (lived < HEALTHY_TICKS) failed(now);
        else {
            failures = 0;
            nextAttempt = now;
        }
    }

    void reset() {
        failures = 0;
        nextAttempt = Long.MIN_VALUE;
    }

    int failures() {
        return failures;
    }

    long nextAttempt() {
        return nextAttempt;
    }
}
