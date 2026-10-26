package kr.chacademi.npcmodel.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 작은 JSON 파서 (glTF 청크·models.json 용). 마인크래프트 의존이 없어 단위 테스트에서도 그대로 쓴다.
 * 객체 → {@link LinkedHashMap}, 배열 → {@link ArrayList}, 숫자 → {@link Double}, 그 외 String/Boolean/null.
 * 깊이·길이 상한을 둬서 깨진 파일이 스택을 넘기지 않게 한다.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw p.err("JSON 뒤에 남는 글자");
        }
        return v;
    }

    private ModelFormatException err(String why) {
        return new ModelFormatException("JSON 오류(" + i + "): " + why);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else {
                break;
            }
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw err("너무 깊음");
        }
        if (i >= s.length()) {
            throw err("값 없음");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                lit("true");
                return Boolean.TRUE;
            case 'f':
                lit("false");
                return Boolean.FALSE;
            case 'n':
                lit("null");
                return null;
            default:
                return number();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) {
            throw err("알 수 없는 값");
        }
        i += w.length();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw err("키는 문자열이어야 함");
            }
            String k = string();
            ws();
            if (peek() != ':') {
                throw err("':' 없음");
            }
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("',' 또는 '}' 없음");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            char c = peek();
            i++;
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("',' 또는 ']' 없음");
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err("끝에 도달");
        }
        return s.charAt(i);
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw err("문자열이 닫히지 않음");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = peek();
                i++;
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw err("\\u 짧음");
                        }
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("\\u 형식");
                        }
                        i += 4;
                        break;
                    default:
                        throw err("알 수 없는 이스케이프");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        if (st == i) {
            throw err("숫자 아님");
        }
        try {
            return Double.parseDouble(s.substring(st, i));
        } catch (NumberFormatException ex) {
            throw err("숫자 형식");
        }
    }

    // ---- 쓰기 (models.json 저장용, 2칸 들여쓰기)

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int ind) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            b.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            b.append('"');
        } else if (v instanceof Double d) {
            b.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.valueOf(d));
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                b.append("  ".repeat(ind + 1));
                write(b, String.valueOf(e.getKey()), ind + 1);
                b.append(": ");
                write(b, e.getValue(), ind + 1);
                b.append(++i < m.size() ? ",\n" : "\n");
            }
            b.append("  ".repeat(ind)).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                write(b, l.get(i), ind + 1);
                if (i + 1 < l.size()) {
                    b.append(", ");
                }
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("JSON 으로 쓸 수 없는 값: " + v.getClass());
        }
    }

    // ---- 읽기 도우미 (형식이 틀리면 ModelFormatException)

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String what) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        throw new ModelFormatException(what + ": 객체가 아님");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String what) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List) {
            return (List<Object>) o;
        }
        throw new ModelFormatException(what + ": 배열이 아님");
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Double d) || d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new ModelFormatException(key + ": 정수가 아님");
        }
        return d.intValue();
    }

    public static int requireInt(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) {
            throw new ModelFormatException(key + " 없음");
        }
        return integer(m, key, 0);
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Double d) || !Double.isFinite(d)) {
            throw new ModelFormatException(key + ": 숫자가 아님");
        }
        return d;
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof String str)) {
            throw new ModelFormatException(key + ": 문자열이 아님");
        }
        return str;
    }

    public static float[] floats(Object o, int expected, String what) {
        List<Object> l = arr(o, what);
        if (expected >= 0 && l.size() != expected) {
            throw new ModelFormatException(what + ": 길이 " + expected + " 이어야 함");
        }
        float[] f = new float[l.size()];
        for (int k = 0; k < f.length; k++) {
            if (!(l.get(k) instanceof Double d) || !Double.isFinite(d)) {
                throw new ModelFormatException(what + ": 숫자가 아님");
            }
            f[k] = d.floatValue();
        }
        return f;
    }
}
