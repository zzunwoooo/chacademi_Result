package school.magiccodex.client;

import school.magiccodex.client.CodexHitboxes.Rect;

/** Shared visual geometry in the original 1672x941 coordinate system. */
public final class CodexComposition {
    private CodexComposition() {}
    public static final int CARD_WIDTH = 208;
    public static final int CARD_HEIGHT = 186;
    public static final int CARD_GAP = 14;
    public static final int ART_CENTER_Y = 62;
    public static final int ART_SIZE = 112;
    public static final int IMAGE_BOTTOM = 128;
    public static final int NAME_CENTER_Y = 146;
    public static final int NAME_SIZE = 20;
    public static final int BADGE_TOP = 162;
    public static final int BADGE_HEIGHT = 18;
    public static final int BADGE_TEXT_SIZE = 15;

    public static Rect card(int index) {
        if (index < 0 || index >= 9) throw new IndexOutOfBoundsException(index);
        return new Rect(357 + index % 3 * (CARD_WIDTH + CARD_GAP),
                235 + index / 3 * (CARD_HEIGHT + CARD_GAP), CARD_WIDTH, CARD_HEIGHT);
    }
}
