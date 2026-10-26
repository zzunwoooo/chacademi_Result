package school.magiccodex.client;

/**
 * Taming target card (monster_atlas frame drawn at 600x110 units). Inner geometry was measured on the atlas:
 * frame inside x 50..550, corner diamonds up to x 58, divider star x 330..354; inside rows normal 22..100,
 * shiny 11..96. Every half keeps the same {@link #PAD} on all four sides.
 */
final class MonsterPanel {
    private MonsterPanel() {}
    static final float PAD = 10, NAME = 18, HP = 13, STATUS = 18, BAR = 8;
    static final float LEFT = 50, DIVIDER_L = 330, DIVIDER_R = 354, RIGHT = 550;
    record Box(float top, float bottom) {
        float left() { return LEFT + PAD; }
        float leftWidth() { return DIVIDER_L - PAD - left(); }
        float right() { return DIVIDER_R + PAD; }
        float rightEnd() { return RIGHT - PAD; }
        float centerY() { return (top + bottom) / 2; }
        /** Name, bar and HP text spread over the inner height with equal gaps. */
        float gap() { return (bottom - top - 2 * PAD - NAME - BAR - HP) / 2; }
        float nameY() { return top + PAD + NAME / 2; }
        float barY() { return top + PAD + NAME + gap(); }
        float hpY() { return barY() + BAR + gap() + HP / 2; }
        float emblem() { return Math.min(40, bottom - top - 2 * PAD); }
    }
    static Box box(boolean shiny) { return shiny ? new Box(11, 96) : new Box(22, 100); }
    /** Constant on-screen size: depends only on the window, never on distance. */
    static float scale(int width, int height) { return Math.clamp(Math.min(width / 1400f, height / 800f) * .72f, .14f, .35f); }
    /** Shrinks the font so the text fits the given width. */
    static float fit(float size, float width, float max) { return width <= max ? size : size * max / Math.max(1, width); }
}
