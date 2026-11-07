package org.bukkit.event.player;
public class PlayerItemDamageEvent extends PlayerEvent implements org.bukkit.event.Cancellable { public org.bukkit.inventory.ItemStack getItem(){return null;} public int getDamage(){return 0;} public void setDamage(int d){} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
