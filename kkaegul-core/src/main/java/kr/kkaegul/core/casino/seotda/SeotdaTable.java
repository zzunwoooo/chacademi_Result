package kr.kkaegul.core.casino.seotda;

import kr.kkaegul.core.casino.seotda.Seotda.Card;
import kr.kkaegul.core.casino.seotda.Seotda.Hand;
import kr.kkaegul.core.casino.seotda.Seotda.Outcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 섯다 한 판 (플레이어 1 + AI 2). 서버 의존성 없는 순수 로직 → 시뮬레이션으로 확률 검증 가능.
 * <p>
 * 자리: 0 = 플레이어, 1 = AI 1, 2 = AI 2.
 * <p>
 * 하우스 우위 구조
 * <ol>
 *     <li>판을 시작할 때 플레이어가 이길 판인지 먼저 정한다 (판돈이 클수록 확률이 낮아짐)</li>
 *     <li>그 결과에 맞게 패를 나눠 준다. 질 판이면 가끔 플레이어에게 좋은 패를 주고 AI에게 근소하게 더 좋은 패를 준다 (미끼)</li>
 *     <li>AI는 모든 패를 알고 베팅한다: 플레이어가 이길 판이면 판을 키우지 않고, 질 판이면 레이즈로 키운다</li>
 * </ol>
 * → 돈을 따는 판은 작고, 잃는 판은 크다.
 */
public final class SeotdaTable {

    public enum Act { DIE, CALL, RAISE }

    public record Move(Act act, long amount) {
        public static Move die() {
            return new Move(Act.DIE, 0);
        }

        public static Move call() {
            return new Move(Act.CALL, 0);
        }

        public static Move raise(long r) {
            return new Move(Act.RAISE, r);
        }
    }

    /**
     * 밸런스 설정.
     *
     * @param winChanceBase   기준 판돈에서 플레이어가 이길 판일 확률
     * @param winChanceFloor  아무리 판돈이 커도 이 밑으로는 안 내려감
     * @param winChanceDrop   판돈이 10배 커질 때마다 줄어드는 확률
     * @param baseAnte        기준 판돈
     * @param baitChance      질 판에서 미끼(좋은 패 + 근소하게 더 좋은 AI 패)를 줄 확률
     * @param maxRaiseMult    한 번에 올릴 수 있는 최대 금액 (판돈의 배수)
     * @param maxRaises       한 판에 레이즈 횟수 제한
     * @param aiCallWhenLosing 플레이어가 이길 판에서 AI가 레이즈를 받아줄 확률 (연기용)
     * @param aiBluffWhenLosing 플레이어가 이길 판에서 AI가 먼저 조금 올려 볼 확률 (연기용, 플레이어 몫이 커짐)
     * @param aiRaiseChance   이길 AI가 레이즈할 확률 (보통)
     * @param aiBaitRaiseChance 미끼 판에서 이길 AI가 레이즈할 확률
     * @param aiRaiseMult     이길 AI의 보통 레이즈 최대 (판돈 배수, 미끼 판은 maxRaiseMult까지)
     */
    public record Config(double winChanceBase, double winChanceFloor, double winChanceDrop, long baseAnte,
                         double baitChance, int maxRaiseMult, int maxRaises, double aiCallWhenLosing,
                         double aiBluffWhenLosing, double aiRaiseChance, double aiBaitRaiseChance, double aiRaiseMult) {
        public static Config defaults() {
            return new Config(0.41, 0.30, 0.04, 10_000, 0.45, 4, 2, 0.30, 0.15, 0.45, 0.85, 2.0);
        }

        /** 판돈에 따른 플레이어 승리 판 확률 */
        public double winChance(long ante) {
            double tens = Math.log10(Math.max(1.0, (double) ante / baseAnte));
            return Math.max(winChanceFloor, winChanceBase - winChanceDrop * Math.max(0, tens));
        }
    }

