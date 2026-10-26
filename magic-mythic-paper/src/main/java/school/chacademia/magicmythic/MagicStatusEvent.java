package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a status (디버프) is about to be applied to a PLAYER, from any source (spells, reactions, mob skills),
 * only when someone listens. Listeners (artifacts: CC duration reduction / immunity) may change the duration or
 * cancel it. Not fired for 마나번 (its ticks are an internal cooldown, the mana loss is instant).
 */
public final class MagicStatusEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity source;
    private final Player target;
    private final String status;
    private int ticks;
    private boolean cancelled;

    public MagicStatusEvent(LivingEntity source, Player target, String status, int ticks) {
        this.source = source; this.target = target; this.status = status; this.ticks = ticks;
    }
    /** Who applied it (may be null). */
    public LivingEntity getSource() { return source; }
    public Player getTarget() { return target; }
    /** Status id (stun, root, disarm, slow, burn, recovery_seal, magic_weakness, gravity, movement_lock, poison, physical_weakness, blindness, potion_seal, sleep, fear). */
    public String getStatus() { return status; }
    public int getTicks() { return ticks; }
    public void setTicks(int v) { ticks = Math.max(1, v); }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean v) { cancelled = v; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
