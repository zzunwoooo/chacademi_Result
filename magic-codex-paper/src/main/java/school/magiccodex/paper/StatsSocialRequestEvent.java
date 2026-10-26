package school.magiccodex.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Validated UI request only. A future social plugin can handle it and supply feedback. */
public final class StatsSocialRequestEvent extends Event {
    public enum Action { FRIEND, POPULARITY }
    private static final HandlerList HANDLERS=new HandlerList();
    private final Player viewer,target;
    private final Action action;
    private String response;
    public StatsSocialRequestEvent(Player viewer,Player target,Action action){this.viewer=viewer;this.target=target;this.action=action;}
    public Player getViewer(){return viewer;}
    public Player getTarget(){return target;}
    public Action getAction(){return action;}
    public String getResponse(){return response;}
    public void setResponse(String text){if(text==null||text.length()>160)throw new IllegalArgumentException("Maximum 160 characters");response=text;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
