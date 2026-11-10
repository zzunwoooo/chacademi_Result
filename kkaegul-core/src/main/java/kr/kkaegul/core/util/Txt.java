package kr.kkaegul.core.util;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 문자열 유틸: & 색코드 변환, {placeholder} 치환, 숫자 포맷. */
public final class Txt {
    private Txt() {}

    private static final DecimalFormat NUM = new DecimalFormat("#,###");

    private static final java.util.regex.Pattern GRADIENT = java.util.regex.Pattern.compile("<#([0-9a-fA-F]{6})>(.*?)</#([0-9a-fA-F]{6})>");

    /** 그라데이션 태그 <#RRGGBB>글자</#RRGGBB> → 글자마다 §x§R§R§G§G§B§B (마크 기본 RGB 색코드) */
    public static String gradient(String s) {
        if (s == null || s.indexOf("<#") < 0) return s;
        java.util.regex.Matcher m = GRADIENT.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1), 16), z = Integer.parseInt(m.group(3), 16);
            String t = m.group(2).replaceAll("[&§][0-9a-fA-FrR]", "");
            StringBuilder g = new StringBuilder();
            int n = t.codePointCount(0, t.length()), i = 0;
            for (int off = 0; off < t.length(); ) {
                int cp = t.codePointAt(off);
                float f = n <= 1 ? 0 : (float) i / (n - 1);
                int r = Math.round(((a >> 16) & 255) + (((z >> 16) & 255) - ((a >> 16) & 255)) * f);
                int gg = Math.round(((a >> 8) & 255) + (((z >> 8) & 255) - ((a >> 8) & 255)) * f);
                int bb = Math.round((a & 255) + ((z & 255) - (a & 255)) * f);
                String hex = String.format("%02x%02x%02x", r, gg, bb);
                g.append("§x");
                for (char h : hex.toCharArray()) g.append('§').append(h);
                g.appendCodePoint(cp);
                off += Character.charCount(cp);
                i++;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(g.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }


    /** ItemsAdder 이모트 글리프: {e:right} → ⨃ (config emote.symbols) */
    public static final java.util.Map<String, String> EMOTES = new java.util.concurrent.ConcurrentHashMap<>();

    public static String emotes(String s) {
        if (s == null || s.indexOf("{e:") < 0) return s;
        for (java.util.Map.Entry<String, String> e : EMOTES.entrySet()) s = s.replace("{e:" + e.getKey() + "}", e.getValue());
        return s.replaceAll("\\{e:[a-z0-9_]+}", "");
    }

    public static String color(String s) {
        if (s == null) return "";
        s = gradient(emotes(s));
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b).replace("§l", "");   // 굵게는 쓰지 않음 (커스텀 폰트에서 두 겹으로 보임)
    }

    public static List<String> color(List<String> list) {
        List<String> out = new ArrayList<>();
        if (list != null) for (String s : list) out.add(color(s));
        return out;
    }

    /**
     * 서버 알림 통일: 앞에 파란 느낌표(system) + 흰 글씨. 원래 회색(&7/&8)으로 시작하던 문장은 회색.
     * 문장 안 색코드는 모두 뗌. 오류 문장(&c/&4 로 시작, {e:error})은 error() 로.
     */
    public static String notice(String raw) {
        if (raw == null) return "";
        if (isError(raw)) return error(raw);
        String r = raw.replaceAll("\\{e:(system|error)}", "").trim();
        boolean dim = firstColor(r) == '7' || firstColor(r) == '8';
        return "&f{e:system} " + (dim ? "&7" : "&f") + strip(r).trim();
    }

    /** 오류 알림: 빨간 느낌표 + 빨간 글씨 */
    public static String error(String raw) {
        if (raw == null) return "";
        String r = raw.replaceAll("\\{e:(system|error)}", "").trim();
        return "&f{e:error} &c" + strip(r).trim();
    }

    public static boolean isError(String raw) {
        if (raw == null) return false;
        if (raw.contains("{e:error}")) return true;
        char c = firstColor(raw.replaceAll("\\{e:[a-z0-9_]+}", "").trim());
        return c == 'c' || c == '4';
    }

    private static char firstColor(String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            char a = s.charAt(i);
            if (a == '&' || a == '§') {
                char k = Character.toLowerCase(s.charAt(i + 1));
                if ("0123456789abcdef".indexOf(k) >= 0) return k;
                i++;
                continue;
            }
            if (!Character.isWhitespace(a)) return 0;   // 글자가 먼저 나오면 색 없음
        }
        return 0;
    }

    public static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("</?#[0-9a-fA-F]{6}>", "").replaceAll("(?i)[§&][0-9A-FK-ORX]", "");
    }

    /** "{name}님" + ("name","준우") 형식 치환 */
    public static String fmt(String template, Object... kv) {
        if (template == null) return "";
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return out;
    }

    public static String fmt(String template, Map<String, ?> vars) {
        String out = template == null ? "" : template;
        for (Map.Entry<String, ?> e : vars.entrySet()) out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        return out;
    }

    public static String num(long n) { synchronized (NUM) { return NUM.format(n); } }

    public static String duration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + "시간 " + m + "분";
        if (m > 0) return m + "분 " + s + "초";
        return s + "초";
    }

    // ───────── 따로 들어온 한글 자모 합치기 ("ㅈㅜㄴㅇㅜ" → "준우") ─────────
    // 윈도우 입력기·한글 모드가 꼬이면 클라이언트가 조합 안 된 자모를 그대로 보낸다. 서버에서 한 번 더 합쳐 준다.
    // 자음만 있는 "ㅋㅋㅋ", "ㄱㄱ", 모음만 있는 "ㅠㅠ" 는 그대로 남는다.
    private static final String J_CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String J_JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String J_JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    private static final String[][] J_VV = {{"ㅗㅏ", "ㅘ"}, {"ㅗㅐ", "ㅙ"}, {"ㅗㅣ", "ㅚ"}, {"ㅜㅓ", "ㅝ"}, {"ㅜㅔ", "ㅞ"}, {"ㅜㅣ", "ㅟ"}, {"ㅡㅣ", "ㅢ"}};
    private static final String[][] J_CC = {{"ㄱㅅ", "ㄳ"}, {"ㄴㅈ", "ㄵ"}, {"ㄴㅎ", "ㄶ"}, {"ㄹㄱ", "ㄺ"}, {"ㄹㅁ", "ㄻ"}, {"ㄹㅂ", "ㄼ"}, {"ㄹㅅ", "ㄽ"}, {"ㄹㅌ", "ㄾ"}, {"ㄹㅍ", "ㄿ"}, {"ㄹㅎ", "ㅀ"}, {"ㅂㅅ", "ㅄ"}};

    private static char jComb(String[][] t, char a, char b) { String k = "" + a + b; for (String[] x : t) if (x[0].equals(k)) return x[1].charAt(0); return 0; }
    private static char[] jSplit(String[][] t, char c) { for (String[] x : t) if (x[1].charAt(0) == c) return new char[]{x[0].charAt(0), x[0].charAt(1)}; return null; }

    /** 자모가 섞여 있으면 합친다. 자모가 없으면 그대로 돌려준다. */
    public static String jamo(String in) {
        if (in == null || in.isEmpty()) return in;
        boolean any = false;
        for (int i = 0; i < in.length(); i++) { char c = in.charAt(i); if (c >= 0x3131 && c <= 0x3163) { any = true; break; } }
        if (!any) return in;
        StringBuilder out = new StringBuilder();
        char[] st = new char[3];   // cho, jung, jong (0 = 없음)
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c < 0x3131 || c > 0x3163) { jFlush(out, st); out.append(c); continue; }
            boolean vowel = J_JUNG.indexOf(c) >= 0;
            if (vowel) {
                if (st[0] != 0 && st[1] == 0) { st[1] = c; continue; }
                if (st[1] != 0 && st[2] == 0) {
                    char v = jComb(J_VV, st[1], c);
                    if (v != 0) { st[1] = v; continue; }
                    jFlush(out, st); st[1] = c; continue;
                }
                if (st[2] != 0) {   // 받침을 다음 글자 첫소리로
                    char[] sp = jSplit(J_CC, st[2]);
                    char move;
                    if (sp != null) { st[2] = sp[0]; move = sp[1]; } else { move = st[2]; st[2] = 0; }
                    jFlush(out, st);
                    st[0] = J_CHO.indexOf(move) >= 0 ? move : 0;
                    if (st[0] == 0) out.append(move);
                    st[1] = c;
                    continue;
                }
                jFlush(out, st); st[1] = c; continue;
            }
            // 자음
            if (st[0] != 0 && st[1] != 0) {
                if (st[2] == 0) { if (J_JONG.indexOf(c) > 0) { st[2] = c; continue; } }
                else { char cc = jComb(J_CC, st[2], c); if (cc != 0) { st[2] = cc; continue; } }
            }
            jFlush(out, st);
            if (J_CHO.indexOf(c) >= 0) st[0] = c; else out.append(c);
        }
        jFlush(out, st);
        return out.toString();
    }

    private static void jFlush(StringBuilder out, char[] st) {
        if (st[0] != 0 && st[1] != 0) {
            int c = J_CHO.indexOf(st[0]), v = J_JUNG.indexOf(st[1]), t = st[2] == 0 ? 0 : J_JONG.indexOf(st[2]);
            out.append((char) (0xAC00 + (c * 21 + v) * 28 + Math.max(0, t)));
        } else {
            if (st[0] != 0) out.append(st[0]);
            if (st[1] != 0) out.append(st[1]);
            if (st[2] != 0) out.append(st[2]);
        }
        st[0] = st[1] = st[2] = 0;
    }
}
