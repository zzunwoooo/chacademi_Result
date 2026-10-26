package school.chacademia.gimmick;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignalsTest {
    @Test void outputOpensOnlyWhenWiredAndEveryInputIsOn() {
        assertFalse(Signals.allOn(List.of()), "nothing wired: stays shut");
        assertTrue(Signals.allOn(List.of(true)));
        assertTrue(Signals.allOn(List.of(true, true)));
        assertFalse(Signals.allOn(List.of(true, false)), "two braziers on one door: both must burn");
    }

    @Test void everyModelKindHasARole() {
        for (String k : List.of("lever", "plate", "brazier", "monument", "seal", "pot", "valve", "turbine", "wheel", "receiver", "pillar", "dial", "statue"))
            assertEquals("입력", Signals.role(k), k);
        for (String k : List.of("door", "sluice", "bridge", "platform", "chest", "gate", "vent")) assertEquals("출력", Signals.role(k), k);
        assertEquals("도관", Signals.role("conduit"));
        assertEquals("장치", Signals.role("mirror"));
        assertEquals("장치", Signals.role("prism"));
    }

    @Test void answersCycleWithinTheirRange() {
        assertEquals(3, Signals.defaultAnswer("pillar", 0));
        assertEquals(2, Signals.defaultAnswer("dial", 0));
        assertEquals(1, Signals.defaultAnswer("statue", 3), "statue: the side it does not face at first");
        assertEquals(7, Signals.answerCount("receiver"));
        assertEquals(8, Signals.answerCount("platform"));
        assertFalse(Signals.hasAnswer("lever"));
        assertEquals("fire", Signals.elementOf("gm_element_monument_fire"));
        assertEquals("", Signals.elementOf("gm_fire_brazier"));
    }

    @Test void conduitPortsTurnWithTheGimmick() {
        assertArrayEquals(new int[]{0, 0, 1}, Signals.port("front", 0));
        assertArrayEquals(new int[]{-1, 0, 0}, Signals.port("right", 0));
        assertArrayEquals(new int[]{-1, 0, 0}, Signals.port("front", 1));
    }
}
