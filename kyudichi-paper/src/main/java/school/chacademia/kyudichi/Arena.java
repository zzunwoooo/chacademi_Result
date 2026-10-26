package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** One arena's layout. Mutated only by admin commands on the main thread; a match reads it at start. */
final class Arena {
    final String name;
    boolean enabled, practice;
    /** 1:1 결투장 (owner 2026-10-18): needs only 경계 + 시작위치 파랑 1 / 빨강 1; 체력·마나 성소 후보는 선택. */
    boolean duel;
    Box bounds, ballArea, snitchArea;
    final Map<Team, List<Box>> goals = new EnumMap<>(Team.class);          // goals a team DEFENDS
    final Map<Team, Point[]> starts = new EnumMap<>(Team.class);           // 3 each
    final Map<Team, Point[]> respawns = new EnumMap<>(Team.class);         // optional, null = start
    final Map<Team, Point> prepare = new EnumMap<>(Team.class);            // optional
    Point quaffle;
    final Point[] bludgers = new Point[2];
    final List<Point> snitchSpawns = new ArrayList<>();
    final Map<ShrineType, List<Point>> shrines = new EnumMap<>(ShrineType.class);
    final List<Box> forbidden = new ArrayList<>();

    Arena(String name) {
        this.name = name;
        for (Team t : Team.values()) { goals.put(t, new ArrayList<>()); starts.put(t, new Point[3]); respawns.put(t, new Point[3]); }
        for (ShrineType s : ShrineType.values()) shrines.put(s, new ArrayList<>());
    }

    Box ballBox() { return ballArea != null ? ballArea : bounds; }
    Box snitchBox() { return snitchArea != null ? snitchArea : ballBox(); }
    String world() { return bounds == null ? null : bounds.world(); }

    Point respawn(Team t, int slot) {
        Point r = respawns.get(t)[slot];
        return r != null ? r : starts.get(t)[slot];
    }

    /** Missing required items, in Korean, empty = can be enabled. */
    List<String> problems() { return new ArrayList<>(fixes().keySet()); }

    /**
     * Missing required items in the order to fill them → the /큐디치관리 command that fixes each one
     * (검사 shows these lines; clicking one puts the command in the chat box).
     */
    java.util.LinkedHashMap<String, String> fixes() {
        var p = new java.util.LinkedHashMap<String, String>();
        String c = "/큐디치관리 ";
        if (bounds == null) p.put("경계", c + "경계");
        if (duel) {
            for (Team t : Team.values()) {
                Point s = starts.get(t)[0];
                if (s == null) p.put(t.word + " 시작위치 1", c + "시작위치 " + t.word);
                else if (bounds != null && (!s.world().equals(bounds.world()) || !bounds.contains(s.vector()))) p.put(t.word + " 시작위치가 경계 밖", c + "삭제 " + t.word + "시작위치 1");
            }
            return p;
        }
        for (Team t : Team.values()) if (goals.get(t).isEmpty()) p.put(t.word + " 골대", c + "골대 " + t.word);
        for (Team t : Team.values()) {
            Point[] s = starts.get(t);
            for (int i = 0; i < 3; i++) if (s[i] == null) p.put(t.word + " 시작위치 " + (i + 1), c + "시작위치 " + t.word);
        }
        if (quaffle == null) p.put("퀘이플위치", c + "퀘이플위치");
        for (int i = 0; i < 2; i++) if (bludgers[i] == null) p.put("블러저위치 " + (i + 1), c + "블러저위치");
        for (ShrineType s : ShrineType.values()) if (shrines.get(s).isEmpty()) p.put(s.label + " 성소 후보", c + "성소 " + s.label);
        if (bounds != null) {
            String w = bounds.world();
            if (ballArea != null && !ballArea.inside(bounds)) p.put("공영역이 경계 밖으로 나감", c + "공영역");
            if (snitchArea != null && !snitchArea.inside(bounds)) p.put("스니치영역이 경계 밖으로 나감", c + "스니치영역");
            for (Team t : Team.values()) {
                var g = goals.get(t);
                for (int i = 0; i < g.size(); i++) if (!g.get(i).world().equals(w)) { p.put(t.word + " 골대 " + (i + 1) + "이(가) 다른 월드", c + "삭제 " + t.word + "골대 " + (i + 1)); break; }
            }
            if (quaffle != null && !bounds.contains(quaffle.vector())) p.put("퀘이플위치가 경계 밖", c + "퀘이플위치");
            for (Team t : Team.values()) {
                Point[] s = starts.get(t);
                for (int i = 0; i < s.length; i++) if (s[i] != null && (!s[i].world().equals(w) || !bounds.contains(s[i].vector()))) {
                    p.put(t.word + " 시작위치 " + (i + 1) + "이(가) 경계 밖", c + "삭제 " + t.word + "시작위치 " + (i + 1)); break;
                }
            }
        }
        return p;
    }

