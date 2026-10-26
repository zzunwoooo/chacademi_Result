package school.magiccodex.paper;

import java.util.*;

/**
 * 리턴권·텔포권 (오너 2026-10-26) 순수 규칙 — Bukkit 없이 시험할 수 있는 부분만:
 * 설정 정리, 접속자 목록(공유 DB 한 줄 = 서버 하나) 묶기·풀기, 텔포권 후보 고르기.
 */
final class TravelItemRules {
    private TravelItemRules() {}

    /** 공유 DB codex_travel_presence 한 줄이 이만큼 새로 쓰이지 않으면 그 서버는 꺼진 것으로 본다 (서버 상태는 15초마다 씀). */
    static final long PRESENCE_ALIVE_MS = 45_000;
    static final int MAX_PRESENCE = 500;

    /** travel-items.yml 내용. 서버 ID 는 travel.yml server-id (소문자). */
    record Settings(String deathItem, String deathName, String friendItem, String friendName,
                    Set<String> useServers, Set<String> deathServers, Set<String> listServers) {
        static final Settings DEFAULT = new Settings("item:death_return", "리턴권", "item:friend_teleport", "텔포권",
                Set.of("school", "wild1", "wild2"), Set.of("wild1", "wild2"), Set.of("school", "wild1", "wild2"));
        boolean usableOn(String server) { return server != null && useServers.contains(server); }
        boolean recordsDeathOn(String server) { return server != null && deathServers.contains(server); }
        boolean listed(String server) { return server != null && listServers.contains(server); }
    }

    static Settings settings(String deathItem, String deathName, String friendItem, String friendName,
                             Collection<String> use, Collection<String> deaths, Collection<String> list) {
        var d = Settings.DEFAULT;
        return new Settings(item(deathItem, d.deathItem()), name(deathName, d.deathName()), item(friendItem, d.friendItem()), name(friendName, d.friendName()),
                ids(use, d.useServers()), ids(deaths, d.deathServers()), ids(list, d.listServers()));
    }
    /** "namespace:id" 만 (아니면 기본값). */
    static String item(String s, String fallback) {
        String t = s == null ? "" : s.strip();
        return t.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? t : fallback;
    }
    static String name(String s, String fallback) {
        String t = s == null ? "" : s.strip();
        return t.isEmpty() || t.length() > 30 ? fallback : t;
    }
    /** 서버 ID 목록 (소문자, travel.yml 형식이 아닌 것은 버림). 목록이 아예 없으면(null) 기본값, 빈 목록이면 빈 집합(= 꺼짐). */
    static Set<String> ids(Collection<String> in, Set<String> fallback) {
        if (in == null) return fallback;
        var out = new HashSet<String>();
        for (String s : in) if (s != null && s.strip().toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,48}")) out.add(s.strip().toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------ 접속자 (서버별 한 줄)

    /** 한 서버의 접속자 한 명: 세계 이름(텔포권 표의 세계), 한글 닉네임, 기숙사 이름. */
    record Online(UUID id, String world, String name, String dormitory) {}
    /** 공유 DB 한 줄. */
    record Presence(String server, long at, List<Online> players) {
        boolean alive(long now) { return at > 0 && now - at <= PRESENCE_ALIVE_MS && at - now <= PRESENCE_ALIVE_MS; }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 줄마다 "uuid \t 세계 \t 닉네임 \t 기숙사". 최대 {@link #MAX_PRESENCE}명. */
    static String pack(List<Online> list) {
        var b = new StringBuilder();
        int n = 0;
        for (var o : list) {
            if (o == null || o.id() == null || n++ >= MAX_PRESENCE) continue;
            b.append(o.id()).append('\t').append(clean(o.world(), 64)).append('\t').append(clean(o.name(), 32)).append('\t').append(clean(o.dormitory(), 16)).append('\n');
        }
        return b.toString();
    }
    /** {@link #pack} 를 거꾸로. 깨진 줄은 건너뛴다. */
    static List<Online> unpack(String s) {
        if (s == null || s.isEmpty()) return List.of();
        var out = new ArrayList<Online>();
        for (String line : s.split("\n")) {
            if (out.size() >= MAX_PRESENCE) break;
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            try { out.add(new Online(UUID.fromString(f[0]), f[1], f[2], f[3])); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    /** 텔포권 후보 한 명 (어느 서버에 있는지까지). */
    record Candidate(UUID id, String server, String world, String name, String dormitory) {}

    /**
     * 살아 있는 서버 줄 중 목록에 넣는 서버(학교·야생, 아레나는 빠짐)의 접속자만, 자기 자신 빼고, 한 사람은 한 번만
     * (서버 옮기는 중 두 줄에 있으면 더 최근에 쓴 서버), 닉네임 순.
     */
    static List<Candidate> candidates(Collection<Presence> rows, Settings s, UUID self, long now) {
        var best = new HashMap<UUID, Candidate>();
        var at = new HashMap<UUID, Long>();
        for (var r : rows) {
            if (r == null || !r.alive(now) || !s.listed(r.server())) continue;
            for (var o : r.players()) {
                if (o.id().equals(self)) continue;
                Long prev = at.get(o.id());
                if (prev != null && prev >= r.at()) continue;
                at.put(o.id(), r.at());
                best.put(o.id(), new Candidate(o.id(), r.server(), o.world(), o.name().isBlank() ? "?" : o.name(), o.dormitory()));
            }
        }
        var out = new ArrayList<>(best.values());
        out.sort(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER).thenComparing(c -> c.id().toString()));
        return out;
    }

    // ------------------------------------------------------------------ 리턴권 사망 자리

    /** 플레이어별 마지막 야생 사망 자리 (공유 DB codex_travel_death). */
    record DeathPoint(String server, String world, String worldUid, double x, double y, double z, float yaw, float pitch, long at) {}

    /**
     * 공허·세계 바닥 아래에서 죽었으면 그 자리 대신 같은 x z 의 땅 위(highest+1)로. 세계 천장 위면 천장 바로 아래로.
     * highest = 죽은 순간 그 열의 가장 높은 블록 y (그 청크는 플레이어가 있던 곳이라 이미 불려 있음).
     * 그 열에 디딜 블록이 아예 없으면(공허 열) NaN = 이번 사망은 적지 않음 (가 봐야 또 떨어짐, 이전 기록 유지).
     */
    static double safeY(double y, int minHeight, int maxHeight, int highest) {
        if (!Double.isFinite(y) || y < minHeight) return highest < minHeight ? Double.NaN : highest + 1;
        if (y > maxHeight) return maxHeight - 1;
        return y;
    }
}
