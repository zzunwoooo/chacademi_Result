package org.bukkit;
import org.bukkit.entity.*; import org.bukkit.inventory.ItemStack;
public interface World { void playSound(Location l, String sound, float v, float p); String getName(); org.bukkit.block.Block getBlockAt(int x,int y,int z); int getHighestBlockYAt(int x,int z); org.bukkit.block.Block getHighestBlockAt(int x,int z); Chunk getChunkAt(int x,int z); boolean isChunkLoaded(Chunk c);
 int getMaxHeight(); int getMinHeight(); boolean isChunkLoaded(int x, int z); Entity spawnEntity(Location l, EntityType t); Item dropItemNaturally(Location l, ItemStack i); java.util.List<Player> getPlayers(); Location getSpawnLocation(); java.util.List<Entity> getEntities(); }
