package kr.chacademi.chatlayout;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudChatKeyPolicyTest {
    private static boolean magic(String className) {
        return HudPointerRoute.isMagicCursorClassName(className);
    }

    /** Event-order fixture: callbacks target the current screen, then the game tick opens chat. */
    private static final class Pipeline {
        final Object hud = new Object();
        final HudChatKeyPolicy policy = new HudChatKeyPolicy();
        Object screen = hud;
        StringBuilder chat;
        int opens, hudCharacters, fallbackKeys;

        boolean key(int action, boolean chatMatches, boolean commandMatches) {
            if (policy.request(screen, screen == hud, action, chatMatches, commandMatches)) return true;
            fallbackKeys++;
            return false;
        }

        void character(char value) {
            if (screen == hud) hudCharacters++;
            else if (chat != null) chat.append(value);
        }

        void tick() {
            String initial = policy.take(screen);
            if (initial != null) {
                screen = new Object();
                chat = new StringBuilder(initial);
                opens++;
            }
        }
    }

    @Test void boundChatKeyOpensOnceAfterCharacterCallbacksFinish() {
        Pipeline pipeline = new Pipeline();
        assertTrue(pipeline.key(1, true, false));
        assertSame(pipeline.hud, pipeline.screen);
        pipeline.character('t');
        assertNull(pipeline.chat);
        pipeline.tick();
        assertEquals("", pipeline.chat.toString());
        assertEquals(1, pipeline.hudCharacters);
        assertEquals(0, pipeline.fallbackKeys);
        pipeline.tick();
        assertEquals(1, pipeline.opens);
        pipeline.character('x');
        assertEquals("x", pipeline.chat.toString());
    }

    @Test void commandBindingStartsWithExactlyOneSlash() {
        Pipeline pipeline = new Pipeline();
        assertTrue(pipeline.key(1, false, true));
        pipeline.character('/');
        pipeline.tick();
        assertEquals("/", pipeline.chat.toString());
        assertEquals(1, pipeline.hudCharacters);
        assertEquals(1, pipeline.opens);
        pipeline.character('h');
        assertEquals("/h", pipeline.chat.toString());
    }

    @Test void chatBindingWinsIfBothBindingsMatchAsInVanilla() {
        Object hud = new Object();
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        assertTrue(policy.request(hud, true, 1, true, true));
        assertEquals("", policy.take(hud));
    }

    @Test void ordinaryScreensAndUnboundKeysKeepTheirOriginalDelegate() {
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        AtomicInteger fallback = new AtomicInteger();
        Object ordinary = new Object();
        for (String name : new String[] {
            "net.minecraft.client.gui.screens.ChatScreen",
            "net.minecraft.client.gui.screens.Screen",
            "other.mod.HudCursorScreen",
            "school.magiccodex.client.CodexScreen"
        }) {
            if (!policy.request(ordinary, magic(name), 1, true, true)) fallback.incrementAndGet();
        }
        assertEquals(4, fallback.get());
        Object hud = new Object();
        if (!policy.request(hud, true, 1, false, false)) fallback.incrementAndGet();
        assertEquals(5, fallback.get());
        assertNull(policy.take(hud));
    }

    @Test void releaseAndRepeatCannotQueueOrReplaceAPress() {
        Object hud = new Object();
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        assertFalse(policy.request(hud, true, 0, true, false));
        assertFalse(policy.request(hud, true, 2, false, true));
        assertNull(policy.take(hud));
        assertTrue(policy.request(hud, true, 1, true, false));
        assertFalse(policy.request(hud, true, 2, false, true));
        assertFalse(policy.request(hud, true, 0, true, false));
        assertEquals("", policy.take(hud));
    }

    @Test void changedScreenCancelsRequestWithoutOpeningLater() {
        Object hud = new Object();
        Object replacement = new Object();
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        assertTrue(policy.request(hud, true, 1, true, false));
        assertNull(policy.take(replacement));
        assertNull(policy.take(hud));
        assertTrue(policy.request(hud, true, 1, false, true));
        assertNull(policy.take(null));
        assertNull(policy.take(hud));
    }

    @Test void screenIdentityCannotBeReplacedByAnEqualScreen() {
        class EqualScreen {
            @Override public boolean equals(Object other) { return other instanceof EqualScreen; }
            @Override public int hashCode() { return 1; }
        }
        Object hud = new EqualScreen();
        Object replacement = new EqualScreen();
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        assertEquals(hud, replacement);
        assertTrue(policy.request(hud, true, 1, true, false));
        assertNull(policy.take(replacement));
        assertNull(policy.take(hud));
    }

    @Test void missingScreenNeverQueuesAChatRequest() {
        HudChatKeyPolicy policy = new HudChatKeyPolicy();
        assertFalse(policy.request(null, true, 1, true, false));
        assertNull(policy.take(null));
    }
}
