package school.magiccodex.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Aim preview shapes generated from magic-mythic-paper/tools/spells.py.
 * Display only: the Bridge still checks range and line of sight with its own spell-runtime.yml.
 * Spells without an entry (self / utility) keep the old instant cast.
 */
public final class SpellTargets {
    public enum Mode { AREA, LINE, UNIT, POINT, CONE, WALL }
    public enum Filter { NONE, AIR, BURNING }
    /**
     * @param range  max distance from the eyes to the chosen point/target (server ray length)
     * @param radius AREA/POINT/UNIT ring radius
     * @param length LINE length, CONE radius
     * @param width  LINE width, WALL width, CONE angle (degrees)
     * @param depth  WALL thickness
     */
    public record Shape(Mode mode, double range, double radius, double length, double width, double depth, Filter filter, boolean casterOrigin) {
        public boolean needsPoint() { return mode != Mode.UNIT; }
    }

    private static final String RESOURCE = "/assets/magiccodex/spell-targeting.json";
    private static Map<String, Shape> shapes;

    private SpellTargets() {}

    public static Shape get(String id) {
        if (shapes == null) {
            try (InputStream in = SpellTargets.class.getResourceAsStream(RESOURCE)) {
                shapes = in == null ? Map.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("magiccodex").warn("spell-targeting.json unreadable; aimed casting disabled", error);
                shapes = Map.of();
            }
        }
        return id == null ? null : shapes.get(id);
    }

    /** Rejects nothing silently except malformed single entries (logged by the caller's absence). */
    public static Map<String, Shape> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject spells = root.getAsJsonObject("spells");
        Map<String, Shape> out = new HashMap<>();
        if (spells == null) return Map.of();
        for (var e : spells.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_-]{1,64}") || !e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            Mode mode;
            try { mode = Mode.valueOf(str(o, "mode", "").toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { continue; }
            Filter filter = switch (str(o, "filter", "")) { case "air" -> Filter.AIR; case "burning" -> Filter.BURNING; default -> Filter.NONE; };
            double range = clamp(num(o, "range", 15), 1, 48);
            double radius = clamp(num(o, "radius", mode == Mode.UNIT ? 0 : .6), 0, 16);
            double length = clamp(num(o, "length", range), .5, 48);
            double width = clamp(num(o, mode == Mode.CONE ? "angle" : "width", 1), .2, mode == Mode.CONE ? 180 : 16);
            double depth = clamp(num(o, "depth", 1), .2, 8);
            out.put(e.getKey(), new Shape(mode, range, radius, length, width, depth, filter, str(o,"origin","eye").equals("caster")));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String str(JsonObject o, String key, String fallback) {
        var v = o.get(key);
        return v == null || !v.isJsonPrimitive() ? fallback : v.getAsString();
    }
    private static double num(JsonObject o, String key, double fallback) {
        var v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return fallback;
        double d = v.getAsDouble();
        return Double.isFinite(d) ? d : fallback;
    }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