    private final Config cfg;
    private final RandomGenerator rng;
    public final long ante;
    /** 플레이어가 더 낼 수 있는 돈 (AI 레이즈 상한 계산용, 판 시작 시 보유금 - 판돈) */
    private long playerBank;

    public final long[] put = new long[3];
    public final boolean[] folded = new boolean[3];
    /** 이번 판에 참가한 자리 (파산한 AI는 빠짐) */
    public final boolean[] present = {true, true, true};
    /** AI가 더 낼 수 있는 돈 (판돈을 내고 남은 칩) */
    private final long[] aiBank = {0, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4};
    public Hand[] hands = new Hand[3];
    public final String[] status = {"", "", ""};
    public int turn;
    public int raisesLeft;
    private final boolean[] acted = new boolean[3];
    /** 플레이어가 이길 판인지 (하우스만 아는 정보) */
    public final boolean playerFated;
    /** 질 판일 때 이길 AI 자리 */
    public final int fatedAi;
    public boolean bait;
    public int rematches;
    public String lastLog = "";

    public SeotdaTable(Config cfg, RandomGenerator rng, long ante, long playerBalanceAfterAnte) {
        this(cfg, rng, ante, playerBalanceAfterAnte, new boolean[]{true, true, true}, null);
    }

    /**
     * @param aiPresent 자리별 참가 여부 (0번은 항상 플레이어)
     * @param aiBanks   AI가 판돈을 내고 남은 칩 (null이면 무한)
     */
    public SeotdaTable(Config cfg, RandomGenerator rng, long ante, long playerBalanceAfterAnte, boolean[] aiPresent, long[] aiBanks) {
        this.cfg = cfg;
        this.rng = rng;
        this.ante = ante;
        this.playerBank = Math.max(0, playerBalanceAfterAnte);
        for (int i = 1; i < 3; i++) {
            present[i] = aiPresent[i];
            folded[i] = !present[i];
            if (aiBanks != null) aiBank[i] = Math.max(0, aiBanks[i]);
        }
        this.playerFated = rng.nextDouble() < cfg.winChance(ante);
        int fa = 1 + rng.nextInt(2);
        if (!present[fa]) fa = 3 - fa;
        this.fatedAi = fa;
        for (int i = 0; i < 3; i++) put[i] = present[i] ? ante : 0;
        dealNew();
    }

    // ------------------------------------------------------------------ 딜링

    private void dealNew() {
        bait = !playerFated && rng.nextDouble() < cfg.baitChance;
        hands = deal(playerFated, fatedAi, bait);
        for (int i = 0; i < 3; i++) {
            acted[i] = folded[i];
            if (!folded[i]) status[i] = "";
        }
        raisesLeft = cfg.maxRaises;
        turn = firstActive(0);
    }

    /** 결과에 맞는 패를 뽑는다 (거절 샘플링) */
    private Hand[] deal(boolean playerWins, int aiWinner, boolean baitMode) {
        List<Card> deck = Seotda.deck();
        Hand[] fallback = null;
        for (int attempt = 0; attempt < 30000; attempt++) {
            Collections.shuffle(deck, new java.util.Random(rng.nextLong()));
            Hand[] h = {Seotda.eval(deck.get(0), deck.get(1)), Seotda.eval(deck.get(2), deck.get(3)), Seotda.eval(deck.get(4), deck.get(5))};
            Hand[] live = h.clone();
            for (int i = 1; i < 3; i++) if (!present[i]) live[i] = null;
            Outcome o = Seotda.showdown(live);
            if (o.rematch()) continue;
            if (playerWins) {
                if (o.winner() == 0) return h;
                continue;
            }
            if (o.winner() != aiWinner) continue;
            // 지는 AI는 플레이어보다 약하게 (플레이어가 2등) → 끝까지 가도 이 AI만 상대가 됨
            if (present[3 - aiWinner] && Seotda.compare(h[0], h[3 - aiWinner]) <= 0) continue;
            if (fallback == null) fallback = h;
            if (!baitMode) return h;
            // 미끼: 플레이어는 좋은 패(땡 이상 또는 알리~세륙), AI는 근소하게 위
            if (h[0].score() >= 650 && closeAbove(h[aiWinner], h[0])) return h;
        }
        if (fallback != null) {
            bait = false;
            return fallback;
        }
        // 이론상 거의 없음
        return new Hand[]{Seotda.eval(deck.get(0), deck.get(1)), Seotda.eval(deck.get(2), deck.get(3)), Seotda.eval(deck.get(4), deck.get(5))};
    }

