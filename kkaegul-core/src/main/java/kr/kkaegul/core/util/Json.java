package kr.kkaegul.core.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 라이브러리 없이 쓰는 아주 작은 JSON 빌더 (UI 브리지 페이로드용).
 * Map, Collection, 배열, Number, Boolean, String, null, Json 을 지원한다.
 */
public final class Json {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public static Json obj() { return new Json(); }

    public Json put(String k, Object v) { map.put(k, v); return this; }

    public Map<String, Object> map() { return map; }

    @Override public String toString() { return write(map); }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Json j) { write(sb, j.map); return; }
        if (o instanceof Number || o instanceof Boolean) {
            if (o instanceof Double d && (d.isNaN() || d.isInfinite())) { sb.append("0"); return; }
            sb.append(o); return;
        }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) { if (!first) sb.append(','); first = false; write(sb, x); }
            sb.append(']');
            return;
        }
        if (o instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); write(sb, arr[i]); }
            sb.append(']');
            return;
        }
        if (o instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
            return;
        }
        str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** 클라이언트→서버 액션 파싱용: 평평한 {"k":"v","n":1} 객체만 지원 */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i++;
            if (i >= s.length()) break;
            String key;
            if (s.charAt(i) == '"') { int[] end = new int[1]; key = readStr(s, i, end); i = end[0]; }
            else { int j = s.indexOf(':', i); if (j < 0) break; key = s.substring(i, j).trim(); i = j; }
            while (i < s.length() && s.charAt(i) != ':') i++;
            i++;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            String val;
            if (i < s.length() && s.charAt(i) == '"') { int[] end = new int[1]; val = readStr(s, i, end); i = end[0]; }
            else { int j = i; while (j < s.length() && s.charAt(j) != ',') j++; val = s.substring(i, j).trim(); i = j; }
            out.put(key, val);
        }
        return out;
    }

    private static String readStr(String s, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'u' -> { if (i + 5 < s.length()) { sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 4; } }
                    default -> sb.append(n);
                }
                i += 2;
                continue;
            }
            if (c == '"') { i++; break; }
            sb.append(c);
            i++;
        }
        endOut[0] = i;
        return sb.toString();
    }
}
