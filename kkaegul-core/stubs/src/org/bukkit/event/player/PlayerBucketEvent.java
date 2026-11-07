package org.bukkit.event.player;
public abstract class PlayerBucketEvent extends PlayerEvent implements org.bukkit.event.Cancellable { public org.bukkit.block.Block getBlock(){return null;} public org.bukkit.block.Block getBlockClicked(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
