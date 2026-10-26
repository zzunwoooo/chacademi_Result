package kr.chacademi.chatlayout;

import net.minecraft.client.gui.screens.ChatScreen;

/** Optional MagicCodex integration without loading or requiring its classes. */
public final class InteractionMode {
    private InteractionMode() {}

    public static boolean isMagicCursor(Object screen) {
        return screen != null && HudPointerRoute.isMagicCursorClassName(screen.getClass().getName());
    }

    public static boolean interactionAllowed(Object screen) {
        return screen instanceof ChatScreen || isMagicCursor(screen);
    }
}