package org.bukkit.event.player;
public class PlayerTeleportEvent extends PlayerMoveEvent { public enum TeleportCause { PLUGIN, COMMAND, UNKNOWN } public TeleportCause getCause(){return null;} }
