package kr.chacademy.npc.core;

/**
 * 예산 계산. "남은 예산 ÷ 남은 날짜"로 그날 한도를 정한다.
 */
public final class BudgetMath {

    private BudgetMath() {
    }

    public enum Status { NORMAL, REDUCED, EXHAUSTED }

    /**
     * @param totalUsd        전체 예산
     * @param spentBeforeToday 어제까지 쓴 돈
     * @param totalDays       운영 일수
     * @param dayIndex        오늘이 몇 번째 날인지 (0부터). 시작 전이면 음수, 끝난 뒤면 totalDays 이상
     * @param testDailyUsd    시작 전 테스트 기간의 하루 한도
     */
    public static double dailyAllowance(double totalUsd, double spentBeforeToday, int totalDays,
                                        int dayIndex, double testDailyUsd) {
        double remaining = Math.max(0, totalUsd - spentBeforeToday);
        if (dayIndex < 0) {
            return Math.min(testDailyUsd, remaining);
        }
        int daysLeft = Math.max(1, totalDays - dayIndex);
        return remaining / daysLeft;
    }

    public static Status status(double spentToday, double allowance, double reducePercent) {
        if (allowance <= 0 || spentToday >= allowance) {
            return Status.EXHAUSTED;
        }
        if (spentToday >= allowance * reducePercent / 100.0) {
            return Status.REDUCED;
        }
        return Status.NORMAL;
    }

    /** API 사용량으로 비용 계산 (가격은 100만 토큰당 달러). */
    public static double cost(long inputTokens, long cachedTokens, long outputTokens,
                              double inPerM, double cachedPerM, double outPerM) {
        long uncached = Math.max(0, inputTokens - cachedTokens);
        return (uncached * inPerM + cachedTokens * cachedPerM + outputTokens * outPerM) / 1_000_000.0;
    }
}
