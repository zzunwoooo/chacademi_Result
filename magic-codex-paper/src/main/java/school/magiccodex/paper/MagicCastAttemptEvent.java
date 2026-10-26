package school.magiccodex.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the server thread before a spell cast is checked and charged (all routes: client key, /마법, chat command,
 * {@link MagicCodexBridge#castMagic}). Cancel it to refuse the cast: no mana is spent, no cooldown starts, the client
 * gets UNAVAILABLE and {@link #getMessage()} (if set) is shown on the action bar. Used by the 큐디치 arena plugin
 * (no casting while holding the quaffle, banned spells).
 */
public final class MagicCastAttemptEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String spellId;
    private boolean cancelled;
    private String message;

    public MagicCastAttemptEvent(Player player, String spellId) { this.player = player; this.spellId = spellId; }
    public Player getPlayer() { return player; }
    public String getSpellId() { return spellId; }
    public String getMessage() { return message; }
    public void setMessage(String message) {
        if (message != null && message.length() > 120) throw new IllegalArgumentException("Maximum 120 characters");
        this.message = message;
    }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
