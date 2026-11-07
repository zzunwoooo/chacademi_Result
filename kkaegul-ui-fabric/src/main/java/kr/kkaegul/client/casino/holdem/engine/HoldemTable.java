package kr.kkaegul.client.casino.holdem.engine;

import kr.kkaegul.client.casino.holdem.engine.Poker.Card;
import kr.kkaegul.client.casino.holdem.engine.Poker.Hand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 노리밋 텍사스 홀덤 한 판 (2~3명, 자리 0~2). 서버 의존성 없는 순수 로직.
 * <ul>
 *     <li>블라인드 → 프리플롭 → 플롭 → 턴 → 리버 → 쇼다운</li>
 *     <li>3명: 딜러 다음 SB, 그다음 BB. 2명: 딜러가 SB</li>
 *     <li>프리플롭은 BB 다음부터, 이후는 딜러 다음 사람부터</li>
 *     <li>최소 레이즈 = 직전 정식 베팅/레이즈 증가분 (최소 BB). 부족한 올인은 레이즈 권한을 다시 열지 않음</li>
 *     <li>메인 팟 / 사이드 팟, 동률은 나눔 (남는 칩은 딜러 왼쪽부터)</li>
 * </ul>
 */
public final class HoldemTable {

    public enum Act { FOLD, CALL, RAISE }

    /** amount: RAISE일 때 "이번 라운드 총 N으로" */
    public record Move(Act act, long amount) {
        public static Move fold() {
            return new Move(Act.FOLD, 0);
        }

        public static Move call() {
            return new Move(Act.CALL, 0);
        }

        public static Move raiseTo(long to) {
            return new Move(Act.RAISE, to);
        }
    }

    public static final String[] STREETS = {"프리플롭", "플롭", "턴", "리버", "쇼다운"};

    /** 팟 하나의 결과 */
    public record PotResult(long amount, List<Integer> winners, long each) {
    }

    private final RandomGenerator rng;
    public final long sb, bb;
    public final boolean[] present = new boolean[3];
    public final long[] stack = new long[3];
    public final long[] bet = new long[3];      // 이번 라운드에 낸 돈
    public final long[] total = new long[3];    // 이번 판에 낸 돈
    public final boolean[] folded = new boolean[3];
    public final boolean[] allIn = new boolean[3];
    private final boolean[] acted = new boolean[3];
    private final int[] seenRaise = new int[3];
    public final String[] status = {"", "", ""};
    public final Card[][] hole = new Card[3][];
    public final List<Card> board = new ArrayList<>();
    private final List<Card> deck;
    public final int dealer, sbSeat, bbSeat;
    public int street;          // 0 프리플롭 … 3 리버, 4 끝
    public int turn = -1;
    public long currentBet;
    public long minRaiseInc;
    private int fullRaises;
    public boolean over;
    /** 끝났을 때: 자리별 받은 칩 */
    public final long[] won = new long[3];
    public List<PotResult> pots = List.of();
    public boolean showdown;
    public String lastLog = "";

    /**
     * @param stacks 자리별 칩 (0이면 빠짐)
     */
    public HoldemTable(RandomGenerator rng, long[] stacks, int dealer, long sb, long bb) {
        this.rng = rng;
        this.sb = sb;
        this.bb = bb;
        int n = 0;
        for (int i = 0; i < 3; i++) {
            present[i] = stacks[i] > 0;
            stack[i] = Math.max(0, stacks[i]);
            folded[i] = !present[i];
            acted[i] = !present[i];
            if (present[i]) n++;
        }
        if (n < 2) throw new IllegalArgumentException("2명 이상 필요");
        this.dealer = present[dealer] ? dealer : nextPresent(dealer);
        if (n == 2) {
            sbSeat = this.dealer;
            bbSeat = nextPresent(sbSeat);
        } else {
            sbSeat = nextPresent(this.dealer);
            bbSeat = nextPresent(sbSeat);
        }
        deck = Poker.deck();
        Collections.shuffle(deck, new java.util.Random(rng.nextLong()));
        int k = 0;
        for (int i = 0; i < 3; i++) if (present[i]) hole[i] = new Card[]{deck.get(k++), deck.get(k++)};
        deck.subList(0, k).clear();
        post(sbSeat, sb);
        post(bbSeat, bb);
        status[sbSeat] = "SB";
        status[bbSeat] = "BB";
        currentBet = Math.max(bet[sbSeat], bet[bbSeat]);
        minRaiseInc = bb;
        street = 0;
        turn = firstToAct(nextPresent(bbSeat));
        checkRoundEnd();
    }

