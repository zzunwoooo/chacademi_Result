package kr.kkaegul.core.casino.seotda;

import kr.kkaegul.core.casino.seotda.Seotda.Card;
import kr.kkaegul.core.casino.seotda.Seotda.Hand;
import kr.kkaegul.core.casino.seotda.Seotda.Outcome;
import kr.kkaegul.core.casino.seotda.SeotdaTable.Act;
import kr.kkaegul.core.casino.seotda.SeotdaTable.Move;

import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 플레이어끼리 하는 섯다 한 판 (주작 없음, 2~3명). 자리 0~2, 빈 자리는 처음부터 다이 상태.
 */
public final class PvpTable {

    private final RandomGenerator rng;
    public final long ante;
    private final int maxRaiseMult;
    private final int maxRaises;

    public final boolean[] present = new boolean[3];
    public final long[] put = new long[3];
    public final boolean[] folded = new boolean[3];
    public final long[] bank = new long[3];
    public Hand[] hands = new Hand[3];
    public final String[] status = {"", "", ""};
    private final boolean[] acted = new boolean[3];
    public int turn;
    public int raisesLeft;
    public int rematches;
    /** 먼저 할 자리 (판마다 돌아감) */
    private final int first;

    /**
     * @param seats 자리마다 참가 여부
     * @param banks 자리마다 판돈을 내고 남은 보유금 (레이즈 한도)
     */
    public PvpTable(RandomGenerator rng, long ante, boolean[] seats, long[] banks, int first, int maxRaiseMult, int maxRaises) {
        this.rng = rng;
        this.ante = ante;
        this.maxRaiseMult = maxRaiseMult;
        this.maxRaises = maxRaises;
        for (int i = 0; i < 3; i++) {
            present[i] = seats[i];
            folded[i] = !seats[i];
            put[i] = seats[i] ? ante : 0;
            bank[i] = seats[i] ? Math.max(0, banks[i]) : 0;
        }
        this.first = first;
        deal();
    }

    private void deal() {
        List<Card> deck = Seotda.deck();
        Collections.shuffle(deck, new java.util.Random(rng.nextLong()));
        int k = 0;
        for (int i = 0; i < 3; i++) {
            if (folded[i]) {
                if (!present[i]) hands[i] = null;
                acted[i] = true;
                continue;
            }
            hands[i] = Seotda.eval(deck.get(k), deck.get(k + 1));
            k += 2;
            acted[i] = false;
            status[i] = "";
        }
        raisesLeft = maxRaises;
        turn = next(first);
    }

    public long maxPut() {
        return Math.max(put[0], Math.max(put[1], put[2]));
    }

    public long pot() {
        return put[0] + put[1] + put[2];
    }

    public long toCall(int seat) {
        return maxPut() - put[seat];
    }

    public int activeCount() {
        int n = 0;
        for (boolean f : folded) if (!f) n++;
        return n;
    }

    public long minRaise() {
        return ante;
    }

    /** 올릴 수 있는 최대: 내 보유금과, 남은 사람 모두가 받을 수 있는 만큼 */
    public long maxRaiseFor(int seat) {
        long m = Math.min(ante * maxRaiseMult, bank[seat] - toCall(seat));
        for (int j = 0; j < 3; j++) {
            if (j == seat || folded[j]) continue;
            m = Math.min(m, bank[j] - toCall(j));
        }
        return m;
    }

    public boolean canRaise(int seat) {
        return raisesLeft > 0 && !folded[seat] && maxRaiseFor(seat) >= minRaise();
    }

    public boolean bettingOver() {
        if (activeCount() <= 1) return true;
        for (int i = 0; i < 3; i++) {
            if (folded[i]) continue;
            if (!acted[i] || put[i] != maxPut()) return false;
        }
        return true;
    }

    /** 이 행동에 필요한 돈 */
    public long cost(int seat, Move m) {
        return switch (m.act()) {
            case DIE -> 0;
            case CALL -> toCall(seat);
            case RAISE -> toCall(seat) + Math.max(minRaise(), Math.min(m.amount(), maxRaiseFor(seat)));
        };
    }

    /** 행동 적용. 낸 돈을 반환 */
    public long apply(int seat, Move m) {
        long paid = 0;
        if (m.act() == Act.RAISE && !canRaise(seat)) m = Move.call();
        switch (m.act()) {
            case DIE -> {
                folded[seat] = true;
                status[seat] = "다이";
            }
            case CALL -> {
                long c = Math.min(toCall(seat), bank[seat]);
                put[seat] += c;
                paid = c;
                status[seat] = c > 0 ? "콜" : "체크";
            }
            case RAISE -> {
                long r = Math.max(minRaise(), Math.min(maxRaiseFor(seat), m.amount()));
                long c = toCall(seat) + r;
                put[seat] += c;
                paid = c;
                raisesLeft--;
                status[seat] = "레이즈";
                for (int i = 0; i < 3; i++) if (!folded[i]) acted[i] = false;
            }
        }
        acted[seat] = true;
        bank[seat] -= paid;
        turn = bettingOver() ? -1 : next(seat + 1);
        return paid;
    }

    /** 자리 비움 (나감·접속 끊김) */
    public void forceFold(int seat) {
        if (folded[seat]) return;
        folded[seat] = true;
        status[seat] = "다이";
        acted[seat] = true;
        if (turn == seat) turn = bettingOver() ? -1 : next(seat + 1);
        else if (bettingOver()) turn = -1;
    }

    private int next(int from) {
        for (int k = 0; k < 3; k++) {
            int i = (from + k) % 3;
            if (!folded[i] && (!acted[i] || put[i] != maxPut())) return i;
        }
        return -1;
    }

    public Outcome showdown() {
        Hand[] live = new Hand[3];
        for (int i = 0; i < 3; i++) live[i] = folded[i] ? null : hands[i];
        return Seotda.showdown(live);
    }

    /** 재경기: 남은 사람끼리 다시 패를 받음 (판돈 유지) */
    public void rematch() {
        rematches++;
        deal();
    }

    /** 혼자 남았을 때 승자 */
    public int lastStanding() {
        for (int i = 0; i < 3; i++) if (!folded[i]) return i;
        return -1;
    }
}
