package school.magiccodex.client.kui;

import java.util.List;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * In-match HUD pieces in hud_layout coordinates (1672×941, reference 36e61f2e).
 * The game places each group with its own anchor: score board top centre, feed top right, F key bottom centre.
 */
public final class HudView {
    private HudView() {}
    public static final float W = 1672, H = 941;

    // ================================================================== score board (anchor: top centre, x 836)
    public record Score(String leftLabel, int leftScore, int leftColor, String rightLabel, int rightScore, int rightColor,
                        String time, String sub, boolean snitchOut, boolean duel) {
        public Score(String leftLabel, int leftScore, int leftColor, String rightLabel, int rightScore, int rightColor, String time, String sub, boolean snitchOut) {
            this(leftLabel, leftScore, leftColor, rightLabel, rightScore, rightColor, time, sub, snitchOut, false);
        }
    }

    public static void score(Pen p, Score s) {
        p.image("hud/scoreboard", 514, 14, 646, 84);
        p.image("hud/timer_plate", 751, 21, 171, 74);
        p.text(s.leftLabel(), 618, 61, 23, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text(String.valueOf(s.leftScore()), 703, 61, 46, s.leftColor(), Face.TITLE, Canvas.CENTER);
        p.text(s.time(), 836, s.sub().isEmpty() ? 60 : 54, 36, Theme.WHITE, Face.SERIF, Canvas.CENTER);
        if (!s.sub().isEmpty()) p.text(s.sub(), 836, 80, 14, Theme.GOLD, Face.SANS_BOLD, Canvas.CENTER);
        p.text(String.valueOf(s.rightScore()), 968, 61, 46, s.rightColor(), Face.TITLE, Canvas.CENTER);
        p.text(s.rightLabel(), 1054, 61, 23, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        if (s.duel()) return; // 1:1 대전: no snitch (owner 2026-10-19)
        // owner 2026-10-17: 14 px lower than the reference so the wings clear the score board
        if (s.snitchOut()) {
            p.image("hud/indicator_snitch", 798, 112, 77, 70);
        } else {
            p.image("hud/snitch_locked", 766, 110, 139, 47);
        }
        p.image("hud/snitch_label", 796, 169, 80, 38);
        p.text(s.snitchOut() ? "등장" : "잠김", 836, 187, 17, s.snitchOut() ? Theme.GOLD : Theme.CREAM, Face.SANS_BOLD, Canvas.CENTER);
    }

    // ================================================================== feed (anchor: top right, x 1672)
    /** type 1 골 · 2 처치 · 3 스니치 · 4 연장 · 5 포기/나감. */
    public record FeedLine(int type, String actor, String target, int points, int actorColor, int targetColor, float alpha) {}

    public static void feed(Pen p, List<FeedLine> lines) {
        for (int i = 0; i < lines.size() && i < 4; i++) {
            var f = lines.get(i);
            float y = 28 + i * 63;
            String part = f.type() == 1 ? "hud/feed_goal" : f.type() == 2 ? "hud/feed_kill" : "hud/feed_background";
            p.c.image(part, 1380, y, 277, 67, f.alpha());
            int a = Math.round(f.alpha() * 255) << 24;
            float x = f.type() == 1 || f.type() == 2 ? 1450 : 1410, mid = y + 33;
            switch (f.type()) {
                case 1 -> { x = word(p, f.actor(), x, mid, f.actorColor(), a) + 10; x = word(p, "골", x, mid, Theme.WHITE, a) + 10; word(p, "+" + f.points(), x, mid, Theme.GOLD, a); }
                case 2 -> {
                    if (f.actor().isEmpty()) { x = word(p, f.target(), x, mid, f.targetColor(), a) + 10; word(p, "쓰러짐", x, mid, Theme.WHITE, a); }
                    else { x = word(p, f.actor(), x, mid, f.actorColor(), a) + 10; x = word(p, "→", x, mid, Theme.WHITE, a) + 10;
                           x = word(p, f.target(), x, mid, f.targetColor(), a) + 10; word(p, "처치", x, mid, Theme.WHITE, a); }
                }
                case 3 -> { x = word(p, f.actor(), x, mid, f.actorColor(), a) + 10; x = word(p, "스니치", x, mid, Theme.WHITE, a) + 10; word(p, "+" + f.points(), x, mid, Theme.GOLD, a); }
                case 4 -> word(p, "골든골 연장!", x, mid, Theme.GOLD, a);
                default -> word(p, f.actor().isEmpty() ? f.target() + " 포기" : f.actor() + " 나감", x, mid, Theme.MUTED, a);
            }
        }
    }
    private static final float FEED_SIZE = 22, FEED_RIGHT = 1640;
    private static float word(Pen p, String s, float x, float y, int color, int alpha) {
        if (s.isEmpty()) return x;
        float w = p.width(s, FEED_SIZE, Face.SERIF);
        float size = x + w > FEED_RIGHT ? FEED_SIZE * Math.max(.6f, (FEED_RIGHT - x) / w) : FEED_SIZE;
        p.c.text(s, x, y, size, (color & 0xFFFFFF) | alpha, Face.SERIF, Canvas.LEFT);
        return x + p.width(s, size, Face.SERIF);
    }

    // ================================================================== F key + quaffle hold timer (anchor: bottom centre)
    /** action 0 없음 1 퀘이플 잡기 2 던지기 3 블러저 쳐내기 4 스니치 잡기. */
    public record Keys(int action, float cooldown, float lit, int holdMs, int holdTotalMs) {}
    static final String[] KEY_PARTS = {"keys/key_inactive", "keys/key_quaffle_catch", "keys/key_quaffle_throw", "keys/key_bludger_deflect", "keys/key_snitch_catch"};
    /** Where the pair is drawn: the F key alone sits in the centre, with the timer they sit side by side like the reference. */
    public static void keys(Pen p, Keys k) {
        boolean holding = k.holdMs() > 0;
        float keyX = holding ? 724 : 780, keyY = 808;
        // key art has transparent padding (225×220 of 256): scale so the drawn frame fills the 112×116 slot
        String part = KEY_PARTS[Math.clamp(k.action(), 0, 4)];
        p.image(part, keyX - 7.5f, keyY - 10.5f, 127, 135);
        if (k.lit() > 0) p.c.image(part, keyX - 7.5f - 6 * k.lit(), keyY - 10.5f - 6 * k.lit(), 127 + 12 * k.lit(), 135 + 12 * k.lit(), .35f * k.lit());
        if (k.cooldown() > 0) p.c.arc(keyX + 56, keyY + 60, 0, 40, 1 - k.cooldown(), 1, 0xA0050A12);
        if (holding) {
            p.image("hud/quaffle_timer_frame", 836, 808, 113, 116);
            // full ring + ball, then the used part of the ring dimmed (README: progress drawn at runtime)
            float cx = 836 + 56.5f, cy = 808 + 52;
            p.image("hud/quaffle_meter_full", cx - 30, cy - 30, 60, 60);
            float left = Math.clamp(k.holdMs() / (float) Math.max(1, k.holdTotalMs()), 0, 1);
            p.c.arc(cx, cy, 23, 29, left, 1, 0xFF1A1518);
            p.text((k.holdMs() + 999) / 1000 + "초", cx, 808 + 89, 18, Theme.WHITE, Face.SANS_BOLD, Canvas.CENTER);
        }
    }
}
