package school.magiccodex.paper;

import java.util.UUID;

/**
 * 인기도 규칙 (메모 #87, 오너 2026-10-26). Bukkit 없음 — PopularityRulesTest.
 * <ul>
 *   <li>다른 플레이어의 인기도를 올리거나 내릴 수 있다. 자기 자신은 안 된다.</li>
 *   <li>같은 상대에게 하루 1번 (올리기든 내리기든). 상대마다 따로 센다.</li>
 *   <li>오늘 이미 조절한 상대에게 한 번 더 하면 인기도 제한 해제권 1개를 써서 제한을 무시한다. 해제권이 없으면 거절.</li>
 * </ul>
 */
final class PopularityRules {
    /** FREE = 오늘 처음 · TICKET = 해제권으로 한 번 더 · LIMIT = 오늘 이미 함(해제권 안 씀) · SELF = 자기 자신 */
    enum Outcome { FREE, TICKET, LIMIT, SELF }

    static final int MIN_SCORE = -1_000_000, MAX_SCORE = 1_000_000;

    private PopularityRules() {}

    /**
     * @param alreadyToday 오늘(행위자→대상) 기록이 하나라도 있는지
     * @param useTicket    해제권을 쓸 준비가 됐는지 (서버가 해제권 1개를 미리 떼어 둔 경우만 true)
     */
    static Outcome decide(UUID actor, UUID target, boolean alreadyToday, boolean useTicket) {
        if (actor.equals(target)) return Outcome.SELF;
        if (!alreadyToday) return Outcome.FREE;
        return useTicket ? Outcome.TICKET : Outcome.LIMIT;
    }

    static int delta(boolean up) { return up ? 1 : -1; }

    /** 점수 범위 안으로 (관리 설정·누적 모두). */
    static int clamp(long score) { return (int) Math.max(MIN_SCORE, Math.min(MAX_SCORE, score)); }

    /** 설정 문구의 {player} 를 행위자 이름으로. */
    static String format(String template, String player) {
        return template == null ? "" : template.replace("{player}", player == null ? "" : player);
    }
}