    void save(ConfigurationSection s) {
        s.set("enabled", enabled); s.set("practice", practice); s.set("kind", duel ? "duel" : "kyudichi");
        s.set("bounds", bounds == null ? null : bounds.serialize());
        s.set("ball-area", ballArea == null ? null : ballArea.serialize());
        s.set("snitch-area", snitchArea == null ? null : snitchArea.serialize());
        for (Team t : Team.values()) {
            s.set("goals." + t.key, goals.get(t).stream().map(Box::serialize).toList());
            s.set("starts." + t.key, points(starts.get(t)));
            s.set("respawns." + t.key, points(respawns.get(t)));
            s.set("prepare." + t.key, prepare.get(t) == null ? null : prepare.get(t).serialize());
        }
        s.set("quaffle", quaffle == null ? null : quaffle.serialize());
        s.set("bludgers", points(bludgers));
        s.set("snitch-spawns", snitchSpawns.stream().map(Point::serialize).toList());
        for (ShrineType t : ShrineType.values()) s.set("shrines." + t.key, shrines.get(t).stream().map(Point::serialize).toList());
        s.set("forbidden", forbidden.stream().map(Box::serialize).toList());
    }

    static Arena load(String name, ConfigurationSection s) {
        var a = new Arena(name);
        a.enabled = s.getBoolean("enabled"); a.practice = s.getBoolean("practice"); a.duel = "duel".equals(s.getString("kind"));
        a.bounds = Box.parse(s.getString("bounds")); a.ballArea = Box.parse(s.getString("ball-area")); a.snitchArea = Box.parse(s.getString("snitch-area"));
        for (Team t : Team.values()) {
            for (String g : s.getStringList("goals." + t.key)) { Box b = Box.parse(g); if (b != null) a.goals.get(t).add(b); }
            read(s.getStringList("starts." + t.key), a.starts.get(t));
            read(s.getStringList("respawns." + t.key), a.respawns.get(t));
            Point p = Point.parse(s.getString("prepare." + t.key)); if (p != null) a.prepare.put(t, p);
        }
        a.quaffle = Point.parse(s.getString("quaffle"));
        read(s.getStringList("bludgers"), a.bludgers);
        for (String p : s.getStringList("snitch-spawns")) { Point v = Point.parse(p); if (v != null) a.snitchSpawns.add(v); }
        for (ShrineType t : ShrineType.values()) for (String p : s.getStringList("shrines." + t.key)) { Point v = Point.parse(p); if (v != null) a.shrines.get(t).add(v); }
        for (String f : s.getStringList("forbidden")) { Box b = Box.parse(f); if (b != null) a.forbidden.add(b); }
        if (!a.problems().isEmpty()) a.enabled = false;
        return a;
    }

    private static List<String> points(Point[] ps) {
        var out = new ArrayList<String>();
        for (Point p : ps) out.add(p == null ? "" : p.serialize());
        return out;
    }
    private static void read(List<String> in, Point[] out) {
        for (int i = 0; i < out.length && i < in.size(); i++) out[i] = Point.parse(in.get(i).isEmpty() ? null : in.get(i));
    }
}
