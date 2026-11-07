package org.bukkit.plugin;
public interface Plugin { java.io.File getDataFolder(); org.bukkit.configuration.file.FileConfiguration getConfig(); java.util.logging.Logger getLogger(); org.bukkit.Server getServer(); void saveConfig(); void saveDefaultConfig(); void reloadConfig(); void saveResource(String p, boolean r); }
