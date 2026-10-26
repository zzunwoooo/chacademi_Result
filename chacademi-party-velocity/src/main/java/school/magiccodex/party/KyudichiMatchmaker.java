package school.magiccodex.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 큐디치 매칭 (오너 결정): 레이팅이 가까운 상대가 우선, 기다릴수록 범위가 넓어지고, 오래 기다리면 그냥 매칭.
 *  - 파티 기준 레이팅 = 파티원 중 가장 높은 레이팅
 *  - 범위: 0~30초 ±100, 30~60초 ±250, 60초부터 제한 없음 (두 팀 중 오래 기다린 쪽 기준)
 *  - 처음 60초: 3인 파티는 3인 파티끼리, 랜덤 팀은 랜덤 팀끼리. 이후 섞어서.
 *  - 1~2명 신청은 다른 1~2명과 묶어 랜덤 팀(3명)을 만든다.
 * Pure (no Velocity types): KyudichiMatchmakerTest.
 */
final class KyudichiMatchmaker {
    static final int TEAM_SIZE = 3;
    static final long NARROW_MS = 30_000, WIDE_MS = 60_000;

    /** One queue entry = a party (or one player). rating = max of its members. */
    record Entry(UUID id, List<UUID> members, int rating, long since) {
        boolean premade() { return members.size() == TEAM_SIZE; }
        boolean premade(int size) { return members.size() == size; }
    }
    record Team(List<Entry> parts, int rating, long since, boolean premade) {
        List<UUID> members() { var l = new ArrayList<UUID>(); for (Entry e : parts) l.addAll(e.members()); return l; }
        static Team of(List<Entry> parts) { return of(parts, TEAM_SIZE); }
        static Team of(List<Entry> parts, int size) {
            int r = parts.stream().mapToInt(Entry::rating).max().orElse(0);
            long since = parts.stream().mapToLong(Entry::since).min().orElse(0);
            return new Team(List.copyOf(parts), r, since, parts.size() == 1 && parts.get(0).premade(size));
        }
    }
    record Pair(Team a, Team b) {}

    static int range(long waitedMs) { return waitedMs < NARROW_MS ? 100 : waitedMs < WIDE_MS ? 250 : Integer.MAX_VALUE; }

    /** The first fair pair for the oldest waiting teams, or null. Never uses an entry twice. */
    static Pair find(List<Entry> queue, long now) { return find(queue, now, TEAM_SIZE); }

    /** `size` = players per team: 3 (큐디치) or 1 (1:1 결투, every entry is one player). */
    static Pair find(List<Entry> queue, long now, int size) {
        var sorted = new ArrayList<>(queue);
        sorted.sort(Comparator.comparingLong(Entry::since));
        var teams = new ArrayList<Team>();
        var used = new HashSet<UUID>();
        for (Entry e : sorted) if (e.premade(size)) { teams.add(Team.of(List.of(e), size)); used.add(e.id()); }
        // random teams from smaller entries, oldest first, closest rating within the oldest's range
        for (Entry e : sorted) {
            if (used.contains(e.id()) || e.members().size() >= size) continue;
            var parts = new ArrayList<Entry>(); parts.add(e);
            if (fill(parts, sorted, used, e, now, size)) { for (Entry p : parts) used.add(p.id()); teams.add(Team.of(parts, size)); }
        }
        teams.sort(Comparator.comparingLong(Team::since));
        for (int i = 0; i < teams.size(); i++) {
            Team t = teams.get(i); Team best = null; int bestGap = Integer.MAX_VALUE;
            for (int j = 0; j < teams.size(); j++) {
                if (i == j) continue;
                Team u = teams.get(j);
                long waited = now - Math.min(t.since(), u.since());
                if (waited < WIDE_MS && t.premade() != u.premade()) continue;
                int gap = Math.abs(t.rating() - u.rating());
                if (gap > range(waited) || gap >= bestGap) continue;
                if (overlap(t, u)) continue;
                best = u; bestGap = gap;
            }
            if (best != null) return new Pair(t, best);
        }
        return null;
    }

    private static boolean fill(List<Entry> parts, List<Entry> sorted, Set<UUID> used, Entry first, long now, int teamSize) {
        int size = first.members().size();
        int range = range(now - first.since());
        while (size < teamSize) {
            Entry pick = null; int gap = Integer.MAX_VALUE;
            for (Entry o : sorted) {
                if (used.contains(o.id()) || parts.contains(o) || o.members().size() + size > teamSize) continue;
                int g = Math.abs(o.rating() - first.rating());
                if (g <= range && g < gap) { pick = o; gap = g; }
            }
            if (pick == null) return false;
            parts.add(pick); size += pick.members().size();
        }
        return true;
    }

    private static boolean overlap(Team a, Team b) {
        var s = new HashSet<>(a.members());
        for (UUID u : b.members()) if (s.contains(u)) return true;
        return false;
    }
}
