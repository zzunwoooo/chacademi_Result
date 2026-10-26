package school.magiccodex.paper;

import java.util.*;

/**
 * 전투 중 이동 막기 (메모 #91) 순수 규칙 — Bukkit 없이 시험할 수 있는 부분만.
 * 설정 읽기·명령 이름 뽑기·남은 시간 계산.
 */
final class CombatLockRules {
    private CombatLockRules() {}

    /** combat-lock.yml 내용. commands·spells 는 소문자, servers 가 비어 있으면 모든 서버. */
    record Settings(boolean enabled, long windowMs, Set<String> servers, Set<String> commands, Set<String> spells, long notifyCooldownMs) {
        static final Settings DEFAULT = new Settings(true, 10_000, Set.of("school", "wild1", "wild2"),
                Set.of("home", "phome", "publichome", "spawn", "warp", "tpa", "tpahere", "tpaccept", "tpyes", "back", "rtp", "randomtp",
                        "집", "홈", "스폰", "워프", "뒤로", "귀환"),
                Set.of("companion_return"), 1500);

        /** 이 서버(travel.yml server-id)에서 막는지. ID를 모르면(빈 값) 막는 쪽. */
        boolean appliesTo(String server) {
            return enabled && (servers.isEmpty() || server == null || server.isBlank() || servers.contains(server.strip().toLowerCase(Locale.ROOT)));
        }
    }

    /** 설정 값 정리. seconds 1~120 (기본 10), 알림 쿨다운 0~10000ms. */
    static Settings settings(boolean enabled, double seconds, Collection<String> servers, Collection<String> commands, Collection<String> spells, long notifyCooldownMs) {
        double s = Double.isFinite(seconds) ? Math.clamp(seconds, 1, 120) : 10;
        return new Settings(enabled, Math.round(s * 1000), lower(servers), commandSet(commands), lower(spells), Math.clamp(notifyCooldownMs, 0, 10_000));
    }

    private static Set<String> lower(Collection<String> in) {
        var out = new HashSet<String>();
        if (in != null) for (String s : in) if (s != null && !s.isBlank()) out.add(s.strip().toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    /** 명령 목록: 앞의 "/" 와 "플러그인:" 을 떼고 소문자로. */
    static Set<String> commandSet(Collection<String> in) {
        var out = new HashSet<String>();
        if (in != null) for (String s : in) { String l = label(s); if (l != null) out.add(l); }
        return Set.copyOf(out);
    }

    /**
     * 채팅창에 친 명령 줄 → 명령 이름 (첫 낱말, "/" 와 "huskhomes:" 같은 앞부분을 뗀 소문자). 명령이 아니면 null.
     * "/HuskHomes:Home 집1" → "home", "/집" → "집".
     */
    static String label(String line) {
        if (line == null) return null;
        String s = line.strip();
        while (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        s = s.toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    /** 마지막 전투 시각(없으면 음수)부터 window 안이면 남은 밀리초, 아니면 0. */
    static long remaining(long lastAt, long now, long window) {
        if (lastAt < 0) return 0;
        long left = lastAt + window - now;
        return left > 0 ? Math.min(left, window) : 0;
    }

    /** 알림에 쓰는 남은 초 (올림, 최소 1). */
    static long seconds(long remainingMs) { return Math.max(1, (remainingMs + 999) / 1000); }

    static String message(long remainingMs) { return "전투 중이라 이동할 수 없어요 · " + seconds(remainingMs) + "초 뒤 가능"; }
}
