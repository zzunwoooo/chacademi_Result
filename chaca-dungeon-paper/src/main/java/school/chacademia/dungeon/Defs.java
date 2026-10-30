package school.chacademia.dungeon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * dungeons.yml 의 모양. 위치는 "월드,x,y,z,yaw,pitch" 한 줄, 구역은 "x,y,z" 두 모서리.
 * 칸(slot) = 같은 던전의 독립 인스턴스 하나 (같은 월드 안에 오너가 맵을 복사해 둔 자리). 칸마다 따로 설정한다.
 */
final class Defs {
    private Defs() {}

    record Loc(String world, double x, double y, double z, float yaw, float pitch) {
        static Loc of(Location l) { return new Loc(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }
        static Loc parse(String s) {
            if (s == null || s.isBlank()) return null;
            String[] p = s.split(",");
            if (p.length < 4) return null;
            try {
                return new Loc(p[0].trim(), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
            } catch (NumberFormatException e) { return null; }
        }
        String text() { return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.1f,%.1f", world, x, y, z, yaw, pitch); }
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
        String shortText() { return String.format(Locale.ROOT, "%s %.0f %.0f %.0f", world, x, y, z); }
    }

    /** 블록 좌표 상자 (양 끝 포함). */
    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Box of(int[] a, int[] b) {
            return new Box(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
        }
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }
        boolean contains(Location l) { return contains(l.getX(), l.getY(), l.getZ()); }
        org.bukkit.util.BoundingBox bounds() { return new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1); }
        String text() { return minX + "," + minY + "," + minZ + " ~ " + maxX + "," + maxY + "," + maxZ; }
    }