    /** a가 b보다 "조금만" 센지 (족보 단계 3칸 이내) */
    private static boolean closeAbove(Hand a, Hand b) {
        return Seotda.compare(a, b) > 0 && step(a) - step(b) <= 3;
    }

    /** 족보 단계 번호 (끗 0~9, 세륙 10 … 알리 15, 1땡 16 … 장땡 25, 13·18광땡 26, 38광땡 27) */
    private static int step(Hand h) {
        int s = h.score();
        if (s >= 1000) return 27;
        if (s >= 900) return 26;
        if (s >= 801) return 15 + (s - 800);
        if (s >= 650) return 10 + (s - 650) / 10;
        return s - 500;
    }

    // ------------------------------------------------------------------ 베팅

    public long maxPut() {
        return Math.max(put[0], Math.max(put[1], put[2]));
    }

    public long pot() {
        return put[0] + put[1] + put[2];
    }

    /** 남은 사람 모두가 받을 수 있는 최대 레이즈 (seat 제외) */
    private long othersCap(int seat) {
        long m = Long.MAX_VALUE;
        for (int j = 0; j < 3; j++) {
            if (j == seat || folded[j]) continue;
            long bank = j == 0 ? playerBank : aiBank[j];
            m = Math.min(m, bank - toCall(j));
        }
        return m;
    }

