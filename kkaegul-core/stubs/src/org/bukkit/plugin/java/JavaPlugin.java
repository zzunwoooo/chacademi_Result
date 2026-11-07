package org.bukkit.plugin.java;
public abstract class JavaPlugin extends org.bukkit.plugin.PluginBase { public java.io.File getDataFolder(){return null;} public org.bukkit.configuration.file.FileConfiguration getConfig(){return null;}
 public java.util.logging.Logger getLogger(){return null;} public org.bukkit.Server getServer(){return null;} public void saveConfig(){} public void saveDefaultConfig(){} public void reloadConfig(){}
 public void saveResource(String p, boolean r){} public org.bukkit.command.PluginCommand getCommand(String n){return null;} public final boolean isEnabled(){return false;} public void onEnable(){} public void onDisable(){} }
