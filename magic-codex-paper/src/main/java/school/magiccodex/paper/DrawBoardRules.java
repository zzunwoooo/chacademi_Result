package school.magiccodex.paper;

import java.util.*;
import java.util.random.RandomGenerator;
import school.magiccodex.protocol.DrawBoardProtocol;

/**
 * 「행운의 뽑기판」 판 규칙 (§44). Bukkit·DB 없이 계산만 — DrawBoardRulesTest 가 그대로 확인한다.
 * <ul>
 *   <li>판(layout) = 등록 아이템 id 100개를 섞은 순서. 칸 i 의 아이템 = layout[i]. 같은 회차 안에서는 절대 바뀌지 않는다.</li>
 *   <li>공개(revealed) = "0"/"1" 100글자. 누른 횟수가 아니라 지금 공개된 칸 수가 100이면 그 판은 끝(complete).</li>
 *   <li>끝난 판은 잠깐(HOLD_MS) 그대로 보여 준 뒤, 같은 100개를 다시 섞어 다음 회차로 (모두 가림).</li>
 * </ul>
 */
final class DrawBoardRules {
    private DrawBoardRules() {}

    static final int CELLS = DrawBoardProtocol.CELLS;
    static final String HIDDEN = "0".repeat(CELLS);

    /** 판 한 줄 (DB codex_draw_board id=1 과 같은 모양). layout 은 비었거나 정확히 100개. */
    record Board(long revision, int round, long itemsRevision, List<String> layout, String revealed, long completedAt) {
        boolean ready() { return layout.size() == CELLS; }
        boolean complete() { return completedAt > 0; }
        int revealedCount() { return count(revealed); }
        boolean revealed(int cell) { return revealed.charAt(cell) == '1'; }
    }

    enum Outcome { CHANGED, SAME, NOT_READY, STALE_ROUND, HOLDING }
    /** 뒤집기 결과: 바뀌었으면 새 revealed 와 이번에 100칸이 다 찼는지. */
    record Flip(Outcome outcome, String revealed, boolean nowComplete) {}

    /** 같은 아이템들(중복 허용)을 새 순서로. 원본은 건드리지 않는다. */
    static List<String> shuffle(List<String> ids, RandomGenerator random) {
        var out = new ArrayList<>(ids);
        for (int i = out.size() - 1; i > 0; i--) Collections.swap(out, i, random.nextInt(i + 1));
        return List.copyOf(out);
    }

    /** 다음 판 배치: 같은 100개를 다시 섞되, 가능하면 이전 배치와 똑같지 않게 (100개가 모두 같은 아이템이면 그대로). */
    static List<String> reshuffle(List<String> previous, RandomGenerator random) {
        var next = shuffle(previous, random);
        for (int tries = 0; tries < 4 && next.equals(previous) && new HashSet<>(previous).size() > 1; tries++) next = shuffle(previous, random);
        return next;
    }

    static int count(String revealed) {
        int n = 0;
        for (int i = 0; i < revealed.length(); i++) if (revealed.charAt(i) == '1') n++;
        return n;
    }

    static boolean validRevealed(String s) { return s != null && s.length() == CELLS && s.chars().allMatch(c -> c == '0' || c == '1'); }

    /** 칸 하나를 공개(reveal=true) 또는 다시 가림. 회차가 다르거나 끝난 판(보여 주는 중)이면 바꾸지 않는다. */
    static Flip flip(Board b, int round, int cell, boolean reveal) {
        if (!b.ready()) return new Flip(Outcome.NOT_READY, b.revealed(), false);
        if (b.round() != round) return new Flip(Outcome.STALE_ROUND, b.revealed(), false);
        if (b.complete()) return new Flip(Outcome.HOLDING, b.revealed(), false);
        if (cell < 0 || cell >= CELLS || b.revealed(cell) == reveal) return new Flip(Outcome.SAME, b.revealed(), false);
        var chars = b.revealed().toCharArray();
        chars[cell] = reveal ? '1' : '0';
        String next = new String(chars);
        return new Flip(Outcome.CHANGED, next, count(next) == CELLS);
    }

    /** 끝난 판을 다음 회차로 넘길 때인지 (보여 주는 시간이 지났는지). */
    static boolean resetDue(Board b, long now, long holdMs) { return b.ready() && b.complete() && now - b.completedAt() >= holdMs; }

    /** layout 텍스트 (DB): id 를 쉼표로. */
    static String join(List<String> layout) { return String.join(",", layout); }
    static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        var parts = List.of(text.split(","));
        return parts.size() == CELLS ? parts : List.of();
    }

    /** 공개된 칸 → (칸, 목록 순번). 목록에 없는 id(있으면 안 됨)는 건너뛴다. */
    static List<DrawBoardProtocol.Cell> visible(Board b, Map<String, Integer> index) {
        var out = new ArrayList<DrawBoardProtocol.Cell>();
        if (!b.ready()) return out;
        for (int i = 0; i < CELLS; i++) {
            if (!b.revealed(i)) continue;
            Integer item = index.get(b.layout().get(i));
            if (item != null && item < CELLS) out.add(new DrawBoardProtocol.Cell(i, item));
        }
        return out;
    }
}
