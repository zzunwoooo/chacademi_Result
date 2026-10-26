package kr.chacademy.npc.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * AI 호출 실패 집계 + 간단한 차단기(circuit breaker). Bukkit·네트워크와 무관한 순수 계산이라 시각은 밖에서 넘겨받는다.
 * <ul>
 *   <li>실패를 종류별로 분 단위 칸에 세어 "최근 1시간" 집계를 낸다.</li>
 *   <li>연속 실패가 기준을 넘으면 cool-off 동안 호출을 막고(열림), 그 뒤에는 가끔 한 건씩만 보내 본다(반열림).
 *       한 건이라도 성공하면 닫힌다.</li>
 * </ul>
 */
public final class AiHealth {

    public enum Transition { NONE, OPENED, CLOSED }

    /** 반열림 상태에서 시험 호출 사이 간격. */
    static final long PROBE_GAP_MS = 5_000L;
    private static final int MINUTES = 60;

    private static final class Bucket {
        final long[] minute = new long[MINUTES];
        final int[] count = new int[MINUTES];
    }

    private final Map<String, Bucket> buckets = new TreeMap<>();
    private final Map<String, Integer> sinceSummary = new TreeMap<>();
    private long lastSummaryAt;

    private int threshold;
    private long cooloffMs;
    private int consecutive;
    private boolean open;
    private long nextAllowedAt;

    public AiHealth(int threshold, long cooloffMs) {
        configure(threshold, cooloffMs);
    }

    /** threshold 0 이하 = 차단기 끔. */
    public synchronized void configure(int threshold, long cooloffMs) {
        this.threshold = threshold;
        this.cooloffMs = Math.max(1_000L, cooloffMs);
        if (threshold <= 0) {
            open = false;
        }
    }

    // ------------------------------------------------------------ 실패 집계

    /**
     * 실패 한 건 기록.
     *
     * @param forSummary false 면 1시간 집계에만 넣고 "1분 요약 로그"에는 넣지 않는다
     * @return 이 종류가 (켜진 뒤) 처음이면 true — 호출자가 바로 로그를 남긴다
     */
    public synchronized boolean recordFailure(String category, long now, boolean forSummary) {
        boolean first = !buckets.containsKey(category);
        Bucket b = buckets.computeIfAbsent(category, k -> new Bucket());
        long minute = now / 60_000L;
        int i = (int) Math.floorMod(minute, (long) MINUTES);
        if (b.minute[i] != minute) {
            b.minute[i] = minute;
            b.count[i] = 0;
        }
        b.count[i]++;
        if (forSummary && !first) {
            sinceSummary.merge(category, 1, Integer::sum);
        }
        return first;
    }

    /** 최근 1시간 종류별 실패 수 (0건인 종류는 뺀다). */
    public synchronized Map<String, Integer> lastHour(long now) {
        long minute = now / 60_000L;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            int sum = 0;
            Bucket b = e.getValue();
            for (int i = 0; i < MINUTES; i++) {
                if (b.count[i] > 0 && minute - b.minute[i] < MINUTES && minute - b.minute[i] >= 0) {
                    sum += b.count[i];
                }
            }
            if (sum > 0) {
                out.put(e.getKey(), sum);
            }
        }
        return out;
    }

    /** 마지막 요약 뒤 1분이 지났고 그 사이 실패가 있었으면 "timeout=3, server=1" 같은 요약, 아니면 null. */
    public synchronized String summaryIfDue(long now) {
        if (sinceSummary.isEmpty() || now - lastSummaryAt < 60_000L) {
            return null;
        }
        lastSummaryAt = now;
        String s = format(sinceSummary);
        sinceSummary.clear();
        return s;
    }

    public static String format(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 차단기

    /** 지금 호출을 보내도 되는지. 반열림이면 PROBE_GAP_MS 마다 한 건만 true. */
    public synchronized boolean allow(long now) {
        if (!open) {
            return true;
        }
        if (now < nextAllowedAt) {
            return false;
        }
        nextAllowedAt = now + PROBE_GAP_MS;
        return true;
    }

    /** 상태를 바꾸지 않고 "지금 막혀 있는지"만 본다. */
    public synchronized boolean blocked(long now) {
        return open && now < nextAllowedAt;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized int consecutiveFailures() {
        return consecutive;
    }

    /** API 쪽 실패(시간 초과·429·5xx·연결 실패·인증 실패 등) 한 건. */
    public synchronized Transition onFailure(long now) {
        consecutive++;
        if (threshold <= 0) {
            return Transition.NONE;
        }
        if (open) {
            nextAllowedAt = now + cooloffMs; // 시험 호출도 실패: 다시 쉰다
            return Transition.NONE;
        }
        if (consecutive >= threshold) {
            open = true;
            nextAllowedAt = now + cooloffMs;
            return Transition.OPENED;
        }
        return Transition.NONE;
    }

    public synchronized Transition onSuccess() {
        consecutive = 0;
        if (open) {
            open = false;
            return Transition.CLOSED;
        }
        return Transition.NONE;
    }
}
