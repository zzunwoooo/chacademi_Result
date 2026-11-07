package kr.kkaegul.client.ui;

/**
 * 게임 안 한글 입력기 (두벌식). 윈도우 입력기를 끄고 영문 키를 받아서 직접 조합한다.
 * → 조합 중인 글자가 바로 보이고(한 글자씩 늦게 뜨는 문제 없음), 윈도우 조합창도 안 뜬다.
 * 쓰는 법: feed(ch) 가 확정된 글자를 돌려주고, composing() 이 지금 조합 중인 글자.
 */
public final class HangulInput {
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] JONG = {"", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"};
    /** 쿼티 → 자모 (소문자 / 대문자는 된소리·ㅒㅖ) */
    private static final String KEYS = "qwertyuiopasdfghjklzxcvbnm";
    private static final String LOWER = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
    private static final String UPPER = "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
    /** 겹모음 / 겹받침 */
    private static final String[][] VV = {{"ㅗㅏ", "ㅘ"}, {"ㅗㅐ", "ㅙ"}, {"ㅗㅣ", "ㅚ"}, {"ㅜㅓ", "ㅝ"}, {"ㅜㅔ", "ㅞ"}, {"ㅜㅣ", "ㅟ"}, {"ㅡㅣ", "ㅢ"}};
    private static final String[][] CC = {{"ㄱㅅ", "ㄳ"}, {"ㄴㅈ", "ㄵ"}, {"ㄴㅎ", "ㄶ"}, {"ㄹㄱ", "ㄺ"}, {"ㄹㅁ", "ㄻ"}, {"ㄹㅂ", "ㄼ"}, {"ㄹㅅ", "ㄽ"}, {"ㄹㅌ", "ㄾ"}, {"ㄹㅍ", "ㄿ"}, {"ㄹㅎ", "ㅀ"}, {"ㅂㅅ", "ㅄ"}};

    private char cho, jung, jong;   // 0 = 없음 (호환 자모 글자)

    public boolean empty() { return cho == 0 && jung == 0 && jong == 0; }

    /** 영문 키를 자모로. 한글 키가 아니면 0 */
    public static char jamo(char ch) {
        int i = KEYS.indexOf(Character.toLowerCase(ch));
        if (i < 0) return 0;
        return Character.isUpperCase(ch) ? UPPER.charAt(i) : LOWER.charAt(i);
    }

    private static boolean vowel(char j) { return JUNG.indexOf(j) >= 0; }
    private static int jongIdx(char j) { for (int i = 1; i < JONG.length; i++) if (JONG[i].charAt(0) == j) return i; return -1; }

    private static char combine(String[][] t, char a, char b) {
        String k = "" + a + b;
        for (String[] p : t) if (p[0].equals(k)) return p[1].charAt(0);
        return 0;
    }

    private static char[] split(String[][] t, char c) {
        for (String[] p : t) if (p[1].charAt(0) == c) return new char[]{p[0].charAt(0), p[0].charAt(1)};
        return null;
    }

    /** 지금 조합 중인 글자 (없으면 빈 문자열) */
    public String composing() {
        if (empty()) return "";
        if (cho != 0 && jung != 0) {
            int c = CHO.indexOf(cho), v = JUNG.indexOf(jung), t = jong == 0 ? 0 : jongIdx(jong);
            return String.valueOf((char) (0xAC00 + (c * 21 + v) * 28 + Math.max(0, t)));
        }
        return String.valueOf(cho != 0 ? cho : jung);
    }

    /** 조합 끝내고 확정 글자 돌려줌 */
    public String flush() {
        String s = composing();
        cho = jung = jong = 0;
        return s;
    }

    /** 자모 하나 넣기. 확정된 글자(없으면 "")를 돌려준다 */
    public String feed(char j) {
        if (j == 0) return "";
        if (vowel(j)) {
            if (cho != 0 && jung == 0) { jung = j; return ""; }
            if (jung != 0 && jong == 0) {
                char v = combine(VV, jung, j);
                if (v != 0) { jung = v; return ""; }
                String out = flush();
                jung = j;
                return out;
            }
            if (jong != 0) {
                // 받침을 다음 글자의 첫소리로 넘김
                char[] sp = split(CC, jong);
                char move;
                if (sp != null) { jong = sp[0]; move = sp[1]; } else { move = jong; jong = 0; }
                String out = composing();
                cho = CHO.indexOf(move) >= 0 ? move : 0;
                jung = j;
                jong = 0;
                if (cho == 0) { out += String.valueOf(move); }
                return out;
            }
            jung = j;   // 빈 상태
            return "";
        }
        // 자음
        if (empty()) { cho = j; return ""; }
        if (cho != 0 && jung == 0) { String out = flush(); cho = j; return out; }
        if (cho == 0 && jung != 0) { String out = flush(); cho = j; return out; }
        if (jong == 0) {
            if (jongIdx(j) > 0) { jong = j; return ""; }
            String out = flush();
            cho = j;
            return out;
        }
        char c2 = combine(CC, jong, j);
        if (c2 != 0) { jong = c2; return ""; }
        String out = flush();
        cho = j;
        return out;
    }

    /** 따로 들어온 자모(ㅈㅜㄴㅇㅜ)를 합친 글 (준우). 자모가 아닌 글자는 그대로 */
    public static String compose(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder out = new StringBuilder();
        HangulInput h = new HangulInput();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3131 && c <= 0x3163) out.append(h.feed(c));
            else { out.append(h.flush()).append(c); }
        }
        return out.append(h.flush()).toString();
    }

    /** 지우기: 조합 중이면 마지막 자모만 지우고 true, 아니면 false (앞 글자를 지우게) */
    public boolean backspace() {
        if (empty()) return false;
        if (jong != 0) { char[] sp = split(CC, jong); jong = sp != null ? sp[0] : 0; return true; }
        if (jung != 0) { char[] sp = split(VV, jung); jung = sp != null ? sp[0] : 0; return true; }
        cho = 0;
        return true;
    }
}
