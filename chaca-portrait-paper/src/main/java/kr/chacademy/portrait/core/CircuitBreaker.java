package kr.chacademy.portrait.core;

/**
 * OpenAI 장애 차단기. 일시적 실패(429·5xx·시간 초과·연결 실패)가 연속으로 threshold번 나면 "열림" 상태가 되어
 * 쉬는 시간 동안 새 작업을 시작하지 않는다. 쉬는 시간이 지나면 작업 하나를 시험 삼아 보내고(반열림),
 * 또 실패하면 쉬는 시간을 두 배(최대 maxMs)로 늘려 다시 쉰다. 한 번이라도 응답이 정상적으로 오면 닫힌다.
 * 시각은 호출 측이 넘긴다 (테스트 가능). 모든 메서드는 스레드 안전.
 */
public final class CircuitBreaker {

    private final int threshold;
    private final long baseMs;
    private final long maxMs;
    private int consecutive;
    private boolean open;
    private long openUntil;
    private long cool;

    public CircuitBreaker(int threshold, long baseMs, long maxMs) {
        this.threshold = Math.max(1, threshold);
        this.baseMs = Math.max(1, baseMs);
        this.maxMs = Math.max(this.baseMs, maxMs);
    }

    /** 일시적 실패 1건. 이번 호출로 차단기가 새로 열렸으면 true (로그 1회용). */
    public synchronized boolean failure(long now) {
        consecutive++;
        if (open) {
            if (now >= openUntil) {
                // 시험 작업도 실패 → 더 길게 쉰다
                cool = Math.min(maxMs, cool * 2);
                openUntil = now + cool;
            }
            return false;
        }
        if (consecutive < threshold) {
            return false;
        }
        open = true;
        cool = baseMs;
        openUntil = now + cool;
        return true;
    }

    /** 서버가 정상적으로 응답함 (성공 또는 내용 거부). 이번 호출로 차단기가 닫혔으면 true (로그 1회용). */
    public synchronized boolean success() {
        consecutive = 0;
        if (!open) {
            return false;
        }
        open = false;
        openUntil = 0;
        return true;
    }

    /** 새 작업을 시작하기 전에 기다려야 하는 시간 (ms). 0이면 바로 시작해도 된다. */
    public synchronized long waitMs(long now) {
        return open && now < openUntil ? openUntil - now : 0;
    }

    /** 지금 쉬는 중인지 (새 요청을 받지 않는 편이 나음). */
    public synchronized boolean paused(long now) {
        return open && now < openUntil;
    }

    /** 현재 쉬는 시간 길이 (ms, 로그용). */
    public synchronized long coolMs() {
        return cool;
    }
}