    private void post(int seat, long amt) {
        long a = Math.min(amt, stack[seat]);
        stack[seat] -= a;
        bet[seat] += a;
        total[seat] += a;
        if (stack[seat] == 0) allIn[seat] = true;
    }

    private int nextPresent(int from) {
        for (int k = 1; k <= 3; k++) {
            int i = (from + k) % 3;
            if (present[i]) return i;
        }
        return from;
    }

    /** from부터 시계방향으로 행동할 수 있는 첫 자리 */
    private int firstToAct(int from) {
        for (int k = 0; k < 3; k++) {
            int i = (from + k) % 3;
            if (canAct(i) && (!acted[i] || bet[i] != currentBet)) return i;
        }
        return -1;
    }

    private boolean canAct(int i) {
        return present[i] && !folded[i] && !allIn[i];
    }

    public int activeCount() {
        int n = 0;
        for (int i = 0; i < 3; i++) if (present[i] && !folded[i]) n++;
        return n;
    }

    private int canActCount() {
        int n = 0;
        for (int i = 0; i < 3; i++) if (canAct(i)) n++;
        return n;
    }

    // ------------------------------------------------------------------ 행동 정보

    public long pot() {
        return total[0] + total[1] + total[2];
    }

    public long toCall(int seat) {
        return Math.min(stack[seat], Math.max(0, currentBet - bet[seat]));
    }

    /** 레이즈(또는 베팅) 최소 "총액" */
    public long minRaiseTo(int seat) {
        long min = currentBet == 0 ? bb : currentBet + Math.max(minRaiseInc, bb);
        return Math.min(min, bet[seat] + stack[seat]);
    }

    /** 레이즈 최대 "총액" (올인) */
    public long maxRaiseTo(int seat) {
        return bet[seat] + stack[seat];
    }

    /** 레이즈할 수 있는지 (올인 포함) */
    public boolean canRaise(int seat) {
        if (!canAct(seat)) return false;
        if (stack[seat] <= toCall(seat)) return false; // 콜하면 올인
        // 다른 사람이 모두 올인/폴드면 더 올릴 이유 없음
        boolean someoneCanRespond = false;
        for (int j = 0; j < 3; j++) if (j != seat && canAct(j)) someoneCanRespond = true;
        if (!someoneCanRespond) return false;
        // 부족한 올인 레이즈는 이미 행동한 사람에게 레이즈 권한을 다시 열지 않음
        return !acted[seat] || fullRaises > seenRaise[seat];
    }

    // ------------------------------------------------------------------ 행동

    /** 행동 적용. 낸 칩을 반환 */
    public long apply(int seat, Move m) {
        if (over || seat != turn) return 0;
        long paid = 0;
        switch (m.act()) {
            case FOLD -> {
                if (toCall(seat) == 0) {
                    // 체크할 수 있으면 폴드 대신 체크
                    status[seat] = "체크";
                } else {
                    folded[seat] = true;
                    status[seat] = "폴드";
                }
            }
            case CALL -> {
                long c = toCall(seat);
                stack[seat] -= c;
                bet[seat] += c;
                total[seat] += c;
                paid = c;
                if (stack[seat] == 0) allIn[seat] = true;
                status[seat] = allIn[seat] && c > 0 ? "올인" : c > 0 ? "콜" : "체크";
            }
            case RAISE -> {
                if (!canRaise(seat)) return apply(seat, Move.call());
                long to = Math.max(minRaiseTo(seat), Math.min(maxRaiseTo(seat), m.amount()));
                long add = to - bet[seat];
                stack[seat] -= add;
                bet[seat] += add;
                total[seat] += add;
                paid = add;
                if (stack[seat] == 0) allIn[seat] = true;
                long inc = to - currentBet;
                boolean wasBet = currentBet == 0;
                if (to > currentBet) {
                    if (inc >= Math.max(minRaiseInc, bb) || (wasBet && to >= bb)) {
                        minRaiseInc = Math.max(inc, bb);
                        fullRaises++;
                    }
                    currentBet = to;
                    for (int j = 0; j < 3; j++) if (j != seat && canAct(j)) acted[j] = false;
                }
                status[seat] = allIn[seat] ? "올인" : wasBet ? "베팅" : "레이즈";
            }
        }
        acted[seat] = true;
        seenRaise[seat] = fullRaises;
        turn = firstToAct((seat + 1) % 3);
        checkRoundEnd();
        return paid;
    }

