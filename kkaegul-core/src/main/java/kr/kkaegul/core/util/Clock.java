package kr.kkaegul.core.util;

import kr.kkaegul.core.KkaegulCore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** 서버 일차 / 일일 초기화 키 (Asia/Seoul 기준, reset-hour 시에 날이 바뀜) */
public final class Clock {
    private final KkaegulCore plugin;

    public Clock(KkaegulCore plugin) { this.plugin = plugin; }

    public ZoneId zone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("server.timezone", "Asia/Seoul"));
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    /** 일일 초기화 시각을 반영한 "오늘" */
    public LocalDate today() {
        int resetHour = plugin.getConfig().getInt("server.daily-reset-hour", 0);
        return ZonedDateTime.now(zone()).minusHours(resetHour).toLocalDate();
    }

    public String dayKey() { return today().toString(); }

    /** 서버 오픈일 = 1일차. 오픈 전이면 0 이하 */
    public int serverDay() {
        String s = plugin.getConfig().getString("server.start-date", "2026-11-10");
        try {
            LocalDate start = LocalDate.parse(s);
            return (int) ChronoUnit.DAYS.between(start, today()) + 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** 초반 보호(1~2일차 500개 미만 감소 금지)가 지금 켜져 있는지 */
    public boolean protectionActive() {
        if (!plugin.getConfig().getBoolean("protection.enabled", true)) return false;
        String force = plugin.getConfig().getString("protection.force", "auto");
        if ("on".equalsIgnoreCase(force)) return true;
        if ("off".equalsIgnoreCase(force)) return false;
        int day = serverDay();
        boolean beforeOpen = plugin.getConfig().getBoolean("protection.active-before-open", true);
        if (day <= 0) return beforeOpen;
        return day <= plugin.getConfig().getInt("protection.days", 2);
    }

    public boolean isPeperoSeason() {
        String from = plugin.getConfig().getString("events.pepero.from", "2026-11-09");
        String to = plugin.getConfig().getString("events.pepero.to", "2026-11-13");
        try {
            LocalDate t = today();
            return !t.isBefore(LocalDate.parse(from)) && !t.isAfter(LocalDate.parse(to));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
