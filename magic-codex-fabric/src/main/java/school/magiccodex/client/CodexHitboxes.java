package school.magiccodex.client;

/** Every hitbox uses the same unscaled PNG coordinates as rendering. */
public final class CodexHitboxes {
    private CodexHitboxes() {}
    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double x, double y) {
            return x >= this.x && y >= this.y && x < this.x + width && y < this.y + height;
        }
        public float centerX() { return x + width / 2f; }
        public float centerY() { return y + height / 2f; }
    }
    public static final Rect CLOSE = new Rect(1482, 68, 48, 46);
    public static final Rect SEARCH = new Rect(357, 180, 364, 40);
    public static final Rect PREVIOUS = new Rect(492, 833, 48, 40);
    public static final Rect NEXT = new Rect(820, 833, 49, 40);
    public static final Rect DONATE = new Rect(1058, 741, 465, 80);
    public static Rect category(int index) { return new Rect(132, 198 + index * 72, 184, 45); }
    public static Rect filter(int index) {
        return switch (index) {
            case 0 -> new Rect(735, 180, 83, 40);
            case 1 -> new Rect(827, 180, 87, 40);
            case 2 -> new Rect(920, 180, 89, 40);
            default -> throw new IndexOutOfBoundsException(index);
        };
    }
    public static Rect card(int index) {
        return CodexComposition.card(index);
    }
}
