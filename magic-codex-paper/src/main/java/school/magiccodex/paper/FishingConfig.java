package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import school.magiccodex.protocol.FishingProtocol;

/**
 * 낚시 설정 (2026-10-20): fishing.yml (규칙·바이옴 분류·배경), fishing-fish.yml (물고기 200종), fishing-gear.yml (장비 27종),
 * fishing-treasure.yml (보물). Parsed all at once; a broken file throws with the place to fix and nothing is replaced.
 */
final class FishingConfig {
    static final String[] WATER = {"웅덩이", "연못", "호수", "대형"};
    static final String[] ZONES = {"얕은", "중층", "깊은", "심해"};

    record Fish(String id, String name, int grade, boolean magic, int shape, float minSize, float maxSize, float minDepth, float maxDepth,
                int waterMin, int waterMax, List<String> biomes, Set<String> seasons, String time, String weather, boolean underground,
                Integer yMin, Integer yMax, String moon, int power, int pattern, String description, String item, String itemsadder) {}
    /** itemsadder = the ItemsAdder item that IS this gear (owner 2026-10-20: item:fishing_rod_1, item:lure_1, item:fishing_line_1, item:bait_1). */
    record Gear(int kind, String id, String name, int grade, float a, float b, int durability, String special, String specialText,
                String description, boolean fallback, String itemsadder) {
        /** rods: a=reel power b=hook window / floats: a=sink speed b=sensitivity / lines: a=length b=max tension / baits: a=rare% b=wait% */
        float effect(String code, float otherwise) {
            for (String part : special.split(";")) {
                String[] kv = part.strip().split(":");
                if (kv[0].equals(code)) {
                    if (kv.length < 2) return 1;
                    try { return Float.parseFloat(kv[1]); } catch (NumberFormatException e) { return otherwise; }
                }
            }
            return otherwise;
        }
        boolean has(String code) {
            for (String part : special.split(";")) if (part.strip().split(":")[0].equals(code)) return true;
            return false;
        }
    }
    record Reward(String item, int min, int max, int weight) {}
    record Treasure(int rolls, List<Reward> rewards) {}
    /** display = what the codex tooltip shows for the command rewards (commands have no readable name). */
    record CodexReward(List<String> commands, List<String> items, List<String> display) {}

    float[] zoneMin = new float[4], zoneMax = new float[4];
    int[] depthCapBlocks = new int[0];
    float[] depthCapMeters = new float[0];
    int[] waterClass = new int[4];
    double[][] rarity = new double[4][4], treasureRarity = new double[4][4];
    Map<String, int[]> times = new HashMap<>();
    Map<String, Set<String>> groups = new LinkedHashMap<>();
    Set<String> caveGroups = Set.of(), iceGroups = Set.of(), iceBiomes = Set.of(), oceanGroups = Set.of();
    List<Fish> fish = List.of();
    Map<String, Fish> fishById = Map.of();
    final List<Map<String, Gear>> gear = new ArrayList<>(List.of(Map.of(), Map.of(), Map.of(), Map.of()));
    final Gear[] fallback = new Gear[4];
    Map<String, Gear> gearByItem = Map.of();
    Map<String, Fish> fishByItem = Map.of();
    Treasure[] treasure = new Treasure[4];
    CodexReward[] codexRewards = new CodexReward[4];
    boolean enabled = true;
    int scanLimit = 4096, snapDamage = 10, tankCapacity = 100;
    float waitMin = 3, waitMax = 9, treasureChance = .03f;
    int teaseMin = 1, teaseMax = 3;
    Map<String, String> messages = new HashMap<>();