    /** AI가 지금 콜할 수 있는지 */
    public boolean aiCanCall(int seat) {
        return aiBank[seat] >= toCall(seat);
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

    public long maxRaise() {
        return ante * cfg.maxRaiseMult();
    }

    /** 플레이어가 레이즈할 수 있는 최대 (보유금 한도 반영) */
    public long playerMaxRaise() {
        return Math.min(Math.min(maxRaise(), playerBank - toCall(0)), othersCap(0));
    }

    public boolean canRaise(int seat) {
        if (raisesLeft <= 0 || folded[seat]) return false;
        if (seat == 0) return playerMaxRaise() >= minRaise();
        return Math.min(aiBank[seat] - toCall(seat), othersCap(seat)) >= minRaise();
    }

    public boolean bettingOver() {
        if (activeCount() <= 1) return true;
        for (int i = 0; i < 3; i++) {
            if (folded[i]) continue;
            if (!acted[i] || put[i] != maxPut()) return false;
        }
        return true;
    }

    /** 행동 적용. 플레이어가 낸 돈(보유금에서 빠질 금액)을 반환 */
    public long apply(int seat, Move m) {
        long paid = 0;
        switch (m.act()) {
            case DIE -> {
                folded[seat] = true;
                status[seat] = "다이";
            }
            case CALL -> {
                long c = toCall(seat);
                put[seat] += c;
                paid = c;
                status[seat] = c > 0 ? "콜" : "체크";
            }
            case RAISE -> {
                long r = Math.max(minRaise(), Math.min(maxRaise(), m.amount()));
                if (seat == 0) r = Math.min(r, playerMaxRaise());
                else r = Math.min(r, Math.min(aiBank[seat] - toCall(seat), othersCap(seat)));
                long c = toCall(seat) + r;
                put[seat] += c;
                paid = c;
                raisesLeft--;
                status[seat] = "레이즈";
                for (int i = 0; i < 3; i++) acted[i] = false;
            }
        }
        acted[seat] = true;
        if (seat == 0) playerBank -= paid;
        else aiBank[seat] -= paid;
        lastLog = switch (status[seat]) {
            case "다이" -> name(seat) + " 다이";
            case "콜" -> name(seat) + " 콜";
            case "체크" -> name(seat) + " 체크";
            default -> name(seat) + " 레이즈";
        };
        turn = bettingOver() ? -1 : firstActive(seat + 1);
        return paid;
    }

    private static String name(int seat) {
        return seat == 0 ? "나" : "AI " + seat;
    }

    private int firstActive(int from) {
        for (int k = 0; k < 3; k++) {
            int i = (from + k) % 3;
            if (!folded[i] && (!acted[i] || put[i] != maxPut())) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ AI

    /** AI 결정 (모든 패를 알고 있음) */
    public Move aiMove(int seat) {
        long need = toCall(seat);
        if (need > aiBank[seat]) return Move.die();
        if (playerFated) {
            // 플레이어가 이길 판: 판을 크게 키우지 않는다 (가끔 조금 올려서 자연스럽게)
            if (need == 0) {
                if (canRaise(seat) && raisesLeft == cfg.maxRaises() && aiRaiseCap() >= minRaise()
                        && rng.nextDouble() < cfg.aiBluffWhenLosing()) return Move.raise(minRaise());
                return Move.call();
            }
            // 플레이어 레이즈를 가끔 한 명만 받아줘서 자연스럽게
            boolean otherCalled = false;
            int other = 3 - seat;
            if (!folded[other] && put[other] == maxPut()) otherCalled = true;
            // 크게 올릴수록 덜 받아줌 (겁먹은 척)
            double callP = cfg.aiCallWhenLosing() * Math.min(1.0, 2.0 * ante / Math.max(1, need));
            if (!otherCalled && rng.nextDouble() < callP) return Move.call();
            return Move.die();
        }
        if (seat == fatedAi) {
            // 이길 AI: 키운다
            long cap = aiRaiseCap();
            if (canRaise(seat) && cap >= minRaise()) {
                // 플레이어가 먼저 올렸으면(자신 있는 패) 거의 항상 다시 올려서 키운다
                boolean playerRaised = status[0].equals("레이즈");
                double p = bait || playerRaised ? cfg.aiBaitRaiseChance() : cfg.aiRaiseChance();
                if (rng.nextDouble() < p) {
                    long normalCap = Math.min(cap, (long) (ante * cfg.aiRaiseMult()));
                    long r = bait ? cap : minRaise() + (long) (rng.nextDouble() * Math.max(0, normalCap - minRaise()));
                    r = Math.max(minRaise(), (r / Math.max(1, ante / 2)) * Math.max(1, ante / 2));
                    return Move.raise(Math.min(r, cap));
                }
            }
            return Move.call();
        }
        // 질 AI: 판을 키우는 이길 AI를 방해하지 않게 조용히
        if (need == 0) return Move.call();
        if (need <= ante && rng.nextDouble() < 0.3) return Move.call();
        return Move.die();
    }

    /** AI가 올릴 수 있는 최대: 플레이어가 콜할 수 있는 만큼 */
    private long aiRaiseCap() {
        int seat = turn > 0 ? turn : fatedAi;
        return Math.min(maxRaise(), Math.min(aiBank[seat] - toCall(seat), othersCap(seat)));
    }

    // ------------------------------------------------------------------ 결과

    /** 베팅이 끝난 뒤 승부. 재경기면 다시 패를 나누고 베팅을 이어간다 (판돈 유지) */
    public Outcome showdown() {
        Hand[] live = new Hand[3];
        for (int i = 0; i < 3; i++) live[i] = folded[i] ? null : hands[i];
        Outcome o = Seotda.showdown(live);
        return o;
    }

    public void rematch() {
        rematches++;
        dealNew();
    }
}
