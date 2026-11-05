package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 느슨한 JSON 읽기 (없거나 타입이 달라도 기본값) */
public final class J {
    private J() {}

    public static JsonObject parse(String s) {
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    public static JsonElement get(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = get(o, key);
        try { return e == null ? def : e.isJsonPrimitive() ? e.getAsString() : e.toString(); } catch (RuntimeException ex) { return def; }
    }

    public static String str(JsonObject o, String key) { return str(o, key, ""); }

    public static long num(JsonObject o, String key, long def) {
        JsonElement e = get(o, key);
        try { return e == null ? def : e.getAsLong(); } catch (RuntimeException ex) {
            try { return (long) e.getAsDouble(); } catch (RuntimeException ex2) { return def; }
        }
    }

    public static long num(JsonObject o, String key) { return num(o, key, 0); }

    public static double dbl(JsonObject o, String key, double def) {
        JsonElement e = get(o, key);
        try { return e == null ? def : e.getAsDouble(); } catch (RuntimeException ex) { return def; }
    }

    public static boolean bool(JsonObject o, String key) {
        JsonElement e = get(o, key);
        try { return e != null && e.getAsBoolean(); } catch (RuntimeException ex) { return false; }
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = get(o, key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonObject objOrEmpty(JsonObject o, String key) {
        JsonObject r = obj(o, key);
        return r == null ? new JsonObject() : r;
    }

    public static List<JsonObject> objs(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement e = get(o, key);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    public static List<String> strs(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = get(o, key);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) {
            try { out.add(x.getAsString()); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    public static long[] longs(JsonObject o, String key) {
        JsonElement e = get(o, key);
        if (e == null || !e.isJsonArray()) return new long[0];
        JsonArray a = e.getAsJsonArray();
        long[] r = new long[a.size()];
        for (int i = 0; i < r.length; i++) try { r[i] = a.get(i).getAsLong(); } catch (RuntimeException ignored) { }
        return r;
    }

    /** a 위에 b 의 키를 덮어쓴 새 객체 */
    public static JsonObject merge(JsonObject a, JsonObject b) {
        JsonObject r = a == null ? new JsonObject() : a.deepCopy();
        if (b != null) for (var en : b.entrySet()) r.add(en.getKey(), en.getValue());
        return r;
    }
}
