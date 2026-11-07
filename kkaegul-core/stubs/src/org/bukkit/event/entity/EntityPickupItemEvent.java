package org.bukkit.event.entity;
public class EntityPickupItemEvent extends EntityEvent implements org.bukkit.event.Cancellable { public org.bukkit.entity.LivingEntity getEntity(){return null;} public org.bukkit.entity.Item getItem(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
