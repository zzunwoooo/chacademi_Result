package kr.kkaegul.client.ui;

import java.util.ArrayList;
import java.util.List;

/** 마인크래프트 색코드(&a, §c, &l, &r) → 색 구간 */
public final class Fmt {
    private Fmt() {}

    public record Run(String text, int rgb, boolean bold) {}

    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** 밝은 화면(크림색 패널) 위에서 읽히도록 바꾼 색. 패널 위 글자는 이걸 쓴다 */
    private static final int[] ON_LIGHT = {
            0x2B2118, 0x2B4C9C, 0x3E8E2F, 0x2A8C8C, 0xB8322B, 0x9A3FB0, 0xD27A12, 0x8A8178,
            0x6B6158, 0x4D6FD8, 0x4FA63A, 0x2E9DB0, 0xE0504A, 0xC255C9, 0xC99A10, 0x5A4636};

    public static String amp(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) b.append('§');
            else b.append(c);
        }
        return b.toString();
    }

    /** 서버 이모트 글리프(ItemsAdder 폰트 전용 문자)는 UI 폰트에 없으므로 뺀다 */
    public static boolean emote(char c) {
        return (c >= '⨀' && c <= '⫿') || (c >= '⑴' && c <= '⑿') || c == '⁜' || (c >= '' && c <= '');
    }

    public static String strip(String s) {
        if (s == null) return "";
        s = amp(s);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            if (emote(c)) continue;
            b.append(c);
        }
        return b.toString().trim().replaceAll(" {2,}", " ");
    }

    public static List<Run> parse(String s, int defaultRgb, boolean onLight) {
        List<Run> out = new ArrayList<>();
        s = amp(s == null ? "" : s);
        int color = defaultRgb;
        boolean bold = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(++i));
                int idx = "0123456789abcdef".indexOf(k);
                if (idx >= 0 || k == 'r' || k == 'l') {
                    if (cur.length() > 0) { out.add(new Run(cur.toString(), color, bold)); cur.setLength(0); }
                    if (idx >= 0) { color = (onLight ? ON_LIGHT : COLORS)[idx]; bold = false; }
                    else if (k == 'r') { color = defaultRgb; bold = false; }
                    else bold = true;
                }
                continue;
            }
            if (emote(c)) continue;
            cur.append(c);
        }
        if (cur.length() > 0) out.add(new Run(cur.toString(), color, bold));
        // 이모트를 빼고 남은 앞뒤 공백 정리
        if (!out.isEmpty()) {
            Run f = out.get(0);
            out.set(0, new Run(f.text().stripLeading(), f.rgb(), f.bold()));
            Run l = out.get(out.size() - 1);
            out.set(out.size() - 1, new Run(l.text().stripTrailing(), l.rgb(), l.bold()));
            out.removeIf(r -> r.text().isEmpty());
        }
        return out;
    }

    public static String num(long n) { return String.format("%,d", n); }

    public static String time(long seconds) {
        if (seconds <= 0) return "0초";
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        if (h > 0) return h + "시간 " + (m > 0 ? m + "분" : "");
        if (m > 0) return m + "분" + (s > 0 ? " " + s + "초" : "");
        return s + "초";
    }

    public static String clock(long seconds) {
        seconds = Math.max(0, seconds);
        return seconds >= 3600 ? String.format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
