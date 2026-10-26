package kr.chacademy.motion.broom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .bbmodel 을 읽기 위한 아주 작은 JSON 파서.
 * 결과: Map(객체) / List(배열) / String / Double / Boolean / null.
 * 게임 밖(단위 테스트)에서도 돌릴 수 있게 외부 라이브러리를 쓰지 않는다.
 */
public final class BbJson {
    private final String s;
    private int i;

    private BbJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        var p = new BbJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("끝에 남는 글자");
        return v;
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON " + msg + " (위치 " + i + ")");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("값이 없음");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw err("알 수 없는 값");
        i += w.length();
        return v;
    }

    private Map<String, Object> object() {
        var m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("',' 또는 '}' 가 필요");
        }
    }

    private List<Object> array() {
        var l = new ArrayList<Object>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw err("',' 또는 ']' 가 필요");
        }
    }

    private String string() {
        expect('"');
        var b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("문자열이 닫히지 않음");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("숫자가 필요");
        return Double.parseDouble(s.substring(start, i));
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw err("갑자기 끝남");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw err("'" + c + "' 가 필요");
    }

    // ---- 꺼내기 도우미 ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** 숫자 또는 숫자 문자열("0\n" 같은 Blockbench 값 포함)을 double 로. */
    public static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static float[] vec3(Object o, float def) {
        var l = arr(o);
        return new float[]{
                l.size() > 0 ? (float) num(l.get(0), def) : def,
                l.size() > 1 ? (float) num(l.get(1), def) : def,
                l.size() > 2 ? (float) num(l.get(2), def) : def};
    }
}
