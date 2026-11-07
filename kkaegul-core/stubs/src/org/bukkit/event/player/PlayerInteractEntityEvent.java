package org.bukkit.event.player;
public class PlayerInteractEntityEvent extends PlayerEvent implements org.bukkit.event.Cancellable { public org.bukkit.entity.Entity getRightClicked(){return null;} public org.bukkit.inventory.EquipmentSlot getHand(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