    /** 자리 비움 (나감 · 접속 끊김): 폴드 */
    public void forceFold(int seat) {
        if (over || folded[seat] || !present[seat]) return;
        folded[seat] = true;
        acted[seat] = true;
        status[seat] = "폴드";
        if (turn == seat) turn = firstToAct((seat + 1) % 3);
        checkRoundEnd();
    }

    private void checkRoundEnd() {
        if (over) return;
        if (activeCount() <= 1) {
            finish(false);
            return;
        }
        boolean roundDone = turn < 0;
        if (!roundDone) return;
        // 한 명 빼고 모두 올인이면 남은 카드 공개 후 바로 쇼다운
        if (canActCount() <= 1) {
            while (board.size() < 5) dealStreetCards();
            street = 3;
            finish(true);
            return;
        }
        nextStreet();
    }

    private void dealStreetCards() {
        int n = board.isEmpty() ? 3 : 1;
        for (int i = 0; i < n; i++) board.add(deck.remove(0));
    }

    private void nextStreet() {
        if (street >= 3) {
            finish(true);
            return;
        }
        street++;
        dealStreetCards();
        for (int i = 0; i < 3; i++) {
            bet[i] = 0;
            acted[i] = !canAct(i);
            if (canAct(i)) status[i] = "";
        }
        currentBet = 0;
        minRaiseInc = bb;
        turn = firstToAct(nextPresent(dealer));
        if (turn < 0) checkRoundEnd();
    }

    /** 지금 보드 기준 최선의 족보 (보드 3장 이상일 때) */
    public Hand handOf(int seat) {
        if (hole[seat] == null || board.size() < 3) return null;
        List<Card> c = new ArrayList<>(board);
        c.add(hole[seat][0]);
        c.add(hole[seat][1]);
        return Poker.best(c);
    }

    // ------------------------------------------------------------------ 정산

    private void finish(boolean show) {
        over = true;
        turn = -1;
        street = 4;
        showdown = show;
        List<PotResult> list = new ArrayList<>();
        if (!show) {
            int w = -1;
            for (int i = 0; i < 3; i++) if (present[i] && !folded[i]) w = i;
            long p = pot();
            won[w] += p;
            stack[w] += p;
            list.add(new PotResult(p, List.of(w), p));
            pots = list;
            return;
        }
        long[] value = new long[3];
        for (int i = 0; i < 3; i++) if (present[i] && !folded[i]) value[i] = Poker.value(withBoard(i));
        // 사이드 팟: 낸 돈 단계별로
        List<Long> levels = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (present[i] && total[i] > 0 && !levels.contains(total[i])) levels.add(total[i]);
        Collections.sort(levels);
        long prev = 0;
        for (long lv : levels) {
            long amount = 0;
            for (int i = 0; i < 3; i++) amount += Math.max(0, Math.min(total[i], lv) - prev);
            List<Integer> elig = new ArrayList<>();
            for (int i = 0; i < 3; i++) if (present[i] && !folded[i] && total[i] >= lv) elig.add(i);
            if (amount > 0) {
                if (elig.isEmpty()) {
                    // 모두 폴드한 단계 (이론상 거의 없음): 그 단계에 낸 사람 중 남은 사람에게
                    for (int i = 0; i < 3; i++) if (present[i] && !folded[i]) elig.add(i);
                }
                long best = -1;
                for (int i : elig) best = Math.max(best, value[i]);
                List<Integer> winners = new ArrayList<>();
                // 딜러 왼쪽부터 순서대로 (남는 칩 우선)
                for (int k = 1; k <= 3; k++) {
                    int i = (dealer + k) % 3;
                    if (elig.contains(i) && value[i] == best) winners.add(i);
                }
                long each = amount / winners.size();
                long rest = amount - each * winners.size();
                for (int w : winners) {
                    long get = each + (rest > 0 ? 1 : 0);
                    if (rest > 0) rest--;
                    won[w] += get;
                    stack[w] += get;
                }
                list.add(new PotResult(amount, winners, each));
            }
            prev = lv;
        }
        pots = list;
    }

    private List<Card> withBoard(int seat) {
        List<Card> c = new ArrayList<>(board);
        c.add(hole[seat][0]);
        c.add(hole[seat][1]);
        return c;
    }

    // ------------------------------------------------------------------ AI용

    /** 남은 덱 (AI 계산용, 복사본) */
    public List<Card> remainingDeck() {
        return new ArrayList<>(deck);
    }
}
