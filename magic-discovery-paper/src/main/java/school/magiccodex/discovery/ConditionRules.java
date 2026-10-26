package school.magiccodex.discovery;

import java.util.*;

/**
 * 마법 발견 조건 v2 (2026-10-26) 의 순수 규칙 (Bukkit 없음, JUnit 대상).
 * LifeConditions·MagicDiscovery 가 이 판정만 부르고, 이벤트·월드 접근은 그쪽에서 한다.
 */
final class ConditionRules {
    private ConditionRules() {}

    /** "learned.<원소>" / "learned.all" 키 (배운 마법 수). */
    static final String LEARNED = "learned.";
    static final Set<String> ELEMENTS = Set.of("wind", "fire", "water", "earth", "light", "dark");

    static boolean learnedKey(String key) {
        if (key == null || !key.startsWith(LEARNED)) return false;
        String rest = key.substring(LEARNED.length());
        return rest.equals("all") || ELEMENTS.contains(rest);
    }

    /** 상태처럼 다루는 키 (누적 카운터가 아님): state.* 와 learned.*. */
    static boolean stateLike(String key) { return key.startsWith("state.") || key.startsWith(LEARNED); }

    /**
     * 배운 마법 수. categories = 마법 id → 원소, learned = 그 마법을 배웠는지. 결과 키는 learned.<원소> 와 learned.all.
     * 원소가 6원소가 아닌 마법은 learned.all 에만 들어간다.
     */
    static Map<String, Double> learnedCounts(Map<String, String> categories, java.util.function.Predicate<String> learned) {
        var out = new HashMap<String, Double>();
        for (String e : ELEMENTS) out.put(LEARNED + e, 0d);
        out.put(LEARNED + "all", 0d);
        for (var e : categories.entrySet()) {
            if (!learned.test(e.getKey())) continue;
            out.merge(LEARNED + "all", 1d, Double::sum);
            if (ELEMENTS.contains(e.getValue())) out.merge(LEARNED + e.getValue(), 1d, Double::sum);
        }
        return out;
    }

    /** 오버월드 밤 (13000~23000). */
    static boolean night(long time) { long t = Math.floorMod(time, 24000L); return t >= 13000 && t <= 23000; }

    /** 바이옴 이름(네임스페이스 뺀 키)으로 고른다: 데이터팩 바이옴도 이름이 같으면 같은 종류로 본다. */
    static boolean desert(String biomeKey) { return biomeKey != null && biomeKey.contains("desert"); }
    static boolean swamp(String biomeKey) { return biomeKey != null && biomeKey.contains("swamp"); }
    static boolean deepOcean(String biomeKey) { return biomeKey != null && biomeKey.contains("deep") && biomeKey.contains("ocean"); }

    /** 하늘이 보이는지: 플레이어 발 높이가 그 칸의 가장 높은 블록보다 낮지 않으면 하늘 아래. */
    static boolean underSky(int feetY, int highestBlockY) { return feetY >= highestBlockY; }

    /** 번개를 "가까이에서 본" 거리: 수평·수직 합쳐 32블록 안. */
    static boolean nearLightning(double distanceSquared) { return distanceSquared <= 32 * 32; }

    /** 이동 거리 누적: 소수점은 남겨 두고 정수 블록만 돌려준다. acc[0] = 남은 소수. */
    static int whole(double[] acc, double add) {
        if (!(add > 0) || add > 10) return 0; // 순간이동·탈것 튐은 버린다
        acc[0] += add; int n = (int) acc[0]; acc[0] -= n; return n;
    }

    /** 같은 행동을 짧은 시간에 여러 번 세지 않게: 마지막으로 센 틱에서 gap 틱이 지났는지. */
    static boolean cooled(Map<UUID, Integer> last, UUID id, int now, int gap) {
        Integer at = last.get(id);
        if (at != null && now - at < gap) return false;
        last.put(id, now); return true;
    }

    /** 되심기: 다 자란 작물을 거둔 자리 (플레이어마다 최근 32곳, 30초). */
    static final class Replant {
        private final Map<UUID, LinkedHashMap<Long, Integer>> harvested = new HashMap<>();
        static final int WINDOW = 600, LIMIT = 32;
        static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF); }
        void harvested(UUID player, long at, int tick) {
            var m = harvested.computeIfAbsent(player, k -> new LinkedHashMap<>());
            m.remove(at); m.put(at, tick);
            while (m.size() > LIMIT) { var it = m.keySet().iterator(); it.next(); it.remove(); }
        }
        /** 같은 자리에 30초 안에 다시 심으면 true (한 자리는 한 번만). */
        boolean replanted(UUID player, long at, int tick) {
            var m = harvested.get(player); if (m == null) return false;
            Integer when = m.remove(at);
            if (m.isEmpty()) harvested.remove(player);
            return when != null && tick - when <= WINDOW;
        }
        void quit(UUID player) { harvested.remove(player); }
        int size() { return harvested.values().stream().mapToInt(Map::size).sum(); }
    }
}
