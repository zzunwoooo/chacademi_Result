package school.magiccodex.client;

/** Reel-time fish state for the fishing minigame (2026-10-21). Pure so it can be unit-tested. */
final class FishMood {
    static final int NORMAL = 0, RESIST = 1, TIRED = 2;
    /** Seconds the fish rests after each pull, and the stamina below which it stays tired. */
    static final float TIRED_WINDOW = 1.1f, TIRED_STAMINA = .25f;
    private FishMood() { }
    /**
     * @param surge     SURGE pattern (rests before its next pull)
     * @param event     a pull event is running
     * @param mod       pull multiplier from the pattern
     * @param eventEnd  end of the last pull event (0 = none yet)
     */
    static int of(boolean surge, float t, boolean event, float mod, float stamina, float eventEnd, float nextEvent) {
        boolean surgeRest = surge && !event && nextEvent - t < 1.2f;
        boolean rest = eventEnd > 0 && t >= eventEnd && t < eventEnd + TIRED_WINDOW;
        if (!event && (stamina < TIRED_STAMINA || rest || surgeRest)) return TIRED;
        if ((event && mod > 1.05f) || mod > 1.25f) return RESIST;
        return NORMAL;
    }
}
