package org.bukkit.command;
public interface CommandSender extends org.bukkit.permissions.Permissible { void sendMessage(String m); String getName();
 class Spigot { public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... c){} } }
