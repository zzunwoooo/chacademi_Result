package org.bukkit.plugin;
public interface PluginManager { void registerEvents(org.bukkit.event.Listener l, Plugin p); Plugin getPlugin(String n); void registerEvent(Class<? extends org.bukkit.event.Event> event, org.bukkit.event.Listener listener, org.bukkit.event.EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled); }
