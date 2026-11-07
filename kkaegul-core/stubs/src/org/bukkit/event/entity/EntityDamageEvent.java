package org.bukkit.event.entity;
public class EntityDamageEvent extends EntityEvent implements org.bukkit.event.Cancellable { public DamageCause getCause(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){}
 public enum DamageCause { FALL } }
