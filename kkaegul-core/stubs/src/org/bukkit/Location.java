package org.bukkit;
public class Location implements Cloneable {
 public Location(World w,double x,double y,double z){} public Location(World w,double x,double y,double z,float yaw,float pitch){}
 public World getWorld(){return null;} public double getX(){return 0;} public double getY(){return 0;} public double getZ(){return 0;}
 public float getYaw(){return 0;} public float getPitch(){return 0;} public void setYaw(float f){} public void setPitch(float f){} public void setY(double y){}
 public int getBlockX(){return 0;} public int getBlockY(){return 0;} public int getBlockZ(){return 0;}
 public Location add(double x,double y,double z){return this;} public Location clone(){return this;}
 public double distanceSquared(Location o){return 0;} public org.bukkit.util.Vector getDirection(){return null;} public org.bukkit.block.Block getBlock(){return null;}
}
