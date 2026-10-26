package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TarotProtocol;

/** 마법 힌트권 「운명의 타로」 (§41): 묶음 왕복·거절, 줄바꿈, 카드 칸·그림 칸, 화면 배율, 뒤집기 시간표. */
class TarotLayoutTest {
    /** 글자 폭 흉내: 한 글자 = 글자 크기 × 0.92 (한글 본문 글꼴과 비슷), 띄어쓰기 = 0.3. */
    private static float width(String s, float size) {
        float w = 0;
        for (char c : s.toCharArray()) w += c == ' ' ? size * .3f : size * .92f;
        return w;
    }

    @Test void protocolRoundTrip() {
        var c = TarotProtocol.decode(TarotProtocol.encode(new TarotProtocol.Card(3, 19, "보름달이 뜬 밤 호숫가에서\n「달빛 화살」")));
        assertEquals(3, c.stars());
        assertEquals(19, c.illustration());
        assertEquals("보름달이 뜬 밤 호숫가에서 「달빛 화살」", c.line());
        assertEquals(TarotProtocol.MAX_LINE, (long) new TarotProtocol.Card(1, 0, "가".repeat(900)).line().length());
        assertTrue(TarotProtocol.encode(new TarotProtocol.Card(1, 0, "가".repeat(900))).length <= TarotProtocol.MAX_BYTES);
    }

