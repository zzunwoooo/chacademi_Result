package school.chacademia.magicmythic;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * C03 (owner decision 7, 2026-10-21): asked when MagicMythic must know whether two different players are on the same
 * side, only when someone listens. A match plugin (ChacaKyudichi: 큐디치·1:1 결투) answers with {@link #setAlly(boolean)}
 * while either player is in one of its matches: then the match team decides and the party is ignored.
 * Nobody answers = the Velocity party decides ({@link AllyResolver}). Main thread only.
 * Listeners find it by name (no compile dependency): getFirst() / getSecond() / setAlly(boolean).
 */
public final class MagicAllyEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player first, second;
    private Boolean ally;

    public MagicAllyEvent(Player first, Player second) { this.first = first; this.second = second; }
    /** The acting player (caster). */
    public Player getFirst() { return first; }
    /** The other player. */
    public Player getSecond() { return second; }
    /** true = same team, false = opponents (or one of them is outside the match). */
    public void setAlly(boolean ally) { this.ally = ally; }
    /** null = no match rule applies. */
    public Boolean getAlly() { return ally; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
    static boolean listened() { return HANDLERS.getRegisteredListeners().length > 0; }

    /** Match answer for the pair, or null when nobody listens / no match rule applies. */
    static Boolean ask(Player first, Player second) {
        if (!listened()) return null;
        var e = new MagicAllyEvent(first, second);
        org.bukkit.Bukkit.getPluginManager().callEvent(e);
        return e.getAlly();
    }
}
