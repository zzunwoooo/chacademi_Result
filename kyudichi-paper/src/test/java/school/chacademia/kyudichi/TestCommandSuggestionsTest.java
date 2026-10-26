package school.chacademia.kyudichi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TestCommandSuggestionsTest {
    @Test void startOffersAllSupportedOptions() {
        assertEquals(List.of("준비:0", "시간:3", "스니치:0", "무적", "블러저끔"), TestCommandSuggestions.complete(""));
    }
    @Test void laterArgumentsStillOfferRemainingOptions() {
        assertEquals(List.of("스니치:0", "무적", "블러저끔"), TestCommandSuggestions.complete("준비:0 시간:3 "));
    }
    @Test void enteredCustomValueSuppressesSameOptionKey() {
        assertEquals(List.of(), TestCommandSuggestions.complete("준비:20 준"));
    }
    @Test void optionPrefixFiltersAndFlagsAreNotRepeated() {
        assertEquals(List.of("블러저끔"), TestCommandSuggestions.complete("무적 블"));
        assertFalse(TestCommandSuggestions.complete("무적 ").contains("무적"));
    }
}
