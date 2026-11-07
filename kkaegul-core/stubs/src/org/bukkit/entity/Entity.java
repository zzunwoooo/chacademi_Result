package org.bukkit.entity;
import org.bukkit.*;
public interface Entity extends org.bukkit.command.CommandSender, org.bukkit.persistence.PersistentDataHolder { Location getLocation(); boolean teleport(Location l); World getWorld();
 java.util.UUID getUniqueId(); EntityType getType(); boolean isValid(); void remove(); boolean addPassenger(Entity p); boolean eject(); Entity getVehicle();
 void setVelocity(org.bukkit.util.Vector v); boolean addScoreboardTag(String t); java.util.Set<String> getScoreboardTags(); void setSilent(boolean b); void setRotation(float yaw, float pitch); void setInvulnerable(boolean b); void setPersistent(boolean b); void setGravity(boolean b); }
