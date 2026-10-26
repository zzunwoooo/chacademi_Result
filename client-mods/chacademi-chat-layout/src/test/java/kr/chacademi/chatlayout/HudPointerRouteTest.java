package kr.chacademi.chatlayout;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudPointerRouteTest {
    @Test void onlyKnownCursorScreenEnablesOptionalRouting() {
        assertTrue(HudPointerRoute.isMagicCursorClassName("school.magiccodex.client.HudCursorScreen"));
        assertFalse(HudPointerRoute.isMagicCursorClassName("net.minecraft.client.gui.screens.Screen"));
        assertFalse(HudPointerRoute.isMagicCursorClassName("school.magiccodex.client.CodexScreen"));
        assertFalse(HudPointerRoute.isMagicCursorClassName("other.mod.HudCursorScreen"));
        assertFalse(HudPointerRoute.isMagicCursorClassName(null));
    }

    @Test void handledChatGestureDoesNotClickOriginalHud() {
        AtomicInteger addon = new AtomicInteger(), original = new AtomicInteger();
        assertTrue(HudPointerRoute.route(true, () -> { addon.incrementAndGet(); return true; },
            () -> { original.incrementAndGet(); return false; }));
        assertEquals(1, addon.get());
        assertEquals(0, original.get());
    }

    @Test void outsideChatGestureReachesOriginalHudExactlyOnce() {
        AtomicInteger addon = new AtomicInteger(), original = new AtomicInteger();
        assertTrue(HudPointerRoute.route(true, () -> { addon.incrementAndGet(); return false; },
            () -> { original.incrementAndGet(); return true; }));
        assertEquals(1, addon.get());
        assertEquals(1, original.get());
    }

    @Test void ordinaryScreenKeepsOriginalInputAndReturnValue() {
        AtomicInteger addon = new AtomicInteger(), original = new AtomicInteger();
        assertFalse(HudPointerRoute.route(false, () -> { addon.incrementAndGet(); return true; },
            () -> { original.incrementAndGet(); return false; }));
        assertEquals(0, addon.get());
        assertEquals(1, original.get());
    }
}