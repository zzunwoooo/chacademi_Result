package school.magiccodex.client.kui;

/** Colours and names shared by the 큐디치 views (sampled from the approved reference images). */
public final class Theme {
    private Theme() {}
    public static final int GOLD = 0xFFF2D28A, GOLD_DEEP = 0xFFE3BC6A, WHITE = 0xFFF3EEE3, CREAM = 0xFFE6DCC6, MUTED = 0xFFA9B4C2,
            BLUE = 0xFF79B6FF, RED = 0xFFFF7A72, TEAL = 0xFF79E8D6, GREEN = 0xFF7EE08D, DARK_TEXT = 0xFF2A1C08, LOSS = 0xFFFF8A80;
    /** Houses in Bridge order: 아르케온 · 루미나 · 베스티아즈 · 노크세르. */
    public static final String[] HOUSE_NAMES = {"아르케온", "루미나", "베스티아즈", "노크세르"};
    public static final String[] HOUSE_PARTS = {"houses/star", "houses/moon", "houses/leaf", "houses/sun"};
    public static final int[] HOUSE_COLORS = {0xFFFF6A5C, 0xFF7FB8FF, 0xFF79DB72, 0xFFFFD45A};
    public static final String[] TIERS = {"bronze", "silver", "gold", "platinum", "diamond", "master", "challenger_1", "challenger_2", "challenger_3"};
    public static final String[] TIER_NAMES = {"브론즈", "실버", "골드", "플래티넘", "다이아몬드", "마스터", "챌린저 1", "챌린저 2", "챌린저 3"};
    /** House crest whose drawn shield is about artHeight tall, centred on (cx, cy); the PNGs carry ~20% transparent padding. */
    public static void crest(Pen p, int house, float cx, float cy, float artHeight) {
        if (house < 0 || house > 3) return;
        float size = artHeight * 192f / 152f;
        p.image(HOUSE_PARTS[house], cx - size / 2, cy - size / 2, size, size);
    }
    /** Art boxes (x0, y0, x1, y1) inside icons/* (128) and medals/* (256), owner EXTRA_PNG. */
    private static final java.util.Map<String, int[]> ART = java.util.Map.ofEntries(
            java.util.Map.entry("icons/arena", new int[]{13, 12, 117, 108}), java.util.Map.entry("icons/bar_chart", new int[]{18, 21, 112, 113}),
            java.util.Map.entry("icons/cancel", new int[]{14, 13, 116, 113}), java.util.Map.entry("icons/clock", new int[]{11, 12, 114, 113}),
            java.util.Map.entry("icons/crossed_swords", new int[]{14, 16, 117, 118}), java.util.Map.entry("icons/crown", new int[]{9, 14, 121, 108}),
            java.util.Map.entry("icons/hourglass", new int[]{30, 13, 97, 115}), java.util.Map.entry("icons/party", new int[]{8, 31, 119, 101}),
            java.util.Map.entry("icons/range_slider", new int[]{9, 52, 120, 79}), java.util.Map.entry("icons/search", new int[]{12, 8, 118, 114}),
            java.util.Map.entry("icons_line/bar_chart_silver", new int[]{22, 21, 122, 94}), java.util.Map.entry("icons_line/clock", new int[]{18, 21, 103, 106}),
            java.util.Map.entry("icons_line/crossed_swords_silver", new int[]{22, 12, 110, 100}), java.util.Map.entry("icons_line/range_slider", new int[]{6, 19, 122, 107}),
            java.util.Map.entry("medals/rank_1", new int[]{9, 27, 246, 211}), java.util.Map.entry("medals/rank_2", new int[]{11, 35, 248, 214}),
            java.util.Map.entry("medals/rank_3", new int[]{8, 34, 248, 216}));
    /** Draws an icon so its visible art fits w×h centred on (cx, cy) (the PNG's transparent padding is ignored). */
    public static void icon(Pen p, String part, float cx, float cy, float w, float h) {
        int[] a = ART.get(part), size = Parts.size(part);
        float aw = a[2] - a[0], ah = a[3] - a[1], s = Math.min(w / aw, h / ah);
        p.image(part, cx - (a[0] + aw / 2) * s, cy - (a[1] + ah / 2) * s, size[0] * s, size[1] * s);
    }
    public static String tier(int t) { return "tiers/" + TIERS[Math.clamp(t, 0, 8)]; }
    public static String time(int ms) { int s = Math.max(0, ms) / 1000; return s / 60 + ":" + String.format("%02d", s % 60); }
    public static String time2(int ms) { int s = Math.max(0, ms) / 1000; return String.format("%02d:%02d", s / 60, s % 60); }
    public static String number(long v) { return String.format("%,d", v); }
}
