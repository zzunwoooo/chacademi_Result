package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a spell hit leaves (or refreshes) an element mark, only when someone listens. Listeners may change how
 * long this caster's mark lasts (artifacts: catalyst powder, sticky ink, slow inkwell). Does not fire for spreads.
 */
public final class MagicMarkEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity caster, target;
    private final String element;
    private int ticks;

    public MagicMarkEvent(LivingEntity caster, LivingEntity target, String element, int ticks) {
        this.caster = caster; this.target = target; this.element = element; this.ticks = ticks;
    }
    public LivingEntity getCaster() { return caster; }
    public LivingEntity getTarget() { return target; }
    public String getElement() { return element; }
    public int getTicks() { return ticks; }
    public void setTicks(int ticks) { this.ticks = Math.clamp(ticks, 1, 20 * 60); }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }
}
