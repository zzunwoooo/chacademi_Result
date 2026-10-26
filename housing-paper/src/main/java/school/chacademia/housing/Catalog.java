package school.chacademia.housing;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import school.magiccodex.protocol.HousingProtocol;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Def;

/** furniture.yml → 가구 정의. 잘못된 항목은 건너뛰고 경고만 남긴다 (나머지는 그대로 쓴다). */
final class Catalog {
    private final Map<String, Def> defs;
    private final List<Def> ordered;
    private final int revision;

    private Catalog(List<Def> list) {
        var map = new LinkedHashMap<String, Def>();
        for (Def d : list) map.put(d.id(), d);
        defs = Collections.unmodifiableMap(map);
        ordered = List.copyOf(map.values());
        revision = ordered.hashCode() & 0x7FFFFFFF;
    }

    static final Catalog EMPTY = new Catalog(List.of());

    Def get(String id) { return defs.get(id); }
    List<Def> all() { return ordered; }
    int revision() { return revision; }
    HousingProtocol.Catalog message() { return new HousingProtocol.Catalog(revision, ordered); }

    /** 이 플레이어가 놓을 수 있는 가구 → 최대 개수 (권한이 있는 것만). */
    Map<String, Integer> allowed(org.bukkit.permissions.Permissible p) {
        var map = new HashMap<String, Integer>();
        for (Def d : ordered) if (p.hasPermission(HousingRules.permission(d.id()))) map.put(d.id(), d.max());
        return map;
    }
    /** 권한과 상관없이 모든 가구의 최대 개수 (관리자 크기 변경 등 주인이 없을 때 정리용). */
    Map<String, Integer> everything() {
        var map = new HashMap<String, Integer>();
        for (Def d : ordered) map.put(d.id(), d.max());
        return map;
    }

    static Catalog parse(ConfigurationSection root, Logger log) {
        var list = new ArrayList<Def>();
        for (String id : root.getKeys(false)) {
            if (list.size() >= HousingProtocol.MAX_CATALOG) { log.warning("가구가 " + HousingProtocol.MAX_CATALOG + "개를 넘어 나머지는 무시합니다."); break; }
            var s = root.getConfigurationSection(id);
            if (s == null) { log.warning("가구 " + id + ": 항목 형식이 아니에요."); continue; }
            try { list.add(def(id, s)); }
            catch (IllegalArgumentException e) { log.warning("가구 " + id + " 건너뜀: " + e.getMessage()); }
        }
        return new Catalog(list);
    }

    static Def def(String id, ConfigurationSection s) {
        if (!HousingRules.validId(id)) throw new IllegalArgumentException("ID 는 영문 소문자·숫자·_ 32자 이하");
        String name = s.getString("name", "").strip();
        String category = s.getString("category", "").strip();
        int cat, sub = 0;
        switch (category) {
            case "가구" -> cat = HousingRules.CAT_FURNITURE;
            case "소품" -> cat = HousingRules.CAT_PROP;
            case "벽걸이" -> cat = HousingRules.CAT_WALL;
            case "벽지" -> { cat = HousingRules.CAT_SURFACE; sub = HousingRules.SURFACE_WALLPAPER; }
            case "바닥재" -> { cat = HousingRules.CAT_SURFACE; sub = HousingRules.SURFACE_FLOOR; }
            default -> throw new IllegalArgumentException("category 는 가구·소품·벽걸이·벽지·바닥재 중 하나");
        }
        int w = 1, d = 1, height = 0;
        if (cat != HousingRules.CAT_SURFACE) {
            String size = s.getString("size", "1x1").strip().toLowerCase(Locale.ROOT).replace('×', 'x');
            String[] parts = size.split("x");
            if (parts.length != 2) throw new IllegalArgumentException("size 는 \"가로x세로\"");
            try { w = Integer.parseInt(parts[0].strip()); d = Integer.parseInt(parts[1].strip()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("size 숫자가 아니에요"); }
            if (cat == HousingRules.CAT_WALL) {
                if (d < 1 || d > HousingRules.WALL_HEIGHT) throw new IllegalArgumentException("벽걸이 높이는 1~" + HousingRules.WALL_HEIGHT + "칸");
                height = d * 10; d = 1;
            } else {
                double h = s.getDouble("height", 1.0);
                if (h < 0 || h > HousingRules.WALL_HEIGHT) throw new IllegalArgumentException("height 는 0~" + HousingRules.WALL_HEIGHT);
                height = (int) Math.round(h * 10);
            }
        }
        int color = color(s.getString("color", "#8A6A4A"));
        int accent = color(s.getString("accent", "#E1B866"));
        String icon = s.getString("icon", "").strip();
        if (!icon.isEmpty() && !icon.matches("[a-z0-9_.-]+:[a-z0-9_./-]+\\.png")) throw new IllegalArgumentException("icon 은 namespace:path.png");
        int max = s.getInt("max", 1);
        if (max < 1 || max > HousingRules.MAX_PLACEMENTS) throw new IllegalArgumentException("max 는 1~" + HousingRules.MAX_PLACEMENTS);
        if (cat == HousingRules.CAT_SURFACE) max = 1;
        return new Def(id, name, cat, sub, w, d, height, color, accent, icon, max);
    }

    static int color(String hex) {
        String v = hex == null ? "" : hex.strip();
        if (v.startsWith("#")) v = v.substring(1);
        if (!v.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("색은 #RRGGBB");
        return 0xFF000000 | Integer.parseInt(v, 16);
    }
}
