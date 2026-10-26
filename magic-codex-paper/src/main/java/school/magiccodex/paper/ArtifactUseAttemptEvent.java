package school.magiccodex.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before {@link ArtifactUseEvent} when the artifact use key is pressed (ownership, equipment and cooldown already
 * checked). Cancel it to refuse the use: no effect, no cooldown, {@link #getMessage()} goes to the action bar.
 * Used by the 큐디치 arena plugin for banned artifacts.
 */
public final class ArtifactUseAttemptEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String artifactId;
    private boolean cancelled;
    private String message;

    public ArtifactUseAttemptEvent(Player player, String artifactId) { this.player = player; this.artifactId = artifactId; }
    public Player getPlayer() { return player; }
    public String getArtifactId() { return artifactId; }
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
