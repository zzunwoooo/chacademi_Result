package school.chacademia.tower;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml 을 읽은 값 (다시불러오기 때마다 새로 만든다). */
final class Settings {
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
            if (a == null || b == null) return null;
            return new Box(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
        }
        boolean contains(Location l) {
            double x = l.getX(), y = l.getY(), z = l.getZ();
            return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }
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

    record Affix(String id, String name, int from, double chance, double perFloor) {
        double chanceAt(int floor) { return floor < from ? 0 : Math.min(0.6, chance + perFloor * (floor - from)); }
    }

    record Grade(String id, String name, int color, double floor1, double floor50) {
        double weightAt(int floor, int floors) {
            double t = floors <= 1 ? 1 : Math.clamp((floor - 1) / (double) (floors - 1), 0, 1);
            return Math.max(0, floor1 + (floor50 - floor1) * t);
        }
    }

    final String name;
    final int floors;
    final String ticketItem;
    final int ticketAmount;
    final Loc start, exit, portal;
    final double portalRadius;
    final int[] corner1, corner2;
    final int floorStartSeconds, cardDelaySeconds, cardSeconds, leaveGraceSeconds, queueOfferSeconds;
    final List<String> normalMobs, eliteMobs;
    final ConfigurationSection balance;
    final int maxAffixes;
    final List<Affix> affixes = new ArrayList<>();
    final List<Grade> grades = new ArrayList<>();
    final int rerolls;
    final double firstCardOtherSpellChance;
    final List<String> basicSpells, excludedSpells;
    final String databaseFile;
    final double startIntelligence;

    Settings(FileConfiguration c) {
        name = c.getString("name", "무한의 탑");
        floors = Math.max(1, c.getInt("floors", 50));
        ticketItem = c.getString("ticket.item", "item:dungeon_ticket");
        ticketAmount = Math.max(0, c.getInt("ticket.amount", 1));
        start = Loc.parse(c.getString("locations.start"));
        exit = Loc.parse(c.getString("locations.exit"));
        portal = Loc.parse(c.getString("locations.portal"));
        portalRadius = Math.max(0.5, c.getDouble("locations.portal-radius", 2.0));
        corner1 = block(c.getString("room.corner1"));
        corner2 = block(c.getString("room.corner2"));
        floorStartSeconds = Math.max(1, c.getInt("floor-start-seconds", 3));
        cardDelaySeconds = Math.max(0, c.getInt("card-delay-seconds", 1));
        cardSeconds = Math.max(10, c.getInt("card-seconds", 40));
        leaveGraceSeconds = Math.max(1, c.getInt("leave-grace-seconds", 5));
        queueOfferSeconds = Math.max(10, c.getInt("queue-offer-seconds", 30));
        normalMobs = List.copyOf(c.getStringList("mobs.normal"));
        eliteMobs = List.copyOf(c.getStringList("mobs.elite"));
        var b = c.getConfigurationSection("balance");
        balance = b != null ? b : c.createSection("balance");
        maxAffixes = Math.max(0, c.getInt("max-affixes", 2));
        var a = c.getConfigurationSection("affixes");
        if (a != null) for (String id : a.getKeys(false))
            affixes.add(new Affix(id, a.getString(id + ".name", id), a.getInt(id + ".from", 99), a.getDouble(id + ".chance"), a.getDouble(id + ".per-floor")));
        var g = c.getConfigurationSection("grades");
        for (String id : List.of("common", "rare", "epic", "legendary")) {
            String hex = g == null ? "" : g.getString(id + ".color", "");
            int color = hex.matches("#[0-9A-Fa-f]{6}") ? Integer.parseInt(hex.substring(1), 16) : 0xFFFFFF;
            grades.add(new Grade(id, g == null ? id : g.getString(id + ".name", id), color,
                    g == null ? 25 : g.getDouble(id + ".floor1", 25), g == null ? 25 : g.getDouble(id + ".floor50", 25)));
        }
        rerolls = Math.max(0, c.getInt("rerolls", 1));
        firstCardOtherSpellChance = Math.clamp(c.getDouble("first-card-other-spell-chance", 0.1), 0, 1);
        basicSpells = List.copyOf(c.getStringList("basic-spells"));
        excludedSpells = List.copyOf(c.getStringList("excluded-spells"));
        startIntelligence = Math.max(0, c.getDouble("start-intelligence", 10));
        databaseFile = c.getString("database-file", "../MagicCodexBridge/player-state-database.properties");
    }

    Box room() { return Box.of(corner1, corner2); }

    double bal(String key, double def) { return balance.getDouble(key, def); }

    /** 설정이 덜 된 부분 (입장 전에 확인). 빈 목록 = 준비됨. */
    List<String> missing() {
        var out = new ArrayList<String>();
        if (start == null || start.location() == null) out.add("시작 위치");
        if (room() == null) out.add("방 구역");
        if (normalMobs.isEmpty()) out.add("몹 목록");
        return out;
    }
}
