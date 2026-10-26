package school.chacademia.magicmythic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 진실의 눈 (true_sight): a player reveals hidden things around `location` for `ticks`.
 * Puzzle/gimmick/story plugins listen to show their hidden clues (glow, particles, a VFX clue phase ...).
 */
public final class MagicRevealEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player caster; private final Location location; private final double radius; private final int ticks;
    public MagicRevealEvent(Player caster, Location location, double radius, int ticks) {
        this.caster = caster; this.location = location.clone(); this.radius = radius; this.ticks = ticks;
    }
    public Player getCaster() { return caster; }
    public Location getLocation() { return location.clone(); }
    public double getRadius() { return radius; }
    public int getTicks() { return ticks; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
