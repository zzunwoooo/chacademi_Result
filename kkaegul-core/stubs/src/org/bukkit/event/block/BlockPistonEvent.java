package org.bukkit.event.block;
public abstract class BlockPistonEvent extends BlockEvent implements org.bukkit.event.Cancellable { public org.bukkit.block.BlockFace getDirection(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }
