package kr.chacademi.chatlayout;

import java.util.function.BooleanSupplier;

/** Dispatch policy independent of Minecraft so optional-screen routing can be verified. */
public final class HudPointerRoute {
    private static final String MAGIC_CURSOR_SCREEN = "school.magiccodex.client.HudCursorScreen";
    private HudPointerRoute() {}

    public static boolean isMagicCursorClassName(String className) {
        return MAGIC_CURSOR_SCREEN.equals(className);
    }

    public static boolean route(boolean magicCursor, BooleanSupplier addon, BooleanSupplier original) {
        return magicCursor && addon.getAsBoolean() || original.getAsBoolean();
    }
}