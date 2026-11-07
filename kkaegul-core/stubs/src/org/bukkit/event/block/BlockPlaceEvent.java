package org.bukkit.event.block;
public class BlockPlaceEvent extends BlockEvent implements org.bukkit.event.Cancellable { public org.bukkit.block.BlockState getBlockReplacedState(){return null;} public org.bukkit.entity.Player getPlayer(){return null;} public org.bukkit.block.Block getBlockPlaced(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
