package kr.kkaegul.client.casino.holdem.engine;

import kr.kkaegul.client.casino.holdem.engine.HoldemTable.Move;
import kr.kkaegul.client.casino.holdem.engine.Poker.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 홀덤 AI (중수 이상). 몬테카를로로 이길 확률(에퀴티)을 계산하고, 팟 오즈와 비교해서 행동한다.
 * peek=true면 상대 패를 알고 계산한다 (하우스 우위용, 설정으로 확률 조절).
 */
public final class HoldemAi {

    private HoldemAi() {
    }

    /** 이길 확률 (무승부는 나눔) */
    public static double equity(HoldemTable t, int seat, boolean peek, int sims, RandomGenerator rng) {
        List<Card> deck = t.remainingDeck();
        List<Integer> opps = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (i != seat && t.present[i] && !t.folded[i]) opps.add(i);
        // 엿보지 않으면 상대 패도 덱에 섞음
        if (!peek) for (int i : opps) {
            deck.add(t.hole[i][0]);
            deck.add(t.hole[i][1]);
        }
        java.util.Random r = new java.util.Random(rng.nextLong());
        double score = 0;
        int need = 5 - t.board.size();
        List<Card> mine = new ArrayList<>(7);
        List<Card> theirs = new ArrayList<>(7);
        for (int s = 0; s < sims; s++) {
            Collections.shuffle(deck, r);
            int k = 0;
            Card[][] oh = new Card[3][];
            for (int i : opps) oh[i] = peek ? t.hole[i] : new Card[]{deck.get(k++), deck.get(k++)};
            List<Card> board = new ArrayList<>(t.board);
            for (int j = 0; j < need; j++) board.add(deck.get(k++));
            mine.clear();
            mine.addAll(board);
            mine.add(t.hole[seat][0]);
            mine.add(t.hole[seat][1]);
            long my = Poker.value(mine);
            boolean lose = false;
            int ties = 0;
            for (int i : opps) {
                theirs.clear();
                theirs.addAll(board);
                theirs.add(oh[i][0]);
                theirs.add(oh[i][1]);
                long v = Poker.value(theirs);
                if (v > my) {
                    lose = true;
                    break;
                }
                if (v == my) ties++;
            }
            if (!lose) score += 1.0 / (1 + ties);
        }
        return score / sims;
    }

    /**
     * 행동 결정.
     *
     * @param peek 상대 패를 알고 계산할지
     */
    public static Move decide(HoldemTable t, int seat, boolean peek, RandomGenerator rng) {
        int opps = t.activeCount() - 1;
        double eq = equity(t, seat, peek, t.board.isEmpty() ? 260 : 360, rng);
        long call = t.toCall(seat);
        long pot = t.pot();
        double potOdds = call == 0 ? 0 : (double) call / (pot + call);
        boolean canRaise = t.canRaise(seat);
        double r = rng.nextDouble();
        // 상대 수에 따라 기준 조정 (2명이면 더 공격적)
        double strong = opps >= 2 ? 0.62 : 0.72;
        double good = opps >= 2 ? 0.42 : 0.56;

        if (eq >= strong && canRaise) {
            // 아주 좋은 패: 대부분 크게, 가끔 슬로플레이
            if (r < 0.18 && t.street < 3 && call == 0) return Move.call();
            double size = 0.6 + rng.nextDouble() * 0.6;
            if (eq > 0.85 && r < 0.25) size = 2.0; // 크게 (올인 가능)
            return Move.raiseTo(sizeTo(t, seat, size));
        }
        if (eq >= good) {
            if (canRaise && r < 0.45) return Move.raiseTo(sizeTo(t, seat, 0.5 + rng.nextDouble() * 0.3));
            return Move.call();
        }
        if (call == 0) {
            // 공짜로 볼 수 있으면 체크, 가끔 블러핑
            if (canRaise && r < 0.10 + (opps == 1 ? 0.06 : 0)) return Move.raiseTo(sizeTo(t, seat, 0.5));
            return Move.call();
        }
        // 팟 오즈가 맞으면 콜
        if (eq > potOdds + 0.04) return Move.call();
        // 작은 금액은 가끔 따라감
        if (call <= t.bb && r < 0.35) return Move.call();
        if (canRaise && r < 0.04) return Move.raiseTo(sizeTo(t, seat, 0.8)); // 블러핑 레이즈
        return Move.fold();
    }

    /** 팟의 size배 만큼 베팅/레이즈한 "총액" */
    private static long sizeTo(HoldemTable t, int seat, double size) {
        long call = t.toCall(seat);
        long potAfterCall = t.pot() + call;
        long raiseBy = Math.max(t.bb, Math.round(potAfterCall * size / t.bb) * t.bb);
        long to = t.currentBet + raiseBy;
        return Math.max(t.minRaiseTo(seat), Math.min(t.maxRaiseTo(seat), to));
    }
}