    static int[] block(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())}; }
        catch (NumberFormatException e) { return null; }
    }

    static String block(int[] b) { return b == null ? "" : b[0] + "," + b[1] + "," + b[2]; }

    static final class Spawn {
        String mob; int count; Loc at;
        Spawn(String mob, int count, Loc at) { this.mob = mob; this.count = count; this.at = at; }
    }

    static final class Room {
        final String name;
        int[] corner1, corner2;
        boolean lock = true;
        final List<Spawn> spawns = new ArrayList<>();
        Room(String name) { this.name = name; }
        Box box() { return corner1 == null || corner2 == null ? null : Box.of(corner1, corner2); }
    }

    static final class Boss {
        String mob; Loc at;
        Boss(String mob, Loc at) { this.mob = mob; this.at = at; }
    }

    static final class Slot {
        final int no;
        Loc start;
        int[] corner1, corner2;
        final Map<String, Room> rooms = new LinkedHashMap<>();
        final Map<String, Boss> bosses = new LinkedHashMap<>();
        final Map<String, Loc> points = new LinkedHashMap<>();
        final Map<String, int[]> gimmicks = new LinkedHashMap<>();
        Slot(int no) { this.no = no; }
        Box region() { return corner1 == null || corner2 == null ? null : Box.of(corner1, corner2); }
    }

    static final class Stage {
        String name = "", goal = "", type = "room", target = "";
        int count = 1;
        double radius = 3;
        List<String> startCommands = List.of();
    }

    /**
     * 보상 한 줄: ItemsAdder/바닐라 id + 수량, 또는 손에 든 아이템 그대로(data = Base64 serializeAsBytes).
     * chance = 확률 보상의 당첨 확률(%), 다른 묶음은 0(항상).
     */
    record Reward(String item, int amount, String data, double chance) {}

    static final class Dungeon {
        final String id;
        String name = "", subtitle = "", world = "", permission = "", lockMessage = "";
        String ticketItem = "", portalLabel = "";
        int ticketAmount = 1, instances = 3;
        boolean freeUntilFirstClear = true;
        /** 던전 안에서 배고픔으로 체력이 저절로 차는지 (마나는 상관없음). 기본 false = 자동 회복 없음 (오너 2026-10-30). */
        boolean naturalRegen;
        Loc portal, exit;
        double portalRadius = 2.5;
        List<String> clearCommands = new ArrayList<>();
        /** 기본 = 클리어마다, 첫 클리어 = 처음 한 번 더, 확률 = 클리어마다 줄마다 chance% 로 따로 뽑음. */
        final List<Reward> baseRewards = new ArrayList<>(), firstRewards = new ArrayList<>(), randomRewards = new ArrayList<>();
        final List<Stage> stages = new ArrayList<>();
        final Map<Integer, Slot> slots = new TreeMap<>();
        Dungeon(String id) { this.id = id; }
        Slot slot(int no) { return slots.computeIfAbsent(no, Slot::new); }
    }

    // ------------------------------------------------------------ 읽기

    static Map<String, Dungeon> read(ConfigurationSection root, List<String> warnings) {
        var out = new LinkedHashMap<String, Dungeon>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null || !id.matches("[a-z0-9_]{1,32}")) { warnings.add("던전 ID 무시: " + id + " (영문 소문자·숫자·_)"); continue; }
            var d = new Dungeon(id);
            d.name = s.getString("name", id);
            d.subtitle = s.getString("subtitle", "");
            d.world = s.getString("world", "");
            d.permission = s.getString("permission", "");
            d.lockMessage = s.getString("lock-message", "아직 들어갈 수 없어요.");
            d.ticketItem = s.getString("ticket.item", "");
            d.ticketAmount = Math.max(0, s.getInt("ticket.amount", 1));
            d.freeUntilFirstClear = s.getBoolean("ticket.free-until-first-clear", true);
            d.instances = Math.max(1, Math.min(16, s.getInt("instances", 3)));
            d.portal = Loc.parse(s.getString("portal", ""));
            d.portalRadius = Math.max(0.5, s.getDouble("portal-radius", 2.5));
            d.portalLabel = s.getString("portal-label", d.name);
            d.exit = Loc.parse(s.getString("exit", ""));
            d.clearCommands = new ArrayList<>(s.getStringList("clear-commands"));
            readRewards(s.getMapList("rewards.first"), d.firstRewards);
            // 기본 보상: 예전 이름 repeat 도 읽는다 (저장하면 base 로 바뀜)
            readRewards(s.getMapList(s.contains("rewards.base") ? "rewards.base" : "rewards.repeat"), d.baseRewards);
            readRewards(s.getMapList("rewards.random"), d.randomRewards);
            d.naturalRegen = s.getBoolean("natural-regen", false);
            for (var m : s.getMapList("stages")) {
                var st = new Stage();
                st.name = str(m.get("name"));
                st.goal = str(m.get("goal"));
                st.type = str(m.get("type")).toLowerCase(Locale.ROOT);
                st.target = str(m.get("target"));
                st.count = m.get("count") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                st.radius = m.get("radius") instanceof Number n ? Math.max(0.5, n.doubleValue()) : 3;
                if (m.get("start-commands") instanceof List<?> l) st.startCommands = l.stream().map(String::valueOf).toList();
                if (!List.of("room", "kill", "boss", "gimmick", "reach").contains(st.type)) {
                    warnings.add(id + " 단계 '" + st.name + "': 모르는 종류 " + st.type + " → 건너뜀");
                    continue;
                }
                d.stages.add(st);
            }
            var slots = s.getConfigurationSection("slots");
            if (slots != null) for (String key : slots.getKeys(false)) {
                int no;
                try { no = Integer.parseInt(key); } catch (NumberFormatException e) { warnings.add(id + " 칸 번호 무시: " + key); continue; }
                var c = slots.getConfigurationSection(key);
                if (c == null) continue;
                var slot = d.slot(no);
                slot.start = Loc.parse(c.getString("start", ""));
                slot.corner1 = block(c.getString("region.1", ""));
                slot.corner2 = block(c.getString("region.2", ""));
                var rooms = c.getConfigurationSection("rooms");
                if (rooms != null) for (String rn : rooms.getKeys(false)) {
                    var r = rooms.getConfigurationSection(rn);
                    if (r == null) continue;
                    var room = new Room(rn);
                    room.corner1 = block(r.getString("1", ""));
                    room.corner2 = block(r.getString("2", ""));
                    room.lock = r.getBoolean("lock", true);
                    for (var m : r.getMapList("spawns")) {
                        Loc at = Loc.parse(str(m.get("at")));
                        String mob = str(m.get("mob"));
                        int count = m.get("count") instanceof Number n ? Math.max(1, Math.min(50, n.intValue())) : 1;
                        if (at != null && !mob.isBlank()) room.spawns.add(new Spawn(mob, count, at));
                    }
                    slot.rooms.put(rn, room);
                }
                var bosses = c.getConfigurationSection("bosses");
                if (bosses != null) for (String bn : bosses.getKeys(false)) {
                    Loc at = Loc.parse(bosses.getString(bn + ".at", ""));
                    String mob = bosses.getString(bn + ".mob", "");
                    if (at != null && !mob.isBlank()) slot.bosses.put(bn, new Boss(mob, at));
                }
                var points = c.getConfigurationSection("points");
                if (points != null) for (String pn : points.getKeys(false)) {
                    Loc at = Loc.parse(points.getString(pn, ""));
                    if (at != null) slot.points.put(pn, at);
                }
                var gimmicks = c.getConfigurationSection("gimmicks");
                if (gimmicks != null) for (String gn : gimmicks.getKeys(false)) {
                    int[] b = block(gimmicks.getString(gn, ""));
                    if (b != null) slot.gimmicks.put(gn, b);
                }
            }
            for (int no = 1; no <= d.instances; no++) d.slot(no);
            out.put(id, d);
        }
        return out;
    }

    private static void readRewards(List<Map<?, ?>> list, List<Reward> out) {
        for (var m : list) {
            int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            double chance = m.get("chance") instanceof Number c ? Math.max(0, Math.min(100, c.doubleValue())) : 0;
            out.add(new Reward(str(m.get("item")), amount, str(m.get("data")), chance));
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    // ------------------------------------------------------------ 쓰기 (인게임 설정 명령 → dungeons.yml)

    static void write(ConfigurationSection root, Dungeon d) {
        var s = root.createSection(d.id);
        s.set("name", d.name);
        s.set("subtitle", d.subtitle);
        s.set("world", d.world);
        s.set("permission", d.permission);
        s.set("lock-message", d.lockMessage);
        s.set("ticket.item", d.ticketItem);
        s.set("ticket.amount", d.ticketAmount);
        s.set("ticket.free-until-first-clear", d.freeUntilFirstClear);
        s.set("instances", d.instances);
        s.set("portal", d.portal == null ? "" : d.portal.text());
        s.set("portal-radius", d.portalRadius);
        s.set("portal-label", d.portalLabel);
        s.set("exit", d.exit == null ? "" : d.exit.text());
        s.set("clear-commands", d.clearCommands);
        s.set("rewards.first", rewards(d.firstRewards));
        s.set("rewards.base", rewards(d.baseRewards));
        s.set("rewards.random", rewards(d.randomRewards));
        s.set("natural-regen", d.naturalRegen);
        var stages = new ArrayList<Map<String, Object>>();
        for (var st : d.stages) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", st.name); m.put("goal", st.goal); m.put("type", st.type); m.put("target", st.target);
            if (st.type.equals("kill")) m.put("count", st.count);
            if (st.type.equals("reach")) m.put("radius", st.radius);
            if (!st.startCommands.isEmpty()) m.put("start-commands", st.startCommands);
            stages.add(m);
        }
        s.set("stages", stages);
        var slots = s.createSection("slots");
        for (var slot : d.slots.values()) {
            var c = slots.createSection(String.valueOf(slot.no));
            c.set("start", slot.start == null ? "" : slot.start.text());
            c.set("region.1", block(slot.corner1));
            c.set("region.2", block(slot.corner2));
            var rooms = c.createSection("rooms");
            for (var room : slot.rooms.values()) {
                var r = rooms.createSection(room.name);
                r.set("1", block(room.corner1));
                r.set("2", block(room.corner2));
                r.set("lock", room.lock);
                var spawns = new ArrayList<Map<String, Object>>();
                for (var sp : room.spawns) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("mob", sp.mob); m.put("count", sp.count); m.put("at", sp.at.text());
                    spawns.add(m);
                }
                r.set("spawns", spawns);
            }
            var bosses = c.createSection("bosses");
            for (var e : slot.bosses.entrySet()) { bosses.set(e.getKey() + ".mob", e.getValue().mob); bosses.set(e.getKey() + ".at", e.getValue().at.text()); }
            var points = c.createSection("points");
            for (var e : slot.points.entrySet()) points.set(e.getKey(), e.getValue().text());
            var gimmicks = c.createSection("gimmicks");
            for (var e : slot.gimmicks.entrySet()) gimmicks.set(e.getKey(), block(e.getValue()));
        }
    }

    private static List<Map<String, Object>> rewards(List<Reward> list) {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : list) {
            var m = new LinkedHashMap<String, Object>();
            if (!r.item().isBlank()) m.put("item", r.item());
            m.put("amount", r.amount());
            if (!r.data().isBlank()) m.put("data", r.data());
            if (r.chance() > 0) m.put("chance", r.chance());
            out.add(m);
        }
        return out;
    }
}
