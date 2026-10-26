package kr.chacademy.motion.broom.trail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 빗자루 → 궤적 연결표. 리소스 {@code assets/chacademy_motion/broom_trails.json} (리소스팩으로 덮어쓰기 가능).
 *
 * <pre>
 * {
 *   "default": "basic",
 *   "effects": {
 *     "basic": { "full": "claude:broomtrails/basic", "lite": "claude:broomtrails/basic_lite",
 *                "cost": 6.1, "liteCost": 1.8, "constCost": 0 }
 *   },
  *   "brooms": { "broom_1": "basic", "broom_26": "l_dragon" }
 * }
 * </pre>
 * cost = 이동 1블록당 살아 있는 입자 수(입자·초/블록, Σ rateOverDistance × 평균 수명), constCost = 정지해도 있는 입자 수.
 * 예산 계산에만 쓴다. 궤적 키는 아티팩트 덮어쓰기(BroomApi)에서도 쓴다.
 */
public final class TrailConfig {
    /** 궤적 한 종류. lite 가 없으면 멀리서도 full 을 쓴다(예산 초과 시 숨김). */
    public record TrailType(String key, String full, String lite, double cost, double liteCost, double constCost) {
        String effect(boolean fullVariant) {
            return fullVariant || lite == null ? full : lite;
        }

        double cost(boolean fullVariant, double blocksPerSecond) {
            double perBlock = fullVariant || lite == null ? cost : liteCost;
            return perBlock * Math.max(0, blocksPerSecond) + (fullVariant ? constCost : 0);
        }
    }

    public static final TrailConfig EMPTY = new TrailConfig(null, Map.of(), Map.of());

    private final String defaultKey;
    private final Map<String, TrailType> effects;
    private final Map<String, String> brooms;

    private TrailConfig(String defaultKey, Map<String, TrailType> effects, Map<String, String> brooms) {
        this.defaultKey = defaultKey;
        this.effects = effects;
        this.brooms = brooms;
    }

    /** 덮어쓰기 키가 있으면 그것, 없으면 빗자루 연결, 그것도 없으면 default. 없는 키면 null. */
    public TrailType resolve(String broom, String overrideKey) {
        if (overrideKey != null) {
            TrailType t = effects.get(overrideKey);
            if (t != null) return t;
        }
        String key = broom == null ? null : brooms.get(broom);
        TrailType t = key == null ? null : effects.get(key);
        if (t == null && defaultKey != null) t = effects.get(defaultKey);
        return t;
    }

    public boolean hasKey(String key) {
        return effects.containsKey(key);
    }

    public int size() {
        return effects.size();
    }

    /** 잘못된 항목은 건너뛰고 경고 목록에 담는다. 전체가 깨지면 IllegalArgumentException. */
    public static TrailConfig parse(String json, java.util.List<String> warnings) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, TrailType> effects = new HashMap<>();
        if (root.has("effects")) {
            for (var e : root.getAsJsonObject("effects").entrySet()) {
                try {
                    JsonObject o = e.getValue().getAsJsonObject();
                    String full = o.get("full").getAsString();
                    String lite = o.has("lite") && !o.get("lite").isJsonNull() ? o.get("lite").getAsString() : null;
                    double cost = num(o, "cost", 10), liteCost = num(o, "liteCost", cost), constCost = num(o, "constCost", 0);
                    if (!validId(full) || (lite != null && !validId(lite))) throw new IllegalArgumentException("효과 ID 형식 오류");
                    effects.put(e.getKey(), new TrailType(e.getKey(), full, lite, cost, liteCost, constCost));
                } catch (RuntimeException ex) {
                    warnings.add("effects." + e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        Map<String, String> brooms = new HashMap<>();
        if (root.has("brooms")) {
            for (var e : root.getAsJsonObject("brooms").entrySet()) {
                String key = e.getValue().getAsString();
                if (!effects.containsKey(key)) warnings.add("brooms." + e.getKey() + ": 없는 궤적 키 " + key);
                else brooms.put(e.getKey(), key);
            }
        }
        String def = root.has("default") ? root.get("default").getAsString() : null;
        if (def != null && !effects.containsKey(def)) {
            warnings.add("default: 없는 궤적 키 " + def);
            def = null;
        }
        return new TrailConfig(def, Map.copyOf(effects), Map.copyOf(brooms));
    }

    private static double num(JsonObject o, String k, double fallback) {
        if (!o.has(k)) return fallback;
        double v = o.get(k).getAsDouble();
        if (!Double.isFinite(v) || v < 0 || v > 1000) throw new IllegalArgumentException(k + " 범위 오류");
        return v;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
