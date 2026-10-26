package school.magiccodex.client;

import java.util.Locale;
import school.magiccodex.protocol.TamingProtocol;

/** Presentation only; odds always come from the validated server response. */
final class TamingChanceLabel {
    private TamingChanceLabel() {}
    static boolean visible(TamingProtocol.State state, long age) {
        if (age < 0) return false;
        return switch (state.status()) {
            case TamingProtocol.TARGET -> age <= 1200;
            case TamingProtocol.CHANNEL -> age <= 1800;
            case TamingProtocol.PENDING -> age <= 15000;
            case TamingProtocol.SUCCESS, TamingProtocol.FAIL -> age <= 2400;
            default -> false;
        };
    }
    static String text(double chance) { return String.format(Locale.ROOT, "교화 %.1f%%", chance * 100); }
    static float scale(double distance) { return (float)Math.clamp(distance * .006, .025, .065); }
}
