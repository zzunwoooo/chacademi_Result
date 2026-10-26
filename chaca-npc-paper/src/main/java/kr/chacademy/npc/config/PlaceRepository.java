package kr.chacademy.npc.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * places.yml — 장소 이름, 좌표, 배회 반경, 가는 길 중간 지점.
 */
public final class PlaceRepository {

    public static final class Place {
        public final String name;
        public String label;          // 프롬프트에 보이는 이름 (없으면 name)
        public Location location;
        public double radius;
        public final List<Location> waypoints = new ArrayList<>();
        /** 저장할 때 쓰는 월드 이름 (월드가 나중에 내려가도 파일에는 그대로 남긴다). */
        String worldName;
        /** 월드를 찾지 못해 쓰지 못하는 중간 지점 원본 — 저장할 때 그대로 다시 적는다. */
        final List<Map<String, Object>> unresolvedWaypoints = new ArrayList<>();

        Place(String name) {
            this.name = name;
        }

        public String label() {
            return label == null || label.isBlank() ? name : label;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<String, Place> places = new LinkedHashMap<>();
    /** 월드가 아직 없어 불러오지 못한 장소 원본. 저장할 때 지워지지 않게 그대로 다시 적는다. */
    private final Map<String, Map<String, Object>> unresolved = new LinkedHashMap<>();

    public PlaceRepository(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public synchronized void load() {
        places.clear();
        unresolved.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("places");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) {
                continue;
            }
            Location loc = readLoc(s);
            if (loc == null) {
                // 월드가 아직 안 올라온 장소: 쓰지는 못하지만 원본을 들고 있다가 저장할 때 그대로 다시 적는다
                unresolved.put(name, new LinkedHashMap<>(s.getValues(false)));
                log.warning("[ChacaNPC] 장소 '" + name + "'의 월드(" + s.getString("world", "world")
                        + ")를 찾을 수 없어 건너뜀 (places.yml 에는 그대로 둡니다)");
                continue;
            }
            Place p = new Place(name);
            p.label = s.getString("label", null);
            p.location = loc;
            p.worldName = loc.getWorld().getName();
            p.radius = s.getDouble("radius", 4);
            for (Map<?, ?> m : s.getMapList("waypoints")) {
                Object wn = m.get("world");
                if (wn != null && Bukkit.getWorld(wn.toString()) == null) {
                    Map<String, Object> raw = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> en : m.entrySet()) {
                        raw.put(String.valueOf(en.getKey()), en.getValue());
                    }
                    p.unresolvedWaypoints.add(raw);
                    log.warning("[ChacaNPC] 장소 '" + name + "'의 중간 지점 월드(" + wn + ")를 찾을 수 없어 건너뜀");
                    continue;
                }
                Location w = readLoc(m, loc.getWorld());
                if (w != null) {
                    p.waypoints.add(w);
                }
            }
            places.put(name, p);
        }
        log.info("[ChacaNPC] 장소 " + places.size() + "곳 불러옴"
                + (unresolved.isEmpty() ? "" : " (월드를 못 찾은 장소 " + unresolved.size() + "곳은 보류)"));
    }

    public synchronized Place get(String name) {
        return name == null ? null : places.get(name);
    }

    public synchronized Collection<Place> all() {
        return new ArrayList<>(places.values());
    }

    /** 장소 이름으로 쓸 수 있는지: 마침표는 YAML 경로 구분자라 저장하면 다른 장소로 쪼개진다. */
    public static boolean validName(String name) {
        return name != null && !name.isBlank() && name.indexOf('.') < 0 && name.indexOf(' ') < 0;
    }

    /** @return 이름을 쓸 수 없거나 위치에 월드가 없으면 false (저장하지 않음) */
    public synchronized boolean setPlace(String name, Location loc, double radius) {
        if (!validName(name) || loc == null || loc.getWorld() == null) {
            return false;
        }
        Place p = places.computeIfAbsent(name, Place::new);
        p.location = loc.clone();
        p.worldName = loc.getWorld().getName();
        p.radius = radius;
        unresolved.remove(name); // 같은 이름으로 다시 등록하면 보류해 둔 예전 내용은 버린다
        save();
        return true;
    }

    /** 장소 지우기 (오너 2026-10-26: 테스트 때 만든 장소 정리). 월드를 못 찾아 보류 중인 장소도 지운다. @return 지운 게 있으면 true */
    public synchronized boolean removePlace(String name) {
        boolean removed = places.remove(name) != null | unresolved.remove(name) != null;
        if (removed) save();
        return removed;
    }

    /** 모든 장소 이름 (월드를 못 찾아 보류 중인 것 포함). */
    public synchronized List<String> allNames() {
        var out = new ArrayList<String>(places.keySet());
        for (String n : unresolved.keySet()) if (!out.contains(n)) out.add(n);
        out.sort(null);
        return out;
    }

    public synchronized boolean addWaypoint(String name, Location loc) {
        Place p = places.get(name);
        if (p == null) {
            return false;
        }
        p.waypoints.add(loc.clone());
        save();
        return true;
    }

    public synchronized boolean clearWaypoints(String name) {
        Place p = places.get(name);
        if (p == null) {
            return false;
        }
        p.waypoints.clear();
        p.unresolvedWaypoints.clear();
        save();
        return true;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Place p : places.values()) {
            String base = "places." + p.name + ".";
            if (p.label != null) {
                y.set(base + "label", p.label);
            }
            y.set(base + "world", p.worldName);
            y.set(base + "x", round(p.location.getX()));
            y.set(base + "y", round(p.location.getY()));
            y.set(base + "z", round(p.location.getZ()));
            y.set(base + "yaw", round(p.location.getYaw()));
            y.set(base + "pitch", round(p.location.getPitch()));
            y.set(base + "radius", p.radius);
            List<Map<String, Object>> wps = new ArrayList<>();
            for (Location w : p.waypoints) {
                Map<String, Object> m = new LinkedHashMap<>();
                String wn = worldName(w);
                if (wn != null && !wn.equals(p.worldName)) {
                    m.put("world", wn); // 장소와 다른 월드의 중간 지점만 월드를 적는다
                }
                m.put("x", round(w.getX()));
                m.put("y", round(w.getY()));
                m.put("z", round(w.getZ()));
                wps.add(m);
            }
            wps.addAll(p.unresolvedWaypoints);
            y.set(base + "waypoints", wps);
        }
        for (Map.Entry<String, Map<String, Object>> e : unresolved.entrySet()) {
            if (!places.containsKey(e.getKey())) {
                y.createSection("places." + e.getKey(), e.getValue());
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            log.warning("[ChacaNPC] places.yml 저장 실패: " + ex.getMessage());
        }
    }

    private static String worldName(Location l) {
        try {
            World w = l.getWorld();
            return w == null ? null : w.getName();
        } catch (RuntimeException ex) {
            return null; // 월드가 내려간 위치
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Location readLoc(ConfigurationSection s) {
        World w = Bukkit.getWorld(s.getString("world", "world"));
        if (w == null) {
            return null;
        }
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
    }

    private static Location readLoc(Map<?, ?> m, World defaultWorld) {
        World w = defaultWorld;
        Object wn = m.get("world");
        if (wn != null) {
            World found = Bukkit.getWorld(wn.toString());
            if (found != null) {
                w = found;
            }
        }
        try {
            return new Location(w, num(m.get("x")), num(m.get("y")), num(m.get("z")));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }
}
