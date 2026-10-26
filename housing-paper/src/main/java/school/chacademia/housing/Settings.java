package school.chacademia.housing;

import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import school.magiccodex.protocol.HousingRules;

/**
 * config.yml 값.
 * likeGlobal: 좋아요(추천) 1회 단위 — false = 방마다 한 번 (기본), true = 모든 방을 통틀어 한 번 (#78, 2026-10-25).
 * visitOnceEver: 누적 방문 집계 단위 — false = 방문자별 하루 한 번 (기본, 예전 동작), true = 방문자별 평생 한 번 (#51).
 */
record Settings(String server, int startSize, TreeMap<Integer, Long> expansion, boolean visitCommand, long guestbookCooldownMs, int guestbookKeep, int presenceTicks,
                boolean likeGlobal, boolean visitOnceEver) {
    static Settings from(ConfigurationSection c, Logger log) {
        String server = c.getString("server-name", "school").strip();
        if (!server.matches("[A-Za-z0-9_-]{1,32}")) { log.warning("server-name 은 영문·숫자 32자 이하: school 로 씁니다."); server = "school"; }
        int start = Math.clamp(c.getInt("start-size", 7), HousingRules.MIN_SIZE, HousingRules.MAX_SIZE);
        var steps = new TreeMap<Integer, Long>();
        var section = c.getConfigurationSection("expansion");
        if (section != null) for (String key : section.getKeys(false)) {
            try {
                int size = Integer.parseInt(key.strip());
                long cost = section.getLong(key, -1);
                if (size <= start || size > HousingRules.MAX_SIZE || cost < 0) { log.warning("expansion " + key + ": 크기는 start-size 보다 크고 " + HousingRules.MAX_SIZE + " 이하, 비용은 0 이상"); continue; }
                steps.put(size, cost);
            } catch (NumberFormatException e) { log.warning("expansion " + key + ": 크기는 숫자"); }
        }
        return new Settings(server, start, steps, c.getBoolean("visit-command", true),
                Math.max(0, c.getLong("guestbook.cooldown-seconds", 10)) * 1000, Math.clamp(c.getInt("guestbook.keep", 100), 10, 1000),
                Math.clamp(c.getInt("presence-interval-ticks", 40), 20, 200),
                scope(c.getString("like.limit", "room"), "global", "like.limit", log),
                scope(c.getString("visits.count-once-per", "day"), "ever", "visits.count-once-per", log));
    }

    /** 두 값 중 하나: 기본값이면 false, other 이면 true, 그 밖은 경고 후 기본값. */
    private static boolean scope(String raw, String other, String key, Logger log) {
        String v = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
        if (v.equals(other)) return true;
        if (!v.equals("room") && !v.equals("day")) log.warning(key + " 값 '" + raw + "' 을(를) 알 수 없어 기본값을 씁니다.");
        return false;
    }

    /** 지금 크기 다음 단계 (없으면 null). */
    Map.Entry<Integer, Long> next(int size) { return expansion.higherEntry(size); }
}
