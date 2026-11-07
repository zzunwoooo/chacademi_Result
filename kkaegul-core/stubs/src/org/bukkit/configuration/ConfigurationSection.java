package org.bukkit.configuration;
import java.util.*;
public interface ConfigurationSection { Set<String> getKeys(boolean deep); boolean contains(String p); String getName();
 String getString(String p); String getString(String p, String d); int getInt(String p); int getInt(String p, int d);
 java.util.List<java.util.Map<?, ?>> getMapList(String p); long getLong(String p); long getLong(String p, long d); double getDouble(String p); double getDouble(String p, double d);
 boolean getBoolean(String p); boolean getBoolean(String p, boolean d); List<String> getStringList(String p);
 ConfigurationSection getConfigurationSection(String p); boolean isConfigurationSection(String p); boolean isString(String p); void set(String p, Object v); }
