package kr.chacademy.portrait.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 자동 생성의 "횟수에 넣지 않는 실패"(스킨 다운로드 실패, 외형 정리 실패, 예산 부족, 설정 문제, 일시적 장애 등) 뒤
 * 같은 플레이어를 접속할 때마다 다시 시도하지 않도록 하는 간격 제한.
 * 플레이어마다 실패 후 minGapMs 동안, 그리고 하루(dayKey 기준) maxPerDay번을 넘으면 그날은 자동 시도를 쉰다.
 * 메모리에만 둔다 — 서버를 재시작하면 초기화된다 (재시작 후 한 번 더 시도하는 정도는 허용).
 */
public final class AutoBackoff {

    private record Entry(long lastAt, String day, int count) {
    }

    private final long minGapMs;
    private final int maxPerDay;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public AutoBackoff(long minGapMs, int maxPerDay) {
        this.minGapMs = minGapMs;
        this.maxPerDay = maxPerDay;
    }

    /** 지금 자동 시도를 해도 되는지. */
    public boolean allowed(UUID id, long now, String day) {
        Entry e = entries.get(id);
        if (e == null) {
            return true;
        }
        if (now - e.lastAt() < minGapMs) {
            return false;
        }
        return !day.equals(e.day()) || e.count() < maxPerDay;
    }

    /** 횟수에 넣지 않는 실패 1건 기록. */
    public void failed(UUID id, long now, String day) {
        entries.merge(id, new Entry(now, day, 1),
                (old, fresh) -> new Entry(now, day, day.equals(old.day()) ? old.count() + 1 : 1));
    }

    /** 성공·관리자 초기화 시. */
    public void clear(UUID id) {
        entries.remove(id);
    }
}
