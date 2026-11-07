package org.bukkit.block;
import org.bukkit.*;
public interface Block { Material getType(); void setType(Material m, boolean physics); void setType(Material m); Location getLocation(); World getWorld(); boolean isPassable();
 Block getRelative(BlockFace f); int getX(); int getY(); int getZ(); org.bukkit.block.data.BlockData getBlockData(); void setBlockData(org.bukkit.block.data.BlockData d, boolean physics); org.bukkit.Chunk getChunk(); }
