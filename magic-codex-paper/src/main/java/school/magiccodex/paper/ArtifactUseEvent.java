package school.magiccodex.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player pressed the artifact use key (default X) with a usable artifact equipped, and the server already checked
 * ownership, equipment and the cooldown. Built-in abilities: {@link ArtifactActions}. A plugin that implements an artifact
 * listens for its id, does the effect and calls {@link #setUsed(boolean) setUsed(true)} - only then is the cooldown
 * (artifacts.yml `cooldown`) started. {@link #setResponse} replaces the action-bar notice.
 */
public final class ArtifactUseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String artifactId;
    private final int cooldownSeconds;
    private boolean used;
    private String response;

    public ArtifactUseEvent(Player player, String artifactId, int cooldownSeconds) {
        this.player = player; this.artifactId = artifactId; this.cooldownSeconds = cooldownSeconds;
    }
    public Player getPlayer() { return player; }
    public String getArtifactId() { return artifactId; }
    /** Configured cooldown in seconds (0 = none). */
    public int getCooldownSeconds() { return cooldownSeconds; }
    public boolean isUsed() { return used; }
    /** true = the effect happened, start the cooldown. Leave false when the use failed (no target etc.). */
    public void setUsed(boolean used) { this.used = used; }
    public String getResponse() { return response; }
    public void setResponse(String text) {
        if (text != null && text.length() > 120) throw new IllegalArgumentException("Maximum 120 characters");
        response = text;
    }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
