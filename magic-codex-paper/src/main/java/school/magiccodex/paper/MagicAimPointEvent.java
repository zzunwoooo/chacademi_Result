package school.magiccodex.paper;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the server thread while an aimed (AIM route) spell is prepared and the look ray hit nothing, so the point
 * would be at full range in the air. A listener may set a closer point; the Bridge only accepts one in the same world
 * within the spell range of the eyes. Used by the 큐디치 arena plugin for wheel-adjusted aiming in the air.
 */
public final class MagicAimPointEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player; private final String spellId; private final double range;
    private Location location;

    public MagicAimPointEvent(Player player, String spellId, double range, Location location) {
        this.player = player; this.spellId = spellId; this.range = range; this.location = location;
    }
    public Player getPlayer() { return player; }
    public String getSpellId() { return spellId; }
    public double getRange() { return range; }
    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
