package school.magiccodex.paper;

import java.util.List;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OperatorCommandsTest {
    @Test void koreanGameModeNamesMapBothWays() {
        assertEquals(List.of("서바이벌", "크리에이티브", "모험", "관전"), List.copyOf(OperatorCommands.GAME_MODES.keySet()));
        for (var e : OperatorCommands.GAME_MODES.entrySet()) {
            assertEquals(e.getValue(), OperatorCommands.gameMode(e.getKey()));
            assertEquals(e.getKey(), OperatorCommands.gameModeName(e.getValue()));
        }
        assertEquals(GameMode.CREATIVE, OperatorCommands.gameMode(" 크리에이티브 "));
    }

    @Test void unknownGameModeIsAUserFixableKoreanError() {
        for (String bad : new String[]{"creative", "1", "", "서바"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> OperatorCommands.gameMode(bad));
            assertTrue(error.getMessage().contains("서바이벌"));
        }
        assertThrows(IllegalArgumentException.class, () -> OperatorCommands.gameMode(null));
    }
}
