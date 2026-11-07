package org.bukkit;
import java.util.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler;
public interface Server { java.io.File getWorldContainer(); boolean dispatchCommand(org.bukkit.command.CommandSender s, String c); Collection<? extends Player> getOnlinePlayers(); List<World> getWorlds(); BukkitScheduler getScheduler(); Player getPlayer(UUID id); World getWorld(String n); }