    static FishingConfig load(File folder) throws Exception {
        var c = new FishingConfig();
        var main = yaml(new File(folder, "fishing.yml"));
        c.enabled = main.getBoolean("settings.enabled", true);
        c.scanLimit = Math.clamp(main.getInt("settings.water-scan-limit", 4096), 16, 32768);
        c.snapDamage = Math.max(0, main.getInt("settings.snap-line-damage", 10));
        c.tankCapacity = Math.clamp(main.getInt("tank.capacity", 100), 1, 10000);
        var w = main.getDoubleList("settings.wait-seconds");
        if (w.size() == 2) { c.waitMin = (float) Math.max(.5, w.get(0).doubleValue()); c.waitMax = (float) Math.clamp(w.get(1).doubleValue(), c.waitMin, 55); c.waitMin = Math.min(c.waitMin, c.waitMax); }
        var t = main.getIntegerList("settings.teases");
        if (t.size() == 2) { c.teaseMin = Math.clamp(t.get(0), 0, 8); c.teaseMax = Math.clamp(t.get(1), c.teaseMin, 8); }
        c.treasureChance = (float) Math.clamp(main.getDouble("settings.treasure-chance", .03), 0, 1);
        for (int i = 0; i < 4; i++) {
            var z = main.getDoubleList("zones." + ZONES[i]);
            if (z.size() != 2) throw new IllegalArgumentException("fishing.yml zones." + ZONES[i] + " 는 [최소, 최대]");
            c.zoneMin[i] = z.get(0).floatValue(); c.zoneMax[i] = z.get(1).floatValue();
            c.waterClass[i] = main.getInt("water-classes." + WATER[i], new int[]{9, 100, 1000, 999999}[i]);
            c.rarity[i] = grades(main, "rarity." + ZONES[i]);
            c.treasureRarity[i] = grades(main, "treasure-rarity." + ZONES[i]);
        }
        var caps = main.getMapList("depth-cap");
        c.depthCapBlocks = new int[caps.size()]; c.depthCapMeters = new float[caps.size()];
        for (int i = 0; i < caps.size(); i++) {
            c.depthCapBlocks[i] = ((Number) caps.get(i).get("blocks")).intValue();
            c.depthCapMeters[i] = ((Number) caps.get(i).get("meters")).floatValue();
        }
        var ts = main.getConfigurationSection("times");
        if (ts != null) for (String k : ts.getKeys(false)) {
            var r = ts.getIntegerList(k);
            if (r.size() == 2) c.times.put(k, new int[]{r.get(0), r.get(1)});
        }
        var gs = main.getConfigurationSection("biome-groups");
        if (gs == null) throw new IllegalArgumentException("fishing.yml biome-groups 가 없음");
        for (String k : gs.getKeys(false)) {
            var set = new HashSet<String>();
            for (String b : gs.getStringList(k)) set.add(b.strip().toLowerCase(Locale.ROOT));
            c.groups.put(k, set);
        }
        c.caveGroups = Set.copyOf(main.getStringList("backgrounds.cave"));
        c.iceGroups = Set.copyOf(main.getStringList("backgrounds.ice"));
        c.iceBiomes = Set.copyOf(main.getStringList("backgrounds.ice-biomes"));
        c.oceanGroups = Set.copyOf(main.getStringList("backgrounds.ocean"));
        int[] tiers = {25, 50, 75, 100};
        for (int i = 0; i < 4; i++) {
            var s = main.getConfigurationSection("codex-rewards." + tiers[i]);
            c.codexRewards[i] = new CodexReward(s == null ? List.of() : s.getStringList("commands"), s == null ? List.of() : s.getStringList("items"),
                    s == null ? List.of() : s.getStringList("display"));
        }
        var ms = main.getConfigurationSection("messages");
        if (ms != null) for (String k : ms.getKeys(false)) c.messages.put(k, ms.getString(k, ""));

        // ---- fish
        var fy = yaml(new File(folder, "fishing-fish.yml"));
        var root = fy.getConfigurationSection("fish");
        if (root == null) throw new IllegalArgumentException("fishing-fish.yml fish 가 없음");
        var list = new ArrayList<Fish>();
        int fishNumber = 0;
        for (String id : root.getKeys(false)) {
            fishNumber++;
            var s = root.getConfigurationSection(id);
            String where = "fishing-fish.yml " + id;
            if (s == null || !FishingProtocol.validId(id)) throw new IllegalArgumentException(where + ": 아이디는 영문 소문자·숫자·_");
            String name = s.getString("name", "").strip();
            if (name.isEmpty() || name.length() > FishingProtocol.MAX_NAME) throw new IllegalArgumentException(where + ".name");
            int grade = FishingProtocol.gradeIndex(s.getString("grade", ""));
            if (grade < 0) throw new IllegalArgumentException(where + ".grade 는 일반·고급·유일·전설");
            var size = s.getDoubleList("size"); var depth = s.getDoubleList("depth"); var water = s.getStringList("water");
            if (size.size() != 2 || depth.size() != 2 || water.size() != 2) throw new IllegalArgumentException(where + ": size·depth·water 는 [최소, 최대]");
            int w0 = Arrays.asList(WATER).indexOf(water.get(0)), w1 = Arrays.asList(WATER).indexOf(water.get(1));
            if (w0 < 0 || w1 < w0) throw new IllegalArgumentException(where + ".water 는 웅덩이·연못·호수·대형");
            for (String b : s.getStringList("biomes")) if (!c.groups.containsKey(b)) throw new IllegalArgumentException(where + ".biomes: '" + b + "' 는 fishing.yml biome-groups 에 없음");
            int pattern = FishingProtocol.patternIndex(s.getString("pattern", "평온"));
            if (pattern < 0) throw new IllegalArgumentException(where + ".pattern");
            String time = s.getString("time", "전체"), weather = s.getString("weather", "전체");
            if (!time.equals("전체") && !c.times.containsKey(time)) throw new IllegalArgumentException(where + ".time");
            if (!List.of("전체", "맑음", "비", "뇌우").contains(weather)) throw new IllegalArgumentException(where + ".weather");
            list.add(new Fish(id, name, grade, "마법".equals(s.getString("kind")), shape(s.getString("shape", "fish")),
                    size.get(0).floatValue(), size.get(1).floatValue(), depth.get(0).floatValue(), depth.get(1).floatValue(), w0, w1,
                    List.copyOf(s.getStringList("biomes")), Set.copyOf(s.getStringList("seasons")), time, weather, s.getBoolean("underground", false),
                    s.contains("y-min") ? s.getInt("y-min") : null, s.contains("y-max") ? s.getInt("y-max") : null, s.getString("moon", ""),
                    Math.clamp(s.getInt("power", 1), 1, 10), pattern, s.getString("description", ""), s.getString("item", "minecraft:cod"),
                    s.getString("itemsadder", "item:fish_" + fishNumber).strip()));
        }
        if (list.isEmpty() || list.size() > FishingProtocol.MAX_FISH) throw new IllegalArgumentException("물고기는 1~" + FishingProtocol.MAX_FISH + "종");
        c.fish = List.copyOf(list);
        var byId = new LinkedHashMap<String, Fish>(); for (Fish f : list) byId.put(f.id(), f);
        c.fishById = Collections.unmodifiableMap(byId);
        var fishItems = new HashMap<String, Fish>();
        for (Fish f : list) if (!f.itemsadder().isEmpty() && fishItems.put(f.itemsadder(), f) != null) throw new IllegalArgumentException("fishing-fish.yml " + f.id() + ".itemsadder 가 겹침: " + f.itemsadder());
        c.fishByItem = Map.copyOf(fishItems);
        var gearItems = new HashMap<String, Gear>();
        String[] itemPrefix = {"item:fishing_rod_", "item:lure_", "item:fishing_line_", "item:bait_"};

        // ---- gear
        var gy = yaml(new File(folder, "fishing-gear.yml"));
        for (int kind = 0; kind < 4; kind++) {
            String key = FishingProtocol.KIND_FOLDERS[kind];
            var sec = gy.getConfigurationSection(key);
            if (sec == null) throw new IllegalArgumentException("fishing-gear.yml " + key + " 가 없음");
            var map = new LinkedHashMap<String, Gear>();
            int number = 0;
            String[] fields = switch (kind) { case 0 -> new String[]{"reel-power", "hook-window"}; case 1 -> new String[]{"sink-speed", "sensitivity"};
                case 2 -> new String[]{"length", "max-tension"}; default -> new String[]{"rare-bonus", "wait-reduction"}; };
            for (String id : sec.getKeys(false)) {
                var s = sec.getConfigurationSection(id);
                String where = "fishing-gear.yml " + key + "." + id;
                if (s == null || !FishingProtocol.validId(id)) throw new IllegalArgumentException(where + ": 아이디는 영문 소문자·숫자·_");
                int grade = FishingProtocol.gradeIndex(s.getString("grade", ""));
                if (grade < 0) throw new IllegalArgumentException(where + ".grade");
                number++;
                var g = new Gear(kind, id, s.getString("name", id), grade, (float) s.getDouble(fields[0]), (float) s.getDouble(fields[1]),
                        kind == FishingProtocol.BAIT ? -1 : Math.max(1, s.getInt("durability", 50)), s.getString("special", ""),
                        s.getString("special-text", ""), s.getString("description", ""), s.getBoolean("default", false),
                        s.getString("itemsadder", itemPrefix[kind] + number).strip());
                map.put(id, g);
                if (!g.itemsadder().isEmpty() && gearItems.put(g.itemsadder(), g) != null) throw new IllegalArgumentException(where + ".itemsadder 가 겹침: " + g.itemsadder());
                if (g.fallback() && c.fallback[kind] == null) c.fallback[kind] = g;
            }
            if (map.isEmpty()) throw new IllegalArgumentException(where(key) + " 가 비어 있음");
            if (c.fallback[kind] == null) c.fallback[kind] = map.values().iterator().next();
            c.gear.set(kind, Collections.unmodifiableMap(map));
        }
        c.gearByItem = Map.copyOf(gearItems);

        // ---- treasure
        var ty = yaml(new File(folder, "fishing-treasure.yml"));
        for (int g = 0; g < 4; g++) {
            var s = ty.getConfigurationSection("treasure." + FishingProtocol.GRADE_LABELS[g]);
            var rewards = new ArrayList<Reward>();
            int rolls = 0;
            if (s != null) {
                rolls = Math.clamp(s.getInt("rolls", 1), 0, FishingProtocol.MAX_REWARDS);
                for (var m : s.getMapList("rewards")) {
                    String item = String.valueOf(m.get("item"));
                    int min = 1, max = 1;
                    if (m.get("amount") instanceof List<?> a && a.size() == 2) { min = ((Number) a.get(0)).intValue(); max = ((Number) a.get(1)).intValue(); }
                    int weight = m.get("weight") instanceof Number n ? n.intValue() : 1;
                    if (min < 1 || max < min || weight < 1) throw new IllegalArgumentException("fishing-treasure.yml " + FishingProtocol.GRADE_LABELS[g] + ": amount/weight");
                    rewards.add(new Reward(item, min, max, weight));
                }
            }
            c.treasure[g] = new Treasure(rolls, List.copyOf(rewards));
        }
        return c;
    }

