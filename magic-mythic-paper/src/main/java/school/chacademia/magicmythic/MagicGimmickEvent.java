package school.chacademia.magicmythic;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A dungeon-gimmick spell (239~250, and 35 다시 타오르는 불 / 48 심지 줄이기) was cast. The gimmick plugin (ChacaGimmick)
 * looks for the gimmick the caster aims at within {@link #getRange()} and applies the action. Nobody handled it = no
 * gimmick there: the mechanic refunds mana + cooldown and shows {@link #getMessage()} (or a default).
 *
 * Actions: thaw, steam, level, weight, grow, beam, tune, tint, wake, rewind, conduit, mark, ignite, extinguish.
 */
public final class MagicGimmickEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player caster; private final String action, spell; private final double range; private final boolean sneaking;
    private boolean handled; private String message;

    public MagicGimmickEvent(Player caster, String action, String spell, double range, boolean sneaking) {
        this.caster = caster; this.action = action; this.spell = spell; this.range = range; this.sneaking = sneaking;
    }
    public Player getCaster() { return caster; }
    /** Lower-case action name (see the class comment). */
    public String getAction() { return action; }
    /** Spell id (for VFX / logs). */
    public String getSpell() { return spell; }
    public double getRange() { return range; }
    /** The caster was sneaking when casting (241 수위 조절: sneak = lower). */
    public boolean isSneaking() { return sneaking; }
    public boolean isHandled() { return handled; }
    public void setHandled(boolean handled) { this.handled = handled; }
    /** Shown on the action bar when the cast did not apply (refunded). */
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
