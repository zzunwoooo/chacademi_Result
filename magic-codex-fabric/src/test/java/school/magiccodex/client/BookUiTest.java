package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.BookProtocol;
import school.magiccodex.protocol.BookProtocol.*;

/** 책 읽기 (2026-10-23): packet round trip / rejection and the text boxes never leaving the page with the real font. */
class BookUiTest {
    @Test void packetsRoundTrip() {
        var open = new Open(42, "오래된 마법서", List.of(new Page("빛은 언제나 가까이에 있다.\n마음을 가라앉히고", "잊혀진 기록 속에서"), new Page("", "")));
        assertEquals(open, BookProtocol.decode(BookProtocol.encode(open)));
        var close = new Close(-7);
        assertEquals(close, BookProtocol.request(BookProtocol.request(close)));
    }

    @Test void rejectsBadPackets() {
        assertThrows(IllegalArgumentException.class, () -> new Close(0));
        assertThrows(IllegalArgumentException.class, () -> new Open(1, "t", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Page("가".repeat(BookProtocol.MAX_SIDE + 1), ""));
        var pages = new ArrayList<Page>(); for (int i = 0; i <= BookProtocol.MAX_PAGES; i++) pages.add(new Page("a", ""));
        assertThrows(IllegalArgumentException.class, () -> new Open(1, "t", pages));
        byte[] close = BookProtocol.request(new Close(5));
        byte[] trailing = java.util.Arrays.copyOf(close, close.length + 1);
        assertThrows(IllegalArgumentException.class, () -> BookProtocol.request(trailing));
        assertThrows(IllegalArgumentException.class, () -> BookProtocol.request(new byte[BookProtocol.MAX_REQUEST_BYTES + 1]));
        // the largest legal book still fits one packet (all Korean, 3 bytes per char)
        var big = new ArrayList<Page>(); for (int i = 0; i < BookProtocol.MAX_PAGES; i++) big.add(new Page("가".repeat(BookProtocol.MAX_SIDE), "나".repeat(BookProtocol.MAX_SIDE)));
        assertEquals(BookProtocol.MAX_PAGES, BookProtocol.decode(BookProtocol.encode(new Open(9, "가".repeat(BookProtocol.MAX_TITLE), big))).pages().size());
        assertTrue(BookProtocol.validId("flame_leap-1") && !BookProtocol.validId("Flame") && !BookProtocol.validId("../x"));
    }

    private static Font font(String file) throws Exception {
        try (var s = BookUiTest.class.getResourceAsStream("/assets/magiccodex/font/" + file)) { return Font.createFont(Font.TRUETYPE_FONT, s); }
    }

    @Test void textStaysInsideThePageWithTheRealFont() throws Exception {
        Font body = font("maruburi_semibold.ttf");
        BookLayout.Measure m = (t, s) -> CodexTextRasterizer.width(body, t, s);
        String para = "긴 문장은 쪽 너비에 맞춰 자동으로 줄이 바뀌고, 책 가운데 접히는 부분이나 테두리 장식과 겹치지 않도록 안쪽 여백 안에서만 그려집니다.";
        String noSpaces = "띄어쓰기없이아주길게이어지는문장도글자단위로잘려서쪽밖으로나가지않아야합니다".repeat(3);
        float height = BookLayout.BOX_BOTTOM - BookLayout.BOX_TOP;
        for (String text : List.of(para, noSpaces, (para + "\n\n").repeat(2), "가".repeat(BookProtocol.MAX_SIDE))) {
            var b = BookLayout.fit(text, BookLayout.BOX_W, height, BookLayout.BODY_SIZE, BookLayout.BODY_MIN, BookLayout.LINE, m);
            assertTrue(b.size() >= BookLayout.BODY_MIN && b.size() <= BookLayout.BODY_SIZE);
            assertTrue(b.height() <= height + 0.01, "lines must fit the page height");
            for (String line : b.lines()) assertTrue(m.width(line, b.size()) <= BookLayout.BOX_W + 0.5, "line wider than the page: " + line);
        }
        // a normal paragraph keeps the default size; an overfull side shrinks then ends with …
        assertEquals(BookLayout.BODY_SIZE, BookLayout.fit(para, BookLayout.BOX_W, height, BookLayout.BODY_SIZE, BookLayout.BODY_MIN, BookLayout.LINE, m).size());
        var over = BookLayout.fit("가".repeat(BookProtocol.MAX_SIDE), BookLayout.BOX_W, height, BookLayout.BODY_SIZE, BookLayout.BODY_MIN, BookLayout.LINE, m);
        assertEquals(BookLayout.BODY_MIN, over.size());
        assertTrue(over.lines().getLast().endsWith("…"));
        // boxes stay clear of the spine fold (x 790..875) and the gold lines (x 127 / 1535)
        assertTrue(BookLayout.LEFT_X > 127 + 40 && BookLayout.LEFT_X + BookLayout.BOX_W < 790 - 30);
        assertTrue(BookLayout.RIGHT_X > 875 + 60 && BookLayout.RIGHT_X + BookLayout.RIGHT_W < 1535 - 40);
        // tilted text (pages not fully open) still stays between the gold top / bottom lines (~y 72..80 / ~822..825)
        for (boolean right : new boolean[]{false, true}) {
            float x0 = right ? BookLayout.RIGHT_X : BookLayout.LEFT_X, x1 = x0 + BookLayout.boxWidth(right);
            for (float x : new float[]{x0, x1}) {
                assertTrue(BookLayout.tiltedY(right, x, BookLayout.BOX_TOP) > 130, "top corner too high");
                assertTrue(BookLayout.tiltedY(right, x, BookLayout.BOX_BOTTOM) < 800, "bottom corner too low");
            }
            // the outer side of each page is higher at the top (paper slopes down toward the spine)
            assertTrue(BookLayout.tiltedY(right, right ? x1 : x0, BookLayout.BOX_TOP) < BookLayout.tiltedY(right, right ? x0 : x1, BookLayout.BOX_TOP));
        }
    }

    @Test void titleFitsOneOrTwoLines() throws Exception {
        Font title = font("maruburi_semibold.ttf");
        BookLayout.Measure m = (t, s) -> CodexTextRasterizer.width(title, t, s);
        var shortTitle = BookLayout.title("오래된 마법서", m);
        assertEquals(1, shortTitle.lines().size());
        assertEquals(BookLayout.TITLE_SIZE, shortTitle.size());
        var longTitle = BookLayout.title("가".repeat(BookProtocol.MAX_TITLE), m);
        assertTrue(longTitle.lines().size() <= 2);
        for (String line : longTitle.lines()) assertTrue(m.width(line, longTitle.size()) <= BookLayout.BOX_W + 0.5);
    }

    @Test void canvasKeepsItsProportionsAtEveryWindowSize() {
        for (int[] s : new int[][]{{427, 240}, {640, 360}, {854, 480}, {1920, 1080}, {960, 1080}, {3840, 1600}}) {
            var l = BookLayout.fit(s[0], s[1]);
            assertTrue(l.scale() > 0);
            assertTrue(BookLayout.W * l.scale() <= s[0] * BookLayout.FRACTION + 0.01 && BookLayout.H * l.scale() <= s[1] * BookLayout.FRACTION + 0.01);
            assertEquals(s[0] / 2.0, l.x() + BookLayout.W * l.scale() / 2, 0.01);
            assertEquals(s[1] / 2.0, l.y() + BookLayout.H * l.scale() / 2, 0.01);
        }
    }
}
