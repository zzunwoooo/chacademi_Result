package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before a spell heals a player (zone heal, rainbow reaction), only when someone listens. Amount is editable. */
public final class MagicHealEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity caster;
    private final Player target;
    private final String reaction;
    private double amount;

    public MagicHealEvent(LivingEntity caster, Player target, String reaction, double amount) {
        this.caster = caster; this.target = target; this.reaction = reaction; this.amount = amount;
    }
    public LivingEntity getCaster() { return caster; }
    public Player getTarget() { return target; }
    /** Reaction id ("rainbow") or "" for spell heals. */
    public String getReaction() { return reaction; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Double.isFinite(amount) ? Math.max(0, amount) : 0; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
