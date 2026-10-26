package kr.chacademy.npc.core;

/**
 * "약 3시간 전" 같은 경과 시간 표현.
 */
public final class TimeText {

    private TimeText() {
    }

    public static String ago(long thenMillis, long nowMillis) {
        long diff = Math.max(0, nowMillis - thenMillis);
        long minutes = diff / 60000;
        if (minutes < 60) {
            return "조금 전";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "약 " + hours + "시간 전";
        }
        if (hours < 48) {
            return "어제";
        }
        return "약 " + (hours / 24) + "일 전";
    }
}
