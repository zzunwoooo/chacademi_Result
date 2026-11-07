package org.bukkit.configuration;
import java.util.*;
public class MemorySection implements ConfigurationSection { public Set<String> getKeys(boolean deep){return null;} public boolean contains(String p){return false;} public String getName(){return null;}
 public String getString(String p){return null;} public String getString(String p, String d){return null;} public int getInt(String p){return 0;} public int getInt(String p, int d){return 0;}
 public long getLong(String p){return 0;} public long getLong(String p, long d){return 0;} public double getDouble(String p){return 0;} public double getDouble(String p, double d){return 0;}
 public boolean getBoolean(String p){return false;} public boolean getBoolean(String p, boolean d){return false;} public List<String> getStringList(String p){return null;}
 public ConfigurationSection getConfigurationSection(String p){return null;} public boolean isConfigurationSection(String p){return false;} public boolean isString(String p){return false;} public void set(String p, Object v){}  public java.util.List<java.util.Map<?, ?>> getMapList(String p){return null;}
}