package school.chacademia.housing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingProtocol;

/** #108 전시 값: DB 의 아티팩트 id 문자열 나누기. */
class ShowcasesTest {
    @Test void split() {
        assertEquals(List.of(), Showcases.split(null));
        assertEquals(List.of(), Showcases.split(""));
        assertEquals(List.of("a", "b"), Showcases.split(" a, ,b,a,"));
        assertEquals(HousingProtocol.MAX_SHOWCASE_ARTIFACTS, Showcases.split("a,b,c,d,e,f,g,h,i,j").size());
        assertEquals(List.of("ok"), Showcases.split("x".repeat(HousingProtocol.SHOWCASE_CHARS + 1) + ",ok"));
    }
    @Test void clip() {
        assertEquals("", Showcases.clip(null));
        assertEquals(HousingProtocol.SHOWCASE_CHARS, Showcases.clip("y".repeat(100)).length());
    }
}
