package kr.chacademy.motionplugin.broom;

/** 비행 숙련도: 빗자루를 탄 누적 시간으로 정한다. Bukkit 에 의존하지 않는다. */
public final class BroomMastery {
    private static final String[] LEVELS = {"견습", "초보", "익숙", "능숙", "숙련", "마스터"};

    private BroomMastery() {}

    /** 0~1. fullSeconds 를 채우면 1. */
    public static float of(long seconds, long fullSeconds) {
        if (fullSeconds <= 0) return 1f;
        return (float) Math.max(0, Math.min(1, (double) seconds / fullSeconds));
    }

    /** 0~1 값의 등급 이름. */
    public static String level(float mastery) {
        if (mastery >= 1f) return LEVELS[5];
        return LEVELS[Math.max(0, Math.min(4, (int) (mastery * 5)))];
    }

    /** 0~100 퍼센트를 누적 초로 바꾼다 (관리자 명령어용). */
    public static long secondsForPercent(double percent, long fullSeconds) {
        return Math.round(Math.max(0, Math.min(100, percent)) / 100.0 * fullSeconds);
    }
}
