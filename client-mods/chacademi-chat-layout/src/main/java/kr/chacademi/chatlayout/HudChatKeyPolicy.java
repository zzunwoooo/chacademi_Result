package kr.chacademi.chatlayout;

/** Delays only a handled HUD chat opener until GLFW's character events have finished. */
public final class HudChatKeyPolicy {
    private Object expectedScreen;
    private String initial;

    public boolean request(Object screen, boolean magicCursor, int action,
                           boolean chatMatches, boolean commandMatches) {
        if (screen == null || !magicCursor || action != 1 || (!chatMatches && !commandMatches)) {
            return false;
        }
        expectedScreen = screen;
        // Vanilla checks the chat binding before the command binding.
        initial = chatMatches ? "" : "/";
        return true;
    }

    /** Takes one request; a replacement screen cancels it, including a new equal instance. */
    public String take(Object currentScreen) {
        Object expected = expectedScreen;
        String requested = initial;
        expectedScreen = null;
        initial = null;
        return expected != null && expected == currentScreen ? requested : null;
    }
}
