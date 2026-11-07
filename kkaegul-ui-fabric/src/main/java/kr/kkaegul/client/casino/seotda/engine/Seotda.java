package kr.kkaegul.client.casino.seotda.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 섯다 카드 · 족보 판정 (2장 섯다). 서버 의존성 없는 순수 로직.
 * <pre>
 * 38광땡 &gt; 13·18광땡 &gt; 장땡 &gt; 9땡 … 1땡 &gt; 알리 &gt; 독사 &gt; 구삥 &gt; 장삥 &gt; 장사 &gt; 세륙 &gt; 갑오(9끗) … 1끗 &gt; 망통
 * 특수패
 *   암행어사 (4열끗 + 7열끗)  : 13·18광땡만 잡음, 그 외에는 1끗
 *   땡잡이   (3광 + 7열끗)    : 1~9땡만 잡음, 그 외에는 망통
 *   구사     (4 + 9)          : 상대 최고패가 알리 이하면 재경기, 그 외에는 3끗
 *   멍텅구리구사 (4열끗 + 9열끗): 상대 최고패가 9땡 이하면 재경기, 그 외에는 3끗
 * </pre>
 */
public final class Seotda {

    private Seotda() {
    }

    /** 화투 한 장. id 예: "01-bright", "07-boar" (클라이언트 텍스처 이름과 같음) */
    public record Card(int month, String kind) {
        public String id() {
            return String.format("%02d-%s", month, kind);
        }

        public boolean bright() {
            return kind.equals("bright");
        }

        /** 열끗 (4월 새, 7월 멧돼지, 9월 국진) */
        public boolean animal() {
            return (month == 4 && kind.equals("bird")) || (month == 7 && kind.equals("boar")) || (month == 9 && kind.equals("cup"));
        }
    }

    public enum Special { NONE, AMHAENG, TTANGJABI, GUSA, MEONG_GUSA }

    /**
     * 판정 결과.
     *
     * @param score 기본 세기 (클수록 셈)
     * @param name  족보 이름
     */
    public record Hand(Card a, Card b, int score, String name, Special special) {
        public boolean isGwang() {
            return score >= 900;
        }

        /** 1~9땡 */
        public boolean isLowTtaeng() {
            return score >= 801 && score <= 809;
        }

        public String displayName() {
            return switch (special) {
                case AMHAENG -> "암행어사";
                case TTANGJABI -> "땡잡이";
                case GUSA -> "구사";
                case MEONG_GUSA -> "멍텅구리구사";
                default -> name;
            };
        }
    }

    /** 20장 덱 */
    public static List<Card> deck() {
        String[][] kinds = {
                {"bright", "ribbon"}, {"bird", "ribbon"}, {"bright", "ribbon"}, {"bird", "ribbon"}, {"bridge", "ribbon"},
                {"butterfly", "ribbon"}, {"boar", "ribbon"}, {"bright", "geese"}, {"cup", "ribbon"}, {"deer", "ribbon"}};
        List<Card> d = new ArrayList<>(20);
        for (int m = 1; m <= 10; m++) for (String k : kinds[m - 1]) d.add(new Card(m, k));
        return d;
    }

    public static Hand eval(Card a, Card b) {
        int lo = Math.min(a.month(), b.month()), hi = Math.max(a.month(), b.month());
        // 광땡
        if (a.bright() && b.bright()) {
            if (lo == 3 && hi == 8) return new Hand(a, b, 1000, "38광땡", Special.NONE);
            if (lo == 1 && hi == 3) return new Hand(a, b, 900, "13광땡", Special.NONE);
            if (lo == 1 && hi == 8) return new Hand(a, b, 900, "18광땡", Special.NONE);
        }
        // 땡
        if (lo == hi) return new Hand(a, b, 800 + lo, lo == 10 ? "장땡" : lo + "땡", Special.NONE);
        // 특수패
        boolean animals = a.animal() && b.animal();
        if (lo == 4 && hi == 7 && animals) return new Hand(a, b, 501, "1끗", Special.AMHAENG);
        if (lo == 3 && hi == 7 && ((a.month() == 3 && a.bright() && b.animal()) || (b.month() == 3 && b.bright() && a.animal()))) {
            return new Hand(a, b, 500, "망통", Special.TTANGJABI);
        }
        if (lo == 4 && hi == 9) {
            return new Hand(a, b, 503, "3끗", animals ? Special.MEONG_GUSA : Special.GUSA);
        }
        // 중간 족보
        if (lo == 1 && hi == 2) return new Hand(a, b, 700, "알리", Special.NONE);
        if (lo == 1 && hi == 4) return new Hand(a, b, 690, "독사", Special.NONE);
        if (lo == 1 && hi == 9) return new Hand(a, b, 680, "구삥", Special.NONE);
        if (lo == 1 && hi == 10) return new Hand(a, b, 670, "장삥", Special.NONE);
        if (lo == 4 && hi == 10) return new Hand(a, b, 660, "장사", Special.NONE);
        if (lo == 4 && hi == 6) return new Hand(a, b, 650, "세륙", Special.NONE);
        // 끗
        int kkeut = (lo + hi) % 10;
        String name = kkeut == 0 ? "망통" : kkeut == 9 ? "갑오" : kkeut + "끗";
        return new Hand(a, b, 500 + kkeut, name, Special.NONE);
    }

