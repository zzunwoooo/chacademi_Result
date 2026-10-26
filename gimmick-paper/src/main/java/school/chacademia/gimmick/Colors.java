package school.chacademia.gimmick;

import java.util.*;

/** Prism / beam colours (246 프리즘 물들이기 cycles them in this order). */
final class Colors {
    private Colors() {}
    static final List<String> ORDER = List.of("white", "red", "yellow", "green", "blue", "purple");
    private static final Map<String, Integer> RGB = Map.of("white", 0xFFFFFF, "red", 0xFF4A3D, "yellow", 0xFFE04A, "green", 0x5BE36B,
        "blue", 0x4A9BFF, "purple", 0xB85CFF);
    private static final Map<String, String> KO = Map.of("white", "흰색", "red", "빨강", "yellow", "노랑", "green", "초록", "blue", "파랑", "purple", "보라");
    static String norm(String c) {
        if (c == null) return "white";
        String k = c.trim().toLowerCase(Locale.ROOT);
        for (var e : KO.entrySet()) if (e.getValue().equals(c.trim())) return e.getKey();
        return RGB.containsKey(k) ? k : "white";
    }
    static int rgb(String c) { return RGB.getOrDefault(norm(c), 0xFFFFFF); }
    static String ko(String c) { return KO.getOrDefault(norm(c), "흰색"); }
    static String next(String c, int step) { return ORDER.get(Math.floorMod(ORDER.indexOf(norm(c)) + step, ORDER.size())); }
}
