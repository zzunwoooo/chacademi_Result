package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.ToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;

class CodexTextLayoutTest {
    private static final ToDoubleFunction<String> WIDTH=s->s.codePointCount(0,s.length());
    @Test void wrapsKoreanAndKeepsFullTextAvailableAfterPreviewTruncation() {
        String text="공중에 떠 있는 몬스터에게 윈드 프레스 50회 사용하기.";
        var lines=CodexTextLayout.wrap(text,14,WIDTH);
        assertTrue(lines.size()>2);
        assertEquals(text.replace(" ",""),String.join("",lines).replace(" ",""));
        var preview=CodexTextLayout.preview(lines,2,14,WIDTH);
        assertEquals(2,preview.size());assertTrue(preview.getLast().endsWith("…"));
        assertTrue(preview.stream().allMatch(s->WIDTH.applyAsDouble(s)<=14));
        assertFalse(lines.getLast().endsWith("…"));
    }
    @Test void preservesExplicitParagraphsAndBlankLines() {
        assertEquals(List.of("첫 줄","","다음 줄"),CodexTextLayout.wrap("첫 줄\r\n\r\n다음 줄",20,WIDTH));
    }
    @Test void overwideGlyphMakesProgressAndNeverSplitsSurrogatePairs() {
        assertEquals(List.of("😀","한","글"),CodexTextLayout.wrap("😀한글",0.5,WIDTH));
    }
    @Test void shortTextIsNotEllipsized() {
        var lines=CodexTextLayout.wrap("짧은 설명",20,WIDTH);
        assertEquals(lines,CodexTextLayout.preview(lines,2,20,WIDTH));
    }
}
