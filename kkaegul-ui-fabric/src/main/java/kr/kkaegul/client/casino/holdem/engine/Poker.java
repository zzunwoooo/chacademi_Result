package kr.kkaegul.client.casino.holdem.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 트럼프 카드 · 텍사스 홀덤 족보 판정 (7장 중 최선의 5장). 서버 의존성 없는 순수 로직.
 */
public final class Poker {

    private Poker() {
    }

    public static final String[] SUITS = {"spades", "hearts", "diamonds", "clubs"};

    /**
     * 카드. rank 2~14 (14 = A), suit 0~3.
     */
    public record Card(int rank, int suit) {
        /** 클라이언트 텍스처 id: "spades-01"(A) ~ "spades-13"(K) */
        public String id() {
            int n = rank == 14 ? 1 : rank;
            return SUITS[suit] + "-" + (n < 10 ? "0" : "") + n;
        }

        public int index() {
            return suit * 13 + (rank - 2);
        }
    }

    public static List<Card> deck() {
        List<Card> d = new ArrayList<>(52);
        for (int s = 0; s < 4; s++) for (int r = 2; r <= 14; r++) d.add(new Card(r, s));
        return d;
    }

    public static final String[] CATEGORY = {"하이카드", "원페어", "투페어", "트리플", "스트레이트", "플러시", "풀하우스", "포카드",
            "스트레이트 플러시", "로열 플러시"};

    /**
     * 판정 결과.
     *
     * @param value 클수록 셈 (같으면 무승부)
     * @param cat   족보 번호 (CATEGORY)
     * @param best  최선의 5장
     */
    public record Hand(long value, int cat, Card[] best) {
        public String name() {
            return CATEGORY[cat];
        }
    }

    /** 5~7장 중 최선의 5장 */
    public static Hand best(List<Card> cards) {
        int n = cards.size();
        Card[] arr = cards.toArray(new Card[0]);
        long bestV = -1;
        Card[] bestC = null;
        int bestCat = 0;
        int[] idx = new int[5];
        // n C 5 조합
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                for (int c = b + 1; c < n; c++)
                    for (int d = c + 1; d < n; d++)
                        for (int e = d + 1; e < n; e++) {
                            idx[0] = a;
                            idx[1] = b;
                            idx[2] = c;
                            idx[3] = d;
                            idx[4] = e;
                            Card[] five = {arr[a], arr[b], arr[c], arr[d], arr[e]};
                            long v = eval5(five);
                            if (v > bestV) {
                                bestV = v;
                                bestC = five;
                                bestCat = (int) (v >> 24);
                            }
                        }
        return new Hand(bestV, bestCat, bestC);
    }

    /** 빠른 값만 (AI 계산용) */
    public static long value(List<Card> cards) {
        int n = cards.size();
        Card[] arr = cards.toArray(new Card[0]);
        long bestV = -1;
        Card[] five = new Card[5];
        for (int a = 0; a < n; a++)
            for (int b = a + 1; b < n; b++)
                for (int c = b + 1; c < n; c++)
                    for (int d = c + 1; d < n; d++)
                        for (int e = d + 1; e < n; e++) {
                            five[0] = arr[a];
                            five[1] = arr[b];
                            five[2] = arr[c];
                            five[3] = arr[d];
                            five[4] = arr[e];
                            long v = eval5(five);
                            if (v > bestV) bestV = v;
                        }
        return bestV;
    }

    /** 5장 값: 족보(4비트) << 24 | 숫자 5개(각 4비트) */
    static long eval5(Card[] c) {
        int[] cnt = new int[15];
        boolean flush = true;
        for (int i = 0; i < 5; i++) {
            cnt[c[i].rank()]++;
            if (c[i].suit() != c[0].suit()) flush = false;
        }
        // 스트레이트
        int straightHigh = 0;
        for (int hi = 14; hi >= 5; hi--) {
            boolean ok = true;
            for (int k = 0; k < 5; k++) {
                int r = hi - k;
                if (r == 1) r = 14;
                if (cnt[r] != 1) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                straightHigh = hi;
                break;
            }
        }
        // 많이 나온 순, 같은 개수면 높은 숫자 순
        int[] order = new int[5];
        int k = 0;
        for (int times = 4; times >= 1; times--)
            for (int r = 14; r >= 2; r--)
                if (cnt[r] == times) order[k++] = r;
        int cat;
        if (straightHigh > 0 && flush) cat = straightHigh == 14 ? 9 : 8;
        else if (cnt[order[0]] == 4) cat = 7;
        else if (cnt[order[0]] == 3 && cnt[order[1]] == 2) cat = 6;
        else if (flush) cat = 5;
        else if (straightHigh > 0) cat = 4;
        else if (cnt[order[0]] == 3) cat = 3;
        else if (cnt[order[0]] == 2 && cnt[order[1]] == 2) cat = 2;
        else if (cnt[order[0]] == 2) cat = 1;
        else cat = 0;
        long v = (long) cat << 24;
        if (cat == 4 || cat == 8 || cat == 9) return v | ((long) straightHigh << 20);
        int shift = 20;
        for (int i = 0; i < k; i++) {
            v |= (long) order[i] << shift;
            shift -= 4;
        }
        return v;
    }

    public static String rankName(int r) {
        return switch (r) {
            case 14 -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> Integer.toString(r);
        };
    }
}
