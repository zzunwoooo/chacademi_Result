package org.bukkit;
import java.util.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler; import org.bukkit.plugin.PluginManager; import org.bukkit.plugin.messaging.Messenger; import org.bukkit.command.*; import org.bukkit.inventory.*;
public final class Bukkit {
 public static Collection<? extends Player> getOnlinePlayers(){return null;}
 public static int broadcastMessage(String m){return 0;}
 public static Player getPlayer(String n){return null;}
 public static Player getPlayerExact(String n){return null;}
 public static Player getPlayer(UUID id){return null;}
 public static PluginManager getPluginManager(){return null;}
 public static BukkitScheduler getScheduler(){return null;}
 public static List<World> getWorlds(){return null;}
 public static World getWorld(String n){return null;}
 public static boolean dispatchCommand(CommandSender s, String c){return false;}
 public static OfflinePlayer getOfflinePlayer(UUID id){return null;}
 public static ConsoleCommandSender getConsoleSender(){return null;}
 public static Messenger getMessenger(){return null;}
 public static Inventory createInventory(InventoryHolder o, int size, String title){return null;}
 public static Server getServer(){return null;}
 public static org.bukkit.block.data.BlockData createBlockData(String d){return null;}
 public static World createWorld(WorldCreator c){return null;}
}
