package school.magiccodex.paper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import school.magiccodex.protocol.TravelProtocol;

/**
 * 지도 이동의 설정과 판정 (2026-10-23). No Bukkit classes here: travel.yml is parsed from plain maps and every rule
 * (which tab a point is on, can it be used now and why not, did the player step into it) is unit tested.
 *
 * Ids: a "server" is the logical server id saved in the DB (school, wild, wild2 ...) with the Velocity server name it
 * maps to; a point's location is always (server, world, x, y, z) so the same coordinates on two wilds never mix.
 */
final class TravelModel {
    private TravelModel() {}

    /** closedText: shown instead of the generic reason while enabled = false (e.g. 야생2 "준비 중입니다"). */
    record ServerDef(String id, String proxy, String label, boolean enabled, String closedText) {
        String closedReason() { return closedText.isBlank() ? label + " 서버는 지금 이동할 수 없어요" : label + " · " + closedText; }
    }
    /** One map tab. map: none / image / terrain. Image corners: world x,z of the image's top-left, top-right, bottom-left. */
    record TabDef(String id, String label, String server, String world, int map, String texture, int imageW, int imageH,
                  double[] corners, double[] bounds) {}
    /**
     * spawn = the destination is that world's spawn (Multiverse setspawn), read when the player arrives; always open (no
     * discovery, no portal in the world, not counted for 공간 도약) and no travel cooldown. from = servers a player may
     * leave from to use it (empty = any).
     */
    record PointDef(String id, String name, String server, String world, double x, double y, double z, float yaw, float pitch,
                    double radius, boolean enabled, boolean spawn, List<String> from) {
        PointDef(String id, String name, String server, String world, double x, double y, double z, float yaw, float pitch, double radius, boolean enabled) {
            this(id, name, server, world, x, y, z, yaw, pitch, radius, enabled, false, List.of());
        }
    }
    /** personalMana / personalCooldownSeconds: moving to the personal point (owner 2026-10-24: 지정은 무료, 이동만 마나·쿨). */
    record Rules(boolean blockInCombat, int cooldownSeconds, double personalMana, int personalCooldownSeconds) {
        Rules(boolean blockInCombat, int cooldownSeconds) { this(blockInCombat, cooldownSeconds, 100, 60); }
    }
    record Config(String self, Map<String, ServerDef> servers, List<TabDef> tabs, Map<String, PointDef> points, Rules rules,
                  boolean revealLockedNames, boolean allowLocalDb, String hash) {
        ServerDef server(String id) { return servers.get(id); }
        TabDef tab(String server, String world) {
            for (TabDef t : tabs) if (t.server().equals(server) && t.world().equals(world)) return t;
            return null;
        }
        List<PointDef> localPoints(String world) {
            var out = new ArrayList<PointDef>();
            for (PointDef p : points.values()) if (p.enabled() && !p.spawn() && p.server().equals(self) && p.world().equals(world)) out.add(p);
            return out;
        }
    }