    private static String where(String key) { return "fishing-gear.yml " + key; }

    private static double[] grades(YamlConfiguration y, String path) {
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) r[i] = Math.max(0, y.getDouble(path + "." + FishingProtocol.GRADE_LABELS[i], 0));
        if (r[0] + r[1] + r[2] + r[3] <= 0) throw new IllegalArgumentException("fishing.yml " + path + " 확률 합이 0");
        return r;
    }

    static int shape(String s) {
        return switch (s == null ? "" : s) { case "shark" -> FishingProtocol.SHAPE_SHARK; case "eel" -> FishingProtocol.SHAPE_EEL;
            case "ray" -> FishingProtocol.SHAPE_RAY; case "squid" -> FishingProtocol.SHAPE_SQUID; default -> FishingProtocol.SHAPE_FISH; };
    }

    private static YamlConfiguration yaml(File f) throws Exception {
        if (!f.exists()) throw new IllegalArgumentException(f.getName() + " 가 없음");
        var y = new YamlConfiguration(); y.load(f); return y;
    }

    // ------------------------------------------------------------------ lookups

    Gear gear(int kind, String id) { return id == null ? null : gear.get(kind).get(id); }
    int zone(float depth) { for (int i = 3; i >= 0; i--) if (depth >= zoneMin[i]) return i; return 0; }
    /** Minigame depth limit (m) for this many blocks of real water; 0 = unlimited. */
    float depthCap(int blocks) {
        float cap = depthCapMeters.length == 0 ? 0 : depthCapMeters[0];
        for (int i = 0; i < depthCapBlocks.length; i++) if (blocks >= depthCapBlocks[i]) cap = depthCapMeters[i];
        return cap;
    }
    int waterClassOf(int blocks) { for (int i = 0; i < 4; i++) if (blocks <= waterClass[i]) return i; return 3; }
    String message(String key, String otherwise) { String m = messages.get(key); return m == null || m.isBlank() ? otherwise : m; }
    /** Groups that contain this biome key. */
    Set<String> groupsOf(String biome) {
        var s = new HashSet<String>();
        for (var e : groups.entrySet()) if (e.getValue().contains(biome)) s.add(e.getKey());
        return s;
    }
}
