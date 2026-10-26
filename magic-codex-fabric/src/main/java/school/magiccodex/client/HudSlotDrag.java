package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;

/**
 * With the Ctrl cursor or chat open, drag a bottom-right magic slot onto another to swap the two
 * ({@link CastingClient#swapHudSlots}): the normal six keep spell+key together, a 큐디치 match swaps spells only.
 */
public final class HudSlotDrag {
    private HudSlotDrag() {}
    private static int from = -1;

    static int from() { return HudPointer.active() ? from : -1; }

    /** @return true when the press picked a slot (nothing else should see it). */
    public static boolean press(double x, double y, int button) {
        if(HudCustomization.editingGesture())return false;
        if (button != 0) return false;
        var c = MinecraftClient.getInstance();
        int slot = SkillHudRenderer.slotAt(x, y, c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight());
        if (slot < 0 || CastingClient.state().bindings().get(slot).empty()) return false;
        from = slot;
        return true;
    }

    /** @return true when a drag was in progress (the release is used even if dropped outside). */
    public static boolean release(double x, double y, int button) {
        if (button != 0 || from < 0) return false;
        int a = from; from = -1;
        var c = MinecraftClient.getInstance();
        int b = SkillHudRenderer.slotAt(x, y, c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight());
        if (b >= 0 && b != a) {
            CastingClient.swapHudSlots(a, b);
            c.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 1.3f, .2f));
        }
        return true;
    }

    static void cancel() { from = -1; }
}
