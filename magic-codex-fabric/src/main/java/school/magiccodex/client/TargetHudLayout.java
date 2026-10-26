package school.magiccodex.client;

/** Pure math of the target HUD (no Minecraft classes), so it is unit-tested. Units: frame-asset px (frame.png 2048 x 84). */
final class TargetHudLayout {
    private TargetHudLayout() {}
    /** HUD width in GUI px for a screen width (HUD v4: one long gauge, sized so its text stays readable). */
    static float width(int screenW) { return Math.clamp(screenW * .46f, 260, 620); }
    /** Center x of the n-th icon counted from the right (0 = rightmost). */
    static float iconX(float right, float diameter, float step, int n) { return right - diameter / 2 - step * n; }
}
