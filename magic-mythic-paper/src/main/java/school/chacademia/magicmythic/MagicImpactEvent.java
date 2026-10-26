package school.chacademia.magicmythic;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired where a spell lands: instant hits (HIT), every step of a travelling bolt (BOLT) and every zone pulse (ZONE).
 * Lets other plugins (dungeon gimmicks) react to magic without guessing.
 * `element` comes from the mechanic's element= option (catalogue category: wind, fire, water, earth, light, dark).
 * <p>Cancel contract (2026-10-21):
 * <ul>
 *  <li>HIT: cancel = "a gimmick consumed this hit as an input". The cast then counts as successful even with no entity
 *      hit (no refund). Entity damage of the same hit is NOT affected (mobs next to a gimmick still take damage).
 *      Listeners should cancel only when an input really fired. Passives and summons ignore the result.</li>
 *  <li>BOLT: cancel = the bolt ends at that position (its end burst aoe= still applies there).</li>
 *  <li>ZONE: cancelling is ignored.</li>
 * </ul>
 */
public final class MagicImpactEvent extends Event implements Cancellable {
    public enum Kind { HIT, BOLT, ZONE }
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity caster;
    private final String element;
    private final Location location;
    private final double radius;
    private final Kind kind;
    private boolean cancelled;

    public MagicImpactEvent(LivingEntity caster, String element, Location location, double radius, Kind kind) {
        this.caster = caster; this.element = element; this.location = location; this.radius = radius; this.kind = kind;
    }
    public LivingEntity getCaster() { return caster; }
    public String getElement() { return element; }
    public Location getLocation() { return location.clone(); }
    public double getRadius() { return radius; }
    public Kind getKind() { return kind; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    /** Cheap when nobody listens. @return false when a listener cancelled it */
    static boolean fire(LivingEntity caster, String element, Location at, double radius, Kind kind) {
        if (HANDLERS.getRegisteredListeners().length == 0) return true;
        var e = new MagicImpactEvent(caster, element, at, radius, kind);
        org.bukkit.Bukkit.getPluginManager().callEvent(e);
        return !e.isCancelled();
    }
}
