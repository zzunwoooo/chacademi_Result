package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.DrawBoardProtocol;

class DrawBoardRulesTest {
    private static List<String> ids() {
        var out = new ArrayList<String>();
        for (int i = 0; i < 100; i++) out.add(UUID.nameUUIDFromBytes(new byte[]{(byte) i}).toString());
        out.set(99, out.get(0));   // 같은 아이템을 두 번 등록한 경우도
        return out;
    }

    private static DrawBoardRules.Board board(List<String> layout, String revealed, int round, long completedAt) {
        return new DrawBoardRules.Board(5, round, 3, layout, revealed, completedAt);
    }

    private static Map<String, Integer> counts(List<String> l) {
        var m = new HashMap<String, Integer>();
        for (var s : l) m.merge(s, 1, Integer::sum);
        return m;
    }

    @Test void shuffleKeepsTheSameHundredItems() {
        var ids = ids();
        var random = new SplittableRandom(11);
        var first = DrawBoardRules.shuffle(ids, random);
        assertEquals(100, first.size());
        assertEquals(counts(ids), counts(first));
        var next = DrawBoardRules.reshuffle(first, random);
        assertEquals(counts(ids), counts(next));
        assertNotEquals(first, next, "다음 판은 위치가 바뀜");
        assertEquals(ids(), ids, "원본 목록은 그대로");
        // 100개가 모두 같은 아이템이면 섞어도 같음 (무한 반복 없음)
        var same = Collections.nCopies(100, "a");
        assertEquals(same, DrawBoardRules.reshuffle(same, random));
    }

    @Test void flipRevealsAndHidesWithoutChangingTheCellItem() {
        var layout = DrawBoardRules.shuffle(ids(), new SplittableRandom(1));
        var b = board(layout, DrawBoardRules.HIDDEN, 1, 0);
        var f = DrawBoardRules.flip(b, 1, 42, true);
        assertEquals(DrawBoardRules.Outcome.CHANGED, f.outcome());
        assertEquals('1', f.revealed().charAt(42));
        assertFalse(f.nowComplete());
        var b2 = board(layout, f.revealed(), 1, 0);
        assertEquals(DrawBoardRules.Outcome.SAME, DrawBoardRules.flip(b2, 1, 42, true).outcome(), "이미 공개된 칸 공개 = 변화 없음");
        var hide = DrawBoardRules.flip(b2, 1, 42, false);
        assertEquals(DrawBoardRules.HIDDEN, hide.revealed());
        // 칸의 아이템은 layout 그대로 (뒤집기는 layout 을 바꾸지 않음)
        var index = new HashMap<String, Integer>();
        for (int i = 0; i < 100; i++) index.putIfAbsent(layout.get(i), i);
        var shown = DrawBoardRules.visible(b2, index);
        assertEquals(1, shown.size());
        assertEquals(42, shown.get(0).cell());
        assertEquals(index.get(layout.get(42)), shown.get(0).item());
    }

    @Test void roundEndsOnlyWhenAllHundredAreCurrentlyRevealed() {
        var layout = DrawBoardRules.shuffle(ids(), new SplittableRandom(2));
        var b = board(layout, DrawBoardRules.HIDDEN, 7, 0);
        int clicks = 0;
        // 99칸 공개 → 앞 50칸 가림 → 다시 공개를 3번: 누른 횟수는 400번이 넘어도 지금 공개된 칸이 100이 아니면 끝나지 않음
        var plan = new ArrayList<int[]>();
        for (int cell = 0; cell < 99; cell++) plan.add(new int[]{cell, 1});
        for (int rep = 0; rep < 3; rep++) { for (int cell = 0; cell < 50; cell++) plan.add(new int[]{cell, 0}); for (int cell = 0; cell < 50; cell++) plan.add(new int[]{cell, 1}); }
        for (var step : plan) {
            var f = DrawBoardRules.flip(b, 7, step[0], step[1] == 1);
            clicks++;
            assertEquals(DrawBoardRules.Outcome.CHANGED, f.outcome());
            assertFalse(f.nowComplete(), "공개 " + DrawBoardRules.count(f.revealed()) + "칸에서 끝나면 안 됨 (누른 횟수 " + clicks + ")");
            b = board(layout, f.revealed(), 7, 0);
        }
        assertTrue(clicks > 100);
        assertEquals(99, b.revealedCount());
        // 지금 상태에서 남은 가려진 칸을 모두 공개 → 마지막 하나에서 정확히 끝남
        int hidden = 100 - b.revealedCount();
        assertTrue(hidden > 0);
        for (int cell = 0; cell < 100; cell++) {
            if (b.revealed(cell)) continue;
            var f = DrawBoardRules.flip(b, 7, cell, true);
            hidden--;
            assertEquals(hidden == 0, f.nowComplete());
            b = board(layout, f.revealed(), 7, f.nowComplete() ? 1000 : 0);
        }
        assertTrue(b.complete());
        assertEquals(100, b.revealedCount());
        // 끝난 판(보여 주는 중)은 더 바꾸지 않음
        assertEquals(DrawBoardRules.Outcome.HOLDING, DrawBoardRules.flip(b, 7, 3, false).outcome());
        assertFalse(DrawBoardRules.resetDue(b, 1000 + DrawBoardProtocol.HOLD_MS - 1, DrawBoardProtocol.HOLD_MS));
        assertTrue(DrawBoardRules.resetDue(b, 1000 + DrawBoardProtocol.HOLD_MS, DrawBoardProtocol.HOLD_MS));
    }

    @Test void staleRoundAndMissingBoardAreRejected() {
        var layout = DrawBoardRules.shuffle(ids(), new SplittableRandom(3));
        var b = board(layout, DrawBoardRules.HIDDEN, 4, 0);
        assertEquals(DrawBoardRules.Outcome.STALE_ROUND, DrawBoardRules.flip(b, 3, 0, true).outcome());
        var none = board(List.of(), DrawBoardRules.HIDDEN, 4, 0);
        assertFalse(none.ready());
        assertEquals(DrawBoardRules.Outcome.NOT_READY, DrawBoardRules.flip(none, 4, 0, true).outcome());
        assertFalse(DrawBoardRules.resetDue(none, Long.MAX_VALUE, 0));
        assertTrue(DrawBoardRules.visible(none, Map.of()).isEmpty());
    }

    @Test void layoutTextRoundTrips() {
        var layout = DrawBoardRules.shuffle(ids(), new SplittableRandom(4));
        assertEquals(layout, DrawBoardRules.split(DrawBoardRules.join(layout)));
        assertTrue(DrawBoardRules.split("").isEmpty());
        assertTrue(DrawBoardRules.split("a,b").isEmpty(), "100개가 아니면 판 없음");
        assertTrue(DrawBoardRules.validRevealed(DrawBoardRules.HIDDEN));
        assertFalse(DrawBoardRules.validRevealed("01"));
        assertTrue(DrawBoardRules.join(layout).length() <= 100 * 37, "TEXT 칸에 들어감");
    }
}