    /** Saved personal point (one per player, every server). worldUid = the world folder's UID when it was set ("" unknown). */
    record Personal(String server, String world, String worldUid, double x, double y, double z, float yaw, float pitch, long at, String source) {}
    /** What a server last published (heartbeat). worlds: world name -> UID. */
    /** spawns: world name -> spawn x, y, z (for drawing spawn points on the map). */
    record ServerStatus(String server, long heartbeat, int online, int max, Map<String, String> worlds, String configHash, Map<String, double[]> spawns) {
        ServerStatus(String server, long heartbeat, int online, int max, Map<String, String> worlds, String configHash) { this(server, heartbeat, online, max, worlds, configHash, Map.of()); }
        boolean alive(long now) { return now - heartbeat <= STALE_MS; }
    }
    /** "name=uid,x,y,z;..." (heartbeat column) <-> maps. */
    static String packWorlds(Map<String, String> uids, Map<String, double[]> spawns) {
        var sb = new StringBuilder();
        uids.forEach((w, uid) -> {
            double[] s = spawns.get(w);
            String part = w + "=" + uid + (s == null ? "" : "," + Math.round(s[0] * 10) / 10.0 + "," + Math.round(s[1] * 10) / 10.0 + "," + Math.round(s[2] * 10) / 10.0) + ";";
            if (sb.length() + part.length() <= 2000) sb.append(part);
        });
        return sb.toString();
    }
    static void unpackWorlds(String packed, Map<String, String> uids, Map<String, double[]> spawns) {
        for (String part : packed.split(";")) {
            int eq = part.indexOf('='); if (eq <= 0) continue;
            String[] v = part.substring(eq + 1).split(",");
            uids.put(part.substring(0, eq), v[0]);
            if (v.length == 4) try { spawns.put(part.substring(0, eq), new double[]{Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3])}); } catch (NumberFormatException ignored) {}
        }
    }
    static final long STALE_MS = 45_000;

    record Verdict(int state, String reason) {
        static final Verdict OK = new Verdict(TravelProtocol.UNLOCKED, "");
        static Verdict no(String why) { return new Verdict(TravelProtocol.UNAVAILABLE, why); }
    }

    // ------------------------------------------------------------------ rules

    /**
     * Can the destination (server, world, worldUid) take a player now? self = this server (always alive and its worlds
     * known directly through `localWorlds`). Never says OK for an unknown, disabled, silent or full server.
     */
    static Verdict destination(Config c, String server, String world, String worldUid, Map<String, ServerStatus> status,
                               Map<String, String> localWorlds, long now) {
        ServerDef def = c.server(server);
        if (def == null) return Verdict.no("지도에서 빠진 서버예요 (" + server + ")");
        if (!def.enabled()) return Verdict.no(def.closedReason());
        Map<String, String> worlds;
        if (server.equals(c.self())) worlds = localWorlds;
        else {
            ServerStatus s = status.get(server);
            if (s == null || !s.alive(now)) return Verdict.no(def.label() + " 서버가 닫혀 있어요");
            if (s.max() > 0 && s.online() >= s.max()) return Verdict.no(def.label() + " 서버 정원이 가득 찼어요");
            worlds = s.worlds();
        }
        String uid = worlds.get(world);
        if (uid == null) return Verdict.no("그 세계가 지금 없어요");
        if (worldUid != null && !worldUid.isEmpty() && !worldUid.equals(uid)) return Verdict.no("세계가 초기화되어 그 지점은 쓸 수 없어요");
        return Verdict.OK;
    }

    static Verdict publicPoint(Config c, PointDef p, boolean unlocked, Map<String, ServerStatus> status, Map<String, String> localWorlds, long now) {
        if (!unlocked && !p.spawn()) return new Verdict(TravelProtocol.LOCKED, "아직 발견하지 못한 지점이에요");
        if (!p.enabled()) return Verdict.no("지금은 닫힌 지점이에요");
        if (!p.from().isEmpty() && !p.from().contains(c.self())) {
            var names = new ArrayList<String>();
            for (String f : p.from()) { var d = c.server(f); names.add(d == null ? f : d.label()); }
            return Verdict.no(String.join("·", names) + "에서만 갈 수 있어요");
        }
        return destination(c, p.server(), p.world(), "", status, localWorlds, now);
    }

    static Verdict personal(Config c, Personal p, Map<String, ServerStatus> status, Map<String, String> localWorlds, long now) {
        if (c.tab(p.server(), p.world()) == null && c.server(p.server()) != null && c.server(p.server()).enabled())
            return Verdict.no("지도에 없는 세계예요");
        return destination(c, p.server(), p.world(), p.worldUid(), status, localWorlds, now);
    }

    /** Stepping into a point: inside the radius horizontally and close in height (a cliff above does not count). */
    static boolean inside(PointDef p, double x, double y, double z) {
        double dx = x - p.x(), dz = z - p.z(), dy = y - p.y();
        return dx * dx + dz * dz <= p.radius() * p.radius() && dy > -2.5 && dy < Math.max(3, p.radius());
    }

    /** Cooldown left in ms (0 = none or free). */
    static long cooldownLeft(Rules r, long lastTravel, long now) {
        if (r.cooldownSeconds() <= 0 || lastTravel <= 0) return 0;
        return Math.max(0, lastTravel + r.cooldownSeconds() * 1000L - now);
    }

    // ------------------------------------------------------------------ travel.yml

    static final String ID = "[a-z0-9_\\-]{1,48}";

    /** Parses travel.yml (already loaded as nested maps). Throws IllegalArgumentException with a Korean message for the admin. */
    @SuppressWarnings("unchecked")
    static Config parse(Map<String, Object> root) {
        String self = str(root.get("server-id"), "");
        if (!self.matches(ID)) throw new IllegalArgumentException("travel.yml server-id 는 이 서버의 서버 ID (영문 소문자·숫자·_·-): " + self);
        var servers = new LinkedHashMap<String, ServerDef>();
        for (var e : section(root, "servers").entrySet()) {
            String id = e.getKey();
            if (!id.matches(ID)) throw new IllegalArgumentException("servers." + id + ": 서버 ID 형식 (영문 소문자·숫자·_·-)");
            var m = map(e.getValue(), "servers." + id);
            String proxy = str(m.get("proxy"), id);
            if (!proxy.matches("[A-Za-z0-9_\\-]{1,48}")) throw new IllegalArgumentException("servers." + id + ".proxy 는 Velocity 서버 이름");
            String closed = str(m.get("closed-text"), "");
            if (closed.length() > 60) throw new IllegalArgumentException("servers." + id + ".closed-text 는 60자까지");
            servers.put(id, new ServerDef(id, proxy, label(m.get("label"), id, "servers." + id), bool(m.get("enabled"), true), closed));
        }
        if (!servers.containsKey(self)) throw new IllegalArgumentException("travel.yml servers 에 이 서버(" + self + ")가 없어요");
        var tabs = new ArrayList<TabDef>();
        var tabIds = new HashSet<String>();
        for (var e : section(root, "tabs").entrySet()) {
            String id = e.getKey(), at = "tabs." + id;
            if (!id.matches(ID) || !tabIds.add(id)) throw new IllegalArgumentException(at + ": 탭 ID 형식 또는 중복");
            var m = map(e.getValue(), at);
            String server = str(m.get("server"), "");
            if (!servers.containsKey(server)) throw new IllegalArgumentException(at + ".server 가 servers 에 없어요: " + server);
            String world = str(m.get("world"), "world");
            if (!TravelProtocol.validWorld(world)) throw new IllegalArgumentException(at + ".world 형식");
            String kind = str(m.get("map"), "none");
            int map = switch (kind) { case "image" -> TravelProtocol.MAP_IMAGE; case "terrain" -> TravelProtocol.MAP_TERRAIN; case "none" -> TravelProtocol.MAP_NONE;
                default -> throw new IllegalArgumentException(at + ".map 은 image / terrain / none"); };
            String texture = ""; int w = 0, h = 0; double[] corners = new double[6];
            if (map == TravelProtocol.MAP_IMAGE) {
                var img = map(m.get("image"), at + ".image");
                texture = str(img.get("texture"), "");
                if (!texture.matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+\\.png") || texture.length() > TravelProtocol.MAX_TEXTURE)
                    throw new IllegalArgumentException(at + ".image.texture 는 리소스 경로 (예: magiccodex:textures/gui/travel/maps/school.png)");
                List<Object> size = list(img.get("size"), at + ".image.size");
                if (size.size() != 2) throw new IllegalArgumentException(at + ".image.size 는 [가로, 세로] 픽셀");
                w = (int) num(size.get(0), at + ".image.size"); h = (int) num(size.get(1), at + ".image.size");
                if (w < 1 || h < 1 || w > 16384 || h > 16384) throw new IllegalArgumentException(at + ".image.size 범위");
                String[] keys = {"top-left", "top-right", "bottom-left"};
                for (int k = 0; k < 3; k++) {
                    List<Object> xz = list(img.get(keys[k]), at + ".image." + keys[k]);
                    if (xz.size() != 2) throw new IllegalArgumentException(at + ".image." + keys[k] + " 는 [x, z]");
                    corners[k * 2] = num(xz.get(0), at); corners[k * 2 + 1] = num(xz.get(1), at);
                }
                double ux = corners[2] - corners[0], uz = corners[3] - corners[1], vx = corners[4] - corners[0], vz = corners[5] - corners[1];
                if (Math.abs(ux * vz - uz * vx) < 1e-6) throw new IllegalArgumentException(at + ".image 기준점 3개가 한 줄 위에 있어요");
            }
            double[] bounds = {-30_000_000, -30_000_000, 30_000_000, 30_000_000};
            if (m.get("bounds") != null) {
                var b = map(m.get("bounds"), at + ".bounds");
                bounds = new double[]{num(b.get("min-x"), at + ".bounds.min-x"), num(b.get("min-z"), at + ".bounds.min-z"), num(b.get("max-x"), at + ".bounds.max-x"), num(b.get("max-z"), at + ".bounds.max-z")};
                if (bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) throw new IllegalArgumentException(at + ".bounds 는 min < max");
            }
            tabs.add(new TabDef(id, label(m.get("label"), id, at), server, world, map, texture, w, h, corners, bounds));
        }
        if (tabs.isEmpty() || tabs.size() > TravelProtocol.MAX_TABS) throw new IllegalArgumentException("tabs 는 1~" + TravelProtocol.MAX_TABS + "개");
        var seen = new HashSet<String>();
        for (TabDef t : tabs) if (!seen.add(t.server() + "/" + t.world())) throw new IllegalArgumentException("같은 서버·세계를 두 탭이 보여요: " + t.server() + "/" + t.world());
        var points = new LinkedHashMap<String, PointDef>();
        for (var e : section(root, "points").entrySet()) {
            String id = e.getKey(), at = "points." + id;
            if (!id.matches(ID)) throw new IllegalArgumentException(at + ": 지점 ID 는 영문 소문자·숫자·_·- 1~48자");
            var m = map(e.getValue(), at);
            String server = str(m.get("server"), "");
            if (!servers.containsKey(server)) throw new IllegalArgumentException(at + ".server 가 servers 에 없어요: " + server);
            String world = str(m.get("world"), "world");
            if (!TravelProtocol.validWorld(world)) throw new IllegalArgumentException(at + ".world 형식");
            double radius = m.get("radius") == null ? 3 : num(m.get("radius"), at + ".radius");
            if (!(radius >= 1 && radius <= 32)) throw new IllegalArgumentException(at + ".radius 는 1~32");
            boolean spawn = bool(m.get("spawn"), false);
            // a spawn point's real place is the world spawn when the player arrives; x/y/z are optional (map position until the server publishes it)
            double x = spawn && m.get("x") == null ? 0 : num(m.get("x"), at + ".x"), y = spawn && m.get("y") == null ? 64 : num(m.get("y"), at + ".y"),
                    z = spawn && m.get("z") == null ? 0 : num(m.get("z"), at + ".z");
            float yaw = m.get("yaw") == null ? 0 : (float) num(m.get("yaw"), at + ".yaw"), pitch = m.get("pitch") == null ? 0 : (float) num(m.get("pitch"), at + ".pitch");
            var from = new ArrayList<String>();
            if (m.get("from") != null) for (Object f : list(m.get("from"), at + ".from")) {
                String sid = String.valueOf(f).strip();
                if (!servers.containsKey(sid)) throw new IllegalArgumentException(at + ".from 의 서버가 servers 에 없어요: " + sid);
                from.add(sid);
            }
            points.put(id, new PointDef(id, label(m.get("name"), null, at + ".name"), server, world, x, y, z, yaw, pitch, radius, bool(m.get("enabled"), true), spawn, List.copyOf(from)));
        }
        if (points.size() > TravelProtocol.MAX_POINTS - 1) throw new IllegalArgumentException("지점은 " + (TravelProtocol.MAX_POINTS - 1) + "개까지");
        var rules = map(root.getOrDefault("rules", Map.of()), "rules");
        int cooldown = rules.get("cooldown-seconds") == null ? 0 : (int) num(rules.get("cooldown-seconds"), "rules.cooldown-seconds");
        if (cooldown < 0 || cooldown > 86400) throw new IllegalArgumentException("rules.cooldown-seconds 는 0~86400");
        double personalMana = rules.get("personal-travel-mana") == null ? 100 : num(rules.get("personal-travel-mana"), "rules.personal-travel-mana");
        int personalCooldown = rules.get("personal-travel-cooldown-seconds") == null ? 60 : (int) num(rules.get("personal-travel-cooldown-seconds"), "rules.personal-travel-cooldown-seconds");
        if (personalMana < 0 || personalMana > 100_000) throw new IllegalArgumentException("rules.personal-travel-mana 는 0~100000");
        if (personalCooldown < 0 || personalCooldown > 86400) throw new IllegalArgumentException("rules.personal-travel-cooldown-seconds 는 0~86400");
        var display = map(root.getOrDefault("display", Map.of()), "display");
        var storage = map(root.getOrDefault("storage", Map.of()), "storage");
        var copy = new LinkedHashMap<>(root); copy.remove("server-id");
        return new Config(self, Collections.unmodifiableMap(servers), List.copyOf(tabs), Collections.unmodifiableMap(points),
                new Rules(bool(rules.get("block-in-combat"), false), cooldown, personalMana, personalCooldown), bool(display.get("reveal-locked-names"), false),
                bool(storage.get("allow-local-test-db"), false), hash(canonical(copy)));
    }

    /** Same text for the same settings whatever the key order (server-id excluded): servers compare it to spot a travel.yml that differs. */
    static String canonical(Object o) {
        if (o instanceof Map<?, ?> m) {
            var keys = new TreeMap<String, Object>();
            m.forEach((k, v) -> keys.put(String.valueOf(k), v));
            var sb = new StringBuilder("{");
            keys.forEach((k, v) -> sb.append(k).append('=').append(canonical(v)).append(';'));
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) { var sb = new StringBuilder("["); for (Object v : l) sb.append(canonical(v)).append(','); return sb.append(']').toString(); }
        if (o instanceof Number n) return Double.toString(n.doubleValue());
        return String.valueOf(o);
    }
    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(); for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v == null) return Map.of();
        return map(v, key);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v, String at) {
        if (v instanceof Map<?, ?> m) { var out = new LinkedHashMap<String, Object>(); m.forEach((k, x) -> out.put(String.valueOf(k), x)); return out; }
        throw new IllegalArgumentException(at + " 형식 확인 (항목 묶음이어야 해요)");
    }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v, String at) {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException(at + " 형식 확인 ([a, b] 목록)");
    }
    private static String str(Object v, String def) { return v == null ? def : String.valueOf(v).strip(); }
    private static boolean bool(Object v, boolean def) { return v == null ? def : v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v)); }
    private static double num(Object v, String at) {
        if (v instanceof Number n && Double.isFinite(n.doubleValue()) && Math.abs(n.doubleValue()) <= 3.0E7) return n.doubleValue();
        try { double d = Double.parseDouble(String.valueOf(v)); if (Double.isFinite(d) && Math.abs(d) <= 3.0E7) return d; } catch (RuntimeException ignored) {}
        throw new IllegalArgumentException(at + " 은 숫자여야 해요: " + v);
    }
    private static String label(Object v, String def, String at) {
        String s = v == null ? def : String.valueOf(v).strip();
        if (s == null || s.isEmpty() || s.length() > TravelProtocol.MAX_NAME) throw new IllegalArgumentException(at + " 이름은 1~" + TravelProtocol.MAX_NAME + "자");
        return s;
    }
}
