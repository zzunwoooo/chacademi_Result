package kr.chacademy.npc.core;

import java.util.List;

/**
 * 일과표 한 칸. minuteOfDay = 마크 시계 기준 하루 중 분(0~1439, 06:00 = 360).
 */
public record ScheduleEntry(int minuteOfDay, String place, String activity) {

    /**
     * "6:00", "06:30" 같은 문자열 또는 YAML이 숫자로 읽은 값(6:00 → 360)을 분으로 바꾼다.
     */
    public static int parseTime(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("time is missing");
        }
        if (raw instanceof Number n) {
            // SnakeYAML(YAML 1.1)은 따옴표 없는 6:00을 60진수 정수 360으로 읽는다 = 이미 '분'
            int v = n.intValue();
            return ((v % 1440) + 1440) % 1440;
        }
        String s = raw.toString().trim();
        String[] parts = s.split(":");
        int h = Integer.parseInt(parts[0].trim());
        int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
        if (h < 0 || h > 24 || m < 0 || m > 59) {
            throw new IllegalArgumentException("bad time " + s);
        }
        return (h * 60 + m) % 1440;
    }

    /** 마크 월드 시간(틱, 0 = 06:00)을 하루 중 분으로. */
    public static int minuteFromWorldTime(long worldTime) {
        long t = ((worldTime % 24000) + 24000) % 24000;
        int minutesSinceSix = (int) (t * 60 / 1000); // 1000틱 = 1시간
        return (360 + minutesSinceSix) % 1440;
    }

    /** 지금 시각에 해당하는 칸의 번호. 일과표가 비어 있으면 -1. */
    public static int currentIndex(List<ScheduleEntry> schedule, int minuteOfDay) {
        if (schedule == null || schedule.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestMinute = -1;
        int latest = -1;
        int latestMinute = -1;
        for (int i = 0; i < schedule.size(); i++) {
            int m = schedule.get(i).minuteOfDay();
            if (m <= minuteOfDay && m > bestMinute) {
                best = i;
                bestMinute = m;
            }
            if (m > latestMinute) {
                latest = i;
                latestMinute = m;
            }
        }
        // 오늘 가장 이른 칸보다 이르면 = 어제의 마지막 칸이 이어지는 중
        return best >= 0 ? best : latest;
    }

    /** 시간대 이름 (프롬프트용). */
    public static String partOfDay(int minuteOfDay) {
        int h = minuteOfDay / 60;
        if (h < 5) {
            return "새벽";
        } else if (h < 9) {
            return "아침";
        } else if (h < 12) {
            return "오전";
        } else if (h < 14) {
            return "점심때";
        } else if (h < 18) {
            return "오후";
        } else if (h < 21) {
            return "저녁";
        }
        return "밤";
    }
}