    @Test void protocolRejects() {
        assertThrows(IllegalArgumentException.class, () -> new TarotProtocol.Card(0, 0, "a"));
        assertThrows(IllegalArgumentException.class, () -> new TarotProtocol.Card(4, 0, "a"));
        assertThrows(IllegalArgumentException.class, () -> new TarotProtocol.Card(1, 20, "a"));
        assertThrows(IllegalArgumentException.class, () -> new TarotProtocol.Card(1, -1, "a"));
        assertThrows(IllegalArgumentException.class, () -> new TarotProtocol.Card(1, 0, "  "));
        byte[] ok = TarotProtocol.encode(new TarotProtocol.Card(2, 5, "힌트"));
        byte[] trailing = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> TarotProtocol.decode(trailing));
        byte[] version = ok.clone(); version[3] = 9;
        assertThrows(IllegalArgumentException.class, () -> TarotProtocol.decode(version));
        byte[] stars = ok.clone(); stars[4] = 7;
        assertThrows(IllegalArgumentException.class, () -> TarotProtocol.decode(stars));
        assertThrows(IllegalArgumentException.class, () -> TarotProtocol.decode(new byte[TarotProtocol.MAX_BYTES + 1]));
    }

    @Test void wrapsByGlyphWidth() {
        String text = "보름달이 뜬 밤 호숫가에서 물에 비친 달을 바라보면 「달빛 화살」을 배울 수 있다. 그리고 아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주아주긴낱말";
        var lines = TarotLayout.wrap(text, 500, s -> width(s, 30));
        assertTrue(lines.size() > 2);
        for (var l : lines) assertTrue(width(l, 30) <= 500, l);
        assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
        assertEquals(1L, TarotLayout.wrap("짧은 힌트", 500, s -> width(s, 30)).size());
    }

    @Test void fitsHintIntoThreeOrFourLines() {
        var shortHint = TarotLayout.fitHint("어딘가 밤하늘과 관련된 마법이 잠들어 있다고 한다…", TarotLayoutTest::width);
        assertEquals(1L, shortHint.lines().size());
        assertEquals((double) TarotLayout.HINT_SIZE, (double) shortHint.size());
        assertEquals((double) TarotLayout.naturalHintHeight(), (double) shortHint.panelHeight());
        String three = "카드가 또렷하게 속삭여요… 「보름달이 뜬 밤 호숫가에서 물에 비친 달을 바라보며 물가에 서서 열 번 숨을 고른다」 그러면 새 마법을 깨칠 수 있을 거예요.";
        var mid = TarotLayout.fitHint(three, TarotLayoutTest::width);
        assertTrue(mid.lines().size() <= 4 && mid.lines().size() >= 2);
        for (var l : mid.lines()) assertTrue(width(l, mid.size()) <= TarotLayout.HINT_TEXT_W, l);
        var huge = TarotLayout.fitHint("가나다라 ".repeat(120), TarotLayoutTest::width);
        assertEquals(4L, huge.lines().size());
        assertTrue(huge.lines().get(3).endsWith("…"));
        for (var l : huge.lines()) assertTrue(width(l, huge.size()) <= TarotLayout.HINT_TEXT_W, l);
        // 4줄이어도 힌트 바탕 아래에 버튼, 버튼은 패널 안
        float bottom = TarotLayout.HINT_TOP + huge.panelHeight(), by = TarotLayout.buttonY(huge), bh = TarotLayout.buttonH();
        assertTrue(by - bh / 2 >= bottom + 4, "button below hint");
        assertTrue(by + bh / 2 <= 923, "button inside panel border");
    }

    @Test void cardBoxes() {
        for (int g = 0; g < 3; g++) {
            int[] f = TarotLayout.FRAME_BOX[g], r = TarotLayout.ART_RECT[g];
            assertTrue(r[0] > f[0] && r[1] > f[1] && r[2] < f[2] && r[3] < f[3], "art inside frame " + g);
            float[] uv = TarotLayout.cover(r);
            assertTrue(uv[0] >= 0 && uv[1] >= 0 && uv[0] + uv[2] <= TarotLayout.CARD_TEX_W + .01f && uv[1] + uv[3] <= TarotLayout.CARD_TEX_H + .01f);
            assertTrue(Math.abs(uv[2] / uv[3] - (r[2] - r[0]) / (float) (r[3] - r[1])) < 1e-3, "cover keeps ratio");
            // 앞면 보이는 높이 = 뒷면 보이는 높이 (뒤집을 때 크기가 같음)
            var front = TarotLayout.place(f, 0, 0, 400);
            assertTrue(Math.abs((f[3] - f[1]) * front.scale() - 400) < .01f);
            assertTrue(Math.abs(front.y() + (f[1] + f[3]) / 2f * front.scale()) < .01f, "centered");
        }
        var back = TarotLayout.place(TarotLayout.BACK_BOX, 10, 20, 400);
        assertTrue(Math.abs(back.x() + (TarotLayout.BACK_BOX[0] + TarotLayout.BACK_BOX[2]) / 2f * back.scale() - 10) < .01f);
        assertEquals(1L, TarotLayout.cardAt(TarotLayout.CX, TarotLayout.SELECT_Y));
        assertEquals(0L, TarotLayout.cardAt(TarotLayout.selectX(0) - 100, TarotLayout.selectY(0)));
        assertEquals(2L, TarotLayout.cardAt(TarotLayout.selectX(2) + 100, TarotLayout.selectY(2)));
        assertEquals(-1L, TarotLayout.cardAt(10, 10));
    }

    @Test void screenScale() {
        // 1920×1080, GUI 배율 3 → 640×360: 가로 70%
        float s = TarotLayout.scale(640, 360);
        assertTrue(Math.abs(TarotLayout.PANEL_W * s - 448) < 1);
        // 세로가 좁으면 세로 94% 안
        s = TarotLayout.scale(1000, 300);
        assertTrue(TarotLayout.PANEL_H * s <= 300 * .94f + .01f);
        // 아주 좁은 화면은 가로 94%
        s = TarotLayout.scale(400, 400);
        assertTrue(TarotLayout.PANEL_W * s <= 400 * .94f + .01f && TarotLayout.PANEL_W * s >= 400 * .9f);
    }

    @Test void flipTimeline() {
        assertEquals(0.0, (double) TarotLayout.flipDegrees(0));
        assertEquals(90.0, (double) TarotLayout.flipDegrees(TarotLayout.FLIP_HALF));
        assertFalse(TarotLayout.frontVisible(TarotLayout.flipDegrees(TarotLayout.FLIP_HALF)));
        assertTrue(TarotLayout.frontVisible(TarotLayout.flipDegrees(TarotLayout.FLIP_HALF + 20)));
        assertEquals(180.0, (double) TarotLayout.flipDegrees(TarotLayout.RESULT_AT));
        for (long t = 0; t <= TarotLayout.RESULT_AT; t += 10) assertTrue(TarotLayout.flipWidth(TarotLayout.flipDegrees(t)) >= 0, "never mirrored");
        assertTrue(TarotLayout.flipWidth(TarotLayout.flipDegrees(TarotLayout.RESULT_AT)) > .999f);
        assertEquals(0.0, (double) TarotLayout.star(TarotLayout.RESULT_AT, 0));
        assertEquals(1.0, (double) TarotLayout.star(TarotLayout.RESULT_AT + 2 * TarotLayout.STAR_STEP + TarotLayout.STAR_MS, 2));
        assertTrue(TarotLayout.CONFIRM_AT >= TarotLayout.RESULT_AT);
    }
}
