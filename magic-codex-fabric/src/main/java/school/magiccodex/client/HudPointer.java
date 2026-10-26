package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;

/**
 * The HUD pointer: the real mouse while the Ctrl cursor screen is open, or the magic aim cursor in magic mode.
 * Coordinates are GUI-scaled pixels.
 */
public final class HudPointer {
    private HudPointer() {}

    public static boolean active() {
        var c = MinecraftClient.getInstance();
        return c.currentScreen instanceof HudCursorScreen || c.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen;
    }

    /** HUD stays drawn under these screens: none, the Ctrl cursor screen and chat. */
    public static boolean hudVisibleUnder(net.minecraft.client.gui.screen.Screen screen) {
        return screen == null || screen instanceof HudCursorScreen || screen instanceof net.minecraft.client.gui.screen.ChatScreen;
    }

    public static double x() {
        var c = MinecraftClient.getInstance();
        var w = c.getWindow();
        return HudCustomization.pointer(HudCustomization.rawX(),HudCustomization.rawY())[0];
    }

    public static double y() {
        var c = MinecraftClient.getInstance();
        var w = c.getWindow();
        return HudCustomization.pointer(HudCustomization.rawX(),HudCustomization.rawY())[1];
    }
}
