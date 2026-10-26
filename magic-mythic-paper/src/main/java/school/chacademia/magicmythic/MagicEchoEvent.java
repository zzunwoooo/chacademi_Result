package school.chacademia.magicmythic;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 169 잔향 읽기: the player read the memory of the aimed object (a block or an entity). Puzzle / story plugins decide
 * which objects hold a memory and show the clue; a listener that has one calls {@link #setClue(String)} (the text is
 * shown on the action bar) or {@link #setHandled(boolean)} when it shows the clue itself. Nobody handled it = "no echo".
 */
public final class MagicEchoEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player caster; private final Block block; private final Entity entity; private final Location location;
    private boolean handled; private String clue;
    public MagicEchoEvent(Player caster, Block block, Entity entity, Location location) {
        this.caster = caster; this.block = block; this.entity = entity; this.location = location.clone();
    }
    public Player getCaster() { return caster; }
    /** The aimed block, or null when an entity was aimed. */
    public Block getBlock() { return block; }
    /** The aimed entity (item frame, armor stand, dropped item, mob ...), or null. */
    public Entity getEntity() { return entity; }
    public Location getLocation() { return location.clone(); }
    public boolean isHandled() { return handled || clue != null; }
    public void setHandled(boolean handled) { this.handled = handled; }
    public String getClue() { return clue; }
    public void setClue(String clue) { this.clue = clue; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
