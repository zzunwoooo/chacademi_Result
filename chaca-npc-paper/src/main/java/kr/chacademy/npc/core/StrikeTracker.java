package kr.chacademy.npc.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 장난·탈옥 시도 누적. 일정 시간 안에 여러 번 걸리면 그 NPC와 대화를 잠근다.
 */
public final class StrikeTracker {

    private final Map<String, Deque<Long>> strikes = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private volatile long windowMillis;
    private volatile int maxStrikes;
    private volatile long lockMillis;

    public StrikeTracker(long windowMillis, int maxStrikes, long lockMillis) {
        this.windowMillis = windowMillis;
        this.maxStrikes = maxStrikes;
        this.lockMillis = lockMillis;
    }

    /** /서버관리 새로고침 NPC: 쌓인 기록·잠금은 그대로 두고 기준만 바꾼다. */
    public void reconfigure(long windowMillis, int maxStrikes, long lockMillis) {
        this.windowMillis = windowMillis;
        this.maxStrikes = maxStrikes;
        this.lockMillis = lockMillis;
    }

    private static String key(String player, String npc) {
        return player + "|" + npc;
    }

    /** 한 번 걸림을 기록. 이번에 잠기면 true. */
    public boolean addStrike(String player, String npc, long now) {
        String k = key(player, npc);
        boolean[] locked = {false};
        // 기록 큐는 compute 안에서만 만진다 (prune 과 겹쳐도 기록이 사라지지 않게)
        strikes.compute(k, (x, old) -> {
            Deque<Long> q = old == null ? new ArrayDeque<>() : old;
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > windowMillis) {
                q.removeFirst();
            }
            if (q.size() >= maxStrikes) {
                lockedUntil.put(k, now + lockMillis);
                locked[0] = true;
                return null;
            }
            return q;
        });
        return locked[0];
    }

    public boolean isLocked(String player, String npc, long now) {
        Long until = lockedUntil.get(key(player, npc));
        if (until == null) {
            return false;
        }
        if (now >= until) {
            lockedUntil.remove(key(player, npc));
            return false;
        }
        return true;
    }

    /** 지난 기록·풀린 잠금 정리 (가끔 호출). 접속을 끊은 플레이어의 기록이 계속 쌓이지 않게 한다. */
    public void prune(long now) {
        lockedUntil.values().removeIf(until -> now >= until);
        for (String k : strikes.keySet()) {
            strikes.computeIfPresent(k, (x, q) -> {
                while (!q.isEmpty() && now - q.peekFirst() > windowMillis) {
                    q.removeFirst();
                }
                return q.isEmpty() ? null : q;
            });
        }
    }

    /** 기록이 남아 있는 (플레이어, NPC) 항목 수 — 테스트·점검용. */
    public int tracked() {
        return strikes.size() + lockedUntil.size();
    }
}