    /** a가 b를 이기면 1, 지면 -1, 비기면 0 (특수패 반영, 재경기는 따로) */
    public static int compare(Hand a, Hand b) {
        if (a.special() == Special.AMHAENG && b.isGwang() && b.score() == 900) return 1;
        if (b.special() == Special.AMHAENG && a.isGwang() && a.score() == 900) return -1;
        if (a.special() == Special.TTANGJABI && b.isLowTtaeng()) return 1;
        if (b.special() == Special.TTANGJABI && a.isLowTtaeng()) return -1;
        return Integer.compare(a.score(), b.score());
    }

    /**
     * 승부 결과.
     *
     * @param winner  이긴 자리 (재경기면 -1)
     * @param rematch 재경기 (구사·멍텅구리구사 또는 동점)
     * @param reason  재경기 사유
     */
    public record Outcome(int winner, boolean rematch, String reason) {
    }

    /**
     * 남은 사람끼리 승부.
     *
     * @param hands  자리별 패 (접은 사람은 null)
     */
    public static Outcome showdown(Hand[] hands) {
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < hands.length; i++) if (hands[i] != null) live.add(i);
        if (live.size() == 1) return new Outcome(live.get(0), false, "");
        // 구사 재경기
        for (int i : live) {
            Hand h = hands[i];
            if (h.special() != Special.GUSA && h.special() != Special.MEONG_GUSA) continue;
            int best = 0;
            for (int j : live) if (j != i) best = Math.max(best, hands[j].score());
            if (h.special() == Special.GUSA && best <= 700) return new Outcome(-1, true, "구사");
            if (h.special() == Special.MEONG_GUSA && best <= 809) return new Outcome(-1, true, "멍텅구리구사");
        }
        // 모두를 이기는 사람
        for (int i : live) {
            boolean all = true;
            for (int j : live) if (j != i && compare(hands[i], hands[j]) <= 0) all = false;
            if (all) return new Outcome(i, false, "");
        }
        return new Outcome(-1, true, "동점");
    }

    /** 족보 표 (화면 표시용): {이름, 설명} */
    public static final String[][] BASIC = {
            {"38광땡", "3광 + 8광"}, {"13광땡 · 18광땡", "1광 + 3광 / 1광 + 8광"}, {"장땡", "10월 두 장"},
            {"9땡", "9월 두 장"}, {"8땡", "8월 두 장"}, {"7땡", "7월 두 장"}, {"6땡", "6월 두 장"}, {"5땡", "5월 두 장"},
            {"4땡", "4월 두 장"}, {"3땡", "3월 두 장"}, {"2땡", "2월 두 장"}, {"1땡", "1월 두 장"},
            {"알리", "1 + 2"}, {"독사", "1 + 4"}, {"구삥", "1 + 9"}, {"장삥", "1 + 10"}, {"장사", "4 + 10"}, {"세륙", "4 + 6"},
            {"갑오", "끗수 합 9"}, {"8끗 ~ 1끗", "끗수 합의 일의 자리"}, {"망통", "끗수 합 0"}};
    public static final String[][] SPECIAL = {
            {"암행어사", "4열끗 + 7열끗 · 13광땡과 18광땡을 잡아요 (그 외에는 1끗)"},
            {"땡잡이", "3광 + 7열끗 · 1땡~9땡을 잡아요 (그 외에는 망통)"},
            {"구사", "4 + 9 · 상대 최고패가 알리 이하면 재경기 (그 외에는 3끗)"},
            {"멍텅구리구사", "4열끗 + 9열끗 · 상대 최고패가 9땡 이하면 재경기 (그 외에는 3끗)"}};
}
