package school.magiccodex.client;

import java.util.function.BooleanSupplier;

/** Capture a menu gesture before overlapping chat handlers, including its release. */
public final class TopMenuMouseInput {
    private boolean captured;

    public boolean handle(int button, int action, BooleanSupplier menuClick) {
        if (button != 0) return false;
        if (action == 0) {
            boolean handled = captured;
            captured = false;
            return handled;
        }
        if (action != 1) return captured;
        captured = menuClick.getAsBoolean();
        return captured;
    }
}
