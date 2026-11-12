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
        boolean overlaps(Box o) { return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ; }
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
    final Loc exit, portal;
    final double portalRadius;
    // [기능:탑여러방 시작] 방 여러 개 (오너 11/12 "기존 던전처럼 여러 개"): 방마다 한 판씩 동시에, 다 차면 대기열.
    /** 방 하나: 시작 자리 · 구역 · 몹 소환 자리(비면 구역 안 아무 데나). */
    record Room(String id, Loc start, Box box, List<Loc> spawns) {
        boolean ready() { return start != null && box != null && start.location() != null; }
        String world() { return start == null ? "" : start.world(); }
        boolean contains(Location l) { return box != null && l != null && l.getWorld() != null && l.getWorld().getName().equals(world()) && box.contains(l); }
    }
    final Map<String, Room> rooms = new LinkedHashMap<>();
    // [기능:탑여러방 끝]
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
    /** 탑 안에서 막을 모드 채널 (소지품·능력치·위치가 바뀌는 화면). */
    final List<String> lockedChannels;
    final double startIntelligence;
    final double startMana, startManaRegen;
    final int startPotions;
    /** 시작할 때 고르는 마법 수 (1장으로는 1층이 너무 길고 심심해서 기본 2). */
    final int startSpellPicks;
    // [기능:탑퀘스트] 판이 끝날 때(어떤 결과든) 콘솔로 실행할 명령 ({player}) · 처음 하는 사람은 입장권 없이
    final java.util.List<String> endCommands;
    final boolean freeFirstPlay;
    /** 치유 물약으로 줄 아이템애더 아이템 (일반·상급). */
    final String potionItem, potionBig;
    /** [기능:탑몹스킬] 몹별 큰 스킬 (MythicMobs 스킬 이름). 탑 안에선 skill-pulse-seconds 마다 하나씩 쓰게 한다 (원래는 약 43초에 한 번). */
    final Map<String, List<String>> mobSkills = new LinkedHashMap<>();
    final double skillPulseSeconds;

    Settings(FileConfiguration c) {
        name = c.getString("name", "무한의 탑");
        floors = Math.max(1, c.getInt("floors", 50));
        ticketItem = c.getString("ticket.item", "item:dungeon_ticket");
        ticketAmount = Math.max(0, c.getInt("ticket.amount", 1));
        exit = Loc.parse(c.getString("locations.exit"));
        portal = Loc.parse(c.getString("locations.portal"));
        portalRadius = Math.max(0.5, c.getDouble("locations.portal-radius", 2.0));
        var rs = c.getConfigurationSection("rooms");
        if (rs != null) for (String id : rs.getKeys(false)) {
            var spawns = new ArrayList<Loc>();
            var box = Box.of(block(rs.getString(id + ".corner1")), block(rs.getString(id + ".corner2")));
            for (String t : rs.getStringList(id + ".spawns")) {
                var l = Loc.parse(t);
                if (l == null) continue;
                if (box != null && !box.contains(new Location(null, l.x(), l.y(), l.z()))) continue;   // 구역을 옮긴 뒤 밖에 남은 소환 자리는 안 씀
                spawns.add(l);
            }
            rooms.put(id, new Room(id, Loc.parse(rs.getString(id + ".start")), box, List.copyOf(spawns)));
        }
        floorStartSeconds = Math.max(1, c.getInt("floor-start-seconds", 3));
        cardDelaySeconds = Math.max(0, c.getInt("card-delay-seconds", 1));
        cardSeconds = Math.max(10, c.getInt("card-seconds", 30));
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
        startMana = Math.max(1, c.getDouble("start-mana", 300));
        startPotions = Math.max(0, c.getInt("start-potions", 3));
        startSpellPicks = Math.clamp(c.getInt("start-spell-picks", 2), 1, 3);
        endCommands = c.isList("end-commands") ? c.getStringList("end-commands") : java.util.List.of("유저관리 {player} 스토리신호 탑도전");
        freeFirstPlay = c.getBoolean("ticket.free-first-play", true);
        potionItem = c.getString("potion-item", "item:health_potion");
        var ms = c.getConfigurationSection("mob-skills");
        if (ms != null) for (String id : ms.getKeys(false)) mobSkills.put(id, List.copyOf(ms.getStringList(id)));
        skillPulseSeconds = Math.max(3, c.getDouble("skill-pulse-seconds", 8));
        potionBig = c.getString("potion-item-big", "item:health_potion_big");
        Cards.LIFESTEAL_FLOOR_HEAL = balance.getDouble("lifesteal-floor-heal", 80);
        double regen = c.getDouble("start-mana-regen", 0);
        startManaRegen = regen > 0 ? regen : startMana * 0.10; // 규칙: 기본 회복 = 최대 마나의 10%
        databaseFile = c.getString("database-file", "../MagicCodexBridge/player-state-database.properties");
        lockedChannels = c.isList("locked-channels") ? List.copyOf(c.getStringList("locked-channels")) : DEFAULT_LOCKED;
    }

    /** 쓸 수 있는 방 (시작 자리·구역이 다 있고 월드가 켜져 있음). */
    List<Room> readyRooms() { return rooms.values().stream().filter(Room::ready).toList(); }

    /** 이 구역과 겹치는 다른 방 이름 (없으면 null). */
    String overlapping(String id, Box box) {
        if (box == null) return null;
        for (var r : rooms.values()) if (!r.id().equals(id) && r.box() != null && r.box().overlaps(box)) return r.id();
        return null;
    }

    /** 이 자리가 들어 있는 방 (없으면 null). */
    Room roomAt(Location l) { for (var r : rooms.values()) if (r.contains(l)) return r; return null; }

    boolean roomWorld(World w) { for (var r : rooms.values()) if (r.world().equals(w.getName())) return true; return false; }

    static final List<String> DEFAULT_LOCKED = List.of(
            "magiccodex:bag_request", "magiccodex:mailbox_request", "magiccodex:shop_request", "magiccodex:fish_shop_request", "magiccodex:fish_tank_request",
            "magiccodex:fishing_request", "magiccodex:enhance_request", "magiccodex:alchemy_request", "magiccodex:artifact_request", "magiccodex:equipment_request",
            "magiccodex:ascend_request", "magiccodex:core_request", "magiccodex:pet_request", "magiccodex:taming_request", "magiccodex:loot_shop_request",
            "magiccodex:quest_request", "magiccodex:mileage_request", "magiccodex:wallet_request", "magiccodex:title_request", "magiccodex:travel_request",
            "magiccodex:tp_pick_request", "magiccodex:school_request", "chacademi:housing", "chacademi:chunk_claim_action", "chacademy:broom_req");

    double bal(String key, double def) { return balance.getDouble(key, def); }

    /** 설정이 덜 된 부분 (입장 전에 확인). 빈 목록 = 준비됨. */
    List<String> missing() {
        var out = new ArrayList<String>();
        if (readyRooms().isEmpty()) out.add(rooms.isEmpty() ? "방 (시작 위치·방 구역)" : "시작 위치·방 구역이 다 찍힌 방");
        if (normalMobs.isEmpty()) out.add("몹 목록");
        return out;
    }
}
