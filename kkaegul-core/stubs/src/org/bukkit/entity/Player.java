package org.bukkit.entity;
import org.bukkit.*;
public interface Player extends HumanEntity, OfflinePlayer, org.bukkit.plugin.messaging.PluginMessageRecipient {
 String getName(); java.util.UUID getUniqueId(); void sendActionBar(String m); void sendTitle(String t, String s, int a, int b, int c);
 void playSound(Location l, String sound, float v, float p); void setPlayerListName(String n); boolean isOnline(); Input getCurrentInput(); boolean isSneaking();
 Spigot spigot(); void kickPlayer(String m); class Spigot extends org.bukkit.command.CommandSender.Spigot {} }
