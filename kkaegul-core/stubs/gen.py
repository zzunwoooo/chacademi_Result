import os
S = {}
def d(path, body): S[path] = body

d("org/bukkit/Bukkit", """package org.bukkit;
import java.util.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler; import org.bukkit.plugin.PluginManager; import org.bukkit.plugin.messaging.Messenger; import org.bukkit.command.*; import org.bukkit.inventory.*;
public final class Bukkit {
 public static Collection<? extends Player> getOnlinePlayers(){return null;}
 public static int broadcastMessage(String m){return 0;}
 public static Player getPlayer(String n){return null;}
 public static Player getPlayerExact(String n){return null;}
 public static Player getPlayer(UUID id){return null;}
 public static PluginManager getPluginManager(){return null;}
 public static BukkitScheduler getScheduler(){return null;}
 public static List<World> getWorlds(){return null;}
 public static World getWorld(String n){return null;}
 public static boolean dispatchCommand(CommandSender s, String c){return false;}
 public static OfflinePlayer getOfflinePlayer(UUID id){return null;}
 public static ConsoleCommandSender getConsoleSender(){return null;}
 public static Messenger getMessenger(){return null;}
 public static Inventory createInventory(InventoryHolder o, int size, String title){return null;}
 public static Server getServer(){return null;}
 public static World createWorld(WorldCreator c){return null;}
}""")
d("org/bukkit/Server", """package org.bukkit;
import java.util.*; import org.bukkit.entity.Player; import org.bukkit.scheduler.BukkitScheduler;
public interface Server { java.io.File getWorldContainer(); boolean dispatchCommand(org.bukkit.command.CommandSender s, String c); Collection<? extends Player> getOnlinePlayers(); List<World> getWorlds(); BukkitScheduler getScheduler(); Player getPlayer(UUID id); World getWorld(String n); }""")
d("org/bukkit/Color", """package org.bukkit;
public final class Color { public static Color fromRGB(int rgb){return null;} public static Color fromARGB(int argb){return null;} }""")
d("org/bukkit/Input", """package org.bukkit;
public interface Input { boolean isForward(); boolean isBackward(); boolean isLeft(); boolean isRight(); boolean isJump(); boolean isSneak(); boolean isSprint(); }""")
d("org/bukkit/Location", """package org.bukkit;
public class Location implements Cloneable {
 public Location(World w,double x,double y,double z){} public Location(World w,double x,double y,double z,float yaw,float pitch){}
 public World getWorld(){return null;} public double getX(){return 0;} public double getY(){return 0;} public double getZ(){return 0;}
 public float getYaw(){return 0;} public float getPitch(){return 0;} public void setYaw(float f){} public void setPitch(float f){} public void setY(double y){}
 public int getBlockX(){return 0;} public int getBlockY(){return 0;} public int getBlockZ(){return 0;}
 public Location add(double x,double y,double z){return this;} public Location clone(){return this;}
 public double distanceSquared(Location o){return 0;} public org.bukkit.util.Vector getDirection(){return null;} public org.bukkit.block.Block getBlock(){return null;}
}""")
d("org/bukkit/NamespacedKey", """package org.bukkit;
public final class NamespacedKey { public NamespacedKey(org.bukkit.plugin.Plugin p, String k){} public static NamespacedKey fromString(String s){return null;} }""")
d("org/bukkit/OfflinePlayer", """package org.bukkit;
public interface OfflinePlayer { String getName(); java.util.UUID getUniqueId(); boolean hasPlayedBefore(); }""")
d("org/bukkit/World", """package org.bukkit;
import org.bukkit.entity.*; import org.bukkit.inventory.ItemStack;
public interface World { String getName(); org.bukkit.block.Block getBlockAt(int x,int y,int z); int getHighestBlockYAt(int x,int z);
 int getMaxHeight(); int getMinHeight(); boolean isChunkLoaded(int x, int z); Entity spawnEntity(Location l, EntityType t); Item dropItemNaturally(Location l, ItemStack i); java.util.List<Player> getPlayers(); Location getSpawnLocation(); java.util.List<Entity> getEntities(); }""")
d("org/bukkit/Material", """package org.bukkit;
public enum Material { AIR, DIAMOND, DIAMOND_BLOCK, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, PAPER, CHEST, ENDER_CHEST, TRAPPED_CHEST, HONEY_BOTTLE, COOKIE, ENCHANTED_BOOK, GLASS_BOTTLE, LEATHER_CHESTPLATE, WATER, LAVA, CACTUS, MAGMA_BLOCK;
 public static Material matchMaterial(String n){return null;} public boolean isBlock(){return false;} public int getMaxStackSize(){return 0;} }""")
d("org/bukkit/block/Block", """package org.bukkit.block;
import org.bukkit.*;
public interface Block { Material getType(); void setType(Material m, boolean physics); void setType(Material m); Location getLocation(); World getWorld(); boolean isPassable(); }""")
d("org/bukkit/command/Command", """package org.bukkit.command;
public abstract class Command { public String getName(){return null;} }""")
d("org/bukkit/command/CommandSender", """package org.bukkit.command;
public interface CommandSender extends org.bukkit.permissions.Permissible { void sendMessage(String m); String getName(); }""")
d("org/bukkit/permissions/Permissible", """package org.bukkit.permissions;
public interface Permissible { boolean hasPermission(String p); boolean isOp(); }""")
d("org/bukkit/command/ConsoleCommandSender", """package org.bukkit.command;
public interface ConsoleCommandSender extends CommandSender {}""")
d("org/bukkit/command/CommandExecutor", """package org.bukkit.command;
public interface CommandExecutor { boolean onCommand(CommandSender s, Command c, String l, String[] a); }""")
d("org/bukkit/command/TabCompleter", """package org.bukkit.command;
public interface TabCompleter { java.util.List<String> onTabComplete(CommandSender s, Command c, String l, String[] a); }""")
d("org/bukkit/command/PluginCommand", """package org.bukkit.command;
public final class PluginCommand extends Command { public void setExecutor(CommandExecutor e){} public void setTabCompleter(TabCompleter t){} }""")
d("org/bukkit/configuration/ConfigurationSection", """package org.bukkit.configuration;
import java.util.*;
public interface ConfigurationSection { Set<String> getKeys(boolean deep); boolean contains(String p); String getName();
 String getString(String p); String getString(String p, String d); int getInt(String p); int getInt(String p, int d);
 long getLong(String p); long getLong(String p, long d); double getDouble(String p); double getDouble(String p, double d);
 boolean getBoolean(String p); boolean getBoolean(String p, boolean d); List<String> getStringList(String p);
 ConfigurationSection getConfigurationSection(String p); boolean isConfigurationSection(String p); boolean isString(String p); void set(String p, Object v); }""")
d("org/bukkit/configuration/MemorySection", """package org.bukkit.configuration;
import java.util.*;
public class MemorySection implements ConfigurationSection { public Set<String> getKeys(boolean deep){return null;} public boolean contains(String p){return false;} public String getName(){return null;}
 public String getString(String p){return null;} public String getString(String p, String d){return null;} public int getInt(String p){return 0;} public int getInt(String p, int d){return 0;}
 public long getLong(String p){return 0;} public long getLong(String p, long d){return 0;} public double getDouble(String p){return 0;} public double getDouble(String p, double d){return 0;}
 public boolean getBoolean(String p){return false;} public boolean getBoolean(String p, boolean d){return false;} public List<String> getStringList(String p){return null;}
 public ConfigurationSection getConfigurationSection(String p){return null;} public boolean isConfigurationSection(String p){return false;} public boolean isString(String p){return false;} public void set(String p, Object v){} }""")
d("org/bukkit/configuration/MemoryConfiguration", """package org.bukkit.configuration;
public class MemoryConfiguration extends MemorySection implements Configuration {}""")
d("org/bukkit/configuration/Configuration", """package org.bukkit.configuration;
public interface Configuration extends ConfigurationSection {}""")
d("org/bukkit/configuration/file/FileConfiguration", """package org.bukkit.configuration.file;
public abstract class FileConfiguration extends org.bukkit.configuration.MemoryConfiguration { public void save(java.io.File f) throws java.io.IOException {} }""")
d("org/bukkit/configuration/file/YamlConfiguration", """package org.bukkit.configuration.file;
public class YamlConfiguration extends FileConfiguration { public YamlConfiguration(){} public static YamlConfiguration loadConfiguration(java.io.File f){return null;} }""")
d("org/bukkit/enchantments/Enchantment", """package org.bukkit.enchantments;
public abstract class Enchantment { public static final Enchantment FORTUNE = null, EFFICIENCY = null, UNBREAKING = null, MENDING = null, SILK_TOUCH = null; }""")
d("org/bukkit/entity/Entity", """package org.bukkit.entity;
import org.bukkit.*;
public interface Entity extends org.bukkit.command.CommandSender, org.bukkit.persistence.PersistentDataHolder { Location getLocation(); boolean teleport(Location l); World getWorld();
 java.util.UUID getUniqueId(); EntityType getType(); boolean isValid(); void remove(); boolean addPassenger(Entity p); boolean eject(); Entity getVehicle();
 void setVelocity(org.bukkit.util.Vector v); boolean addScoreboardTag(String t); java.util.Set<String> getScoreboardTags(); void setSilent(boolean b); void setRotation(float yaw, float pitch); void setInvulnerable(boolean b); void setPersistent(boolean b); void setGravity(boolean b); }""")
d("org/bukkit/entity/Damageable", """package org.bukkit.entity;
public interface Damageable extends Entity { void setHealth(double h); double getHealth(); }""")
d("org/bukkit/entity/LivingEntity", """package org.bukkit.entity;
import org.bukkit.potion.*;
public interface LivingEntity extends Damageable { boolean addPotionEffect(PotionEffect e); void removePotionEffect(PotionEffectType t); boolean hasPotionEffect(PotionEffectType t); void setInvisible(boolean b); void setAI(boolean b); Entity getTargetEntity(int max); }""")
d("org/bukkit/entity/HumanEntity", """package org.bukkit.entity;
public interface HumanEntity extends LivingEntity { org.bukkit.inventory.PlayerInventory getInventory(); org.bukkit.inventory.InventoryView openInventory(org.bukkit.inventory.Inventory i); }""")
d("org/bukkit/entity/Player", """package org.bukkit.entity;
import org.bukkit.*;
public interface Player extends HumanEntity, OfflinePlayer, org.bukkit.plugin.messaging.PluginMessageRecipient {
 String getName(); java.util.UUID getUniqueId(); void sendActionBar(String m); void sendTitle(String t, String s, int a, int b, int c);
 void playSound(Location l, String sound, float v, float p); void setPlayerListName(String n); boolean isOnline(); Input getCurrentInput(); boolean isSneaking(); }""")
d("org/bukkit/entity/ArmorStand", """package org.bukkit.entity;
public interface ArmorStand extends LivingEntity { void setMarker(boolean b); }""")
d("org/bukkit/entity/Item", """package org.bukkit.entity;
public interface Item extends Entity { org.bukkit.inventory.ItemStack getItemStack(); }""")
d("org/bukkit/entity/Display", """package org.bukkit.entity;
public interface Display extends Entity { void setBillboard(Billboard b); enum Billboard { FIXED, VERTICAL, HORIZONTAL, CENTER } void setTransformation(org.bukkit.util.Transformation t); org.bukkit.util.Transformation getTransformation(); void setInterpolationDuration(int t); void setInterpolationDelay(int t); void setTeleportDuration(int t); void setViewRange(float r); void setShadowRadius(float r); }""")
d("org/bukkit/entity/ItemDisplay", """package org.bukkit.entity;
public interface ItemDisplay extends Display { void setItemStack(org.bukkit.inventory.ItemStack i); void setItemDisplayTransform(ItemDisplayTransform t); enum ItemDisplayTransform { NONE, FIXED, HEAD, GUI, GROUND } }""")
d("org/bukkit/util/Transformation", """package org.bukkit.util;
public class Transformation { public Transformation(org.joml.Vector3f t, org.joml.Quaternionf l, org.joml.Vector3f s, org.joml.Quaternionf r){} }""")
d("org/joml/Vector3f", """package org.joml;
public class Vector3f { public float x,y,z; public Vector3f(){} public Vector3f(float x,float y,float z){} public Vector3f sub(Vector3f o){return this;} public Vector3f rotate(Quaternionf q){return this;} }""")
d("org/joml/Quaternionf", """package org.joml;
public class Quaternionf { public Quaternionf(){} public Quaternionf rotateX(float a){return this;} public Quaternionf rotateY(float a){return this;} public Vector3f transform(Vector3f v){return v;} }""")
d("org/bukkit/WorldCreator", """package org.bukkit;
public class WorldCreator { public WorldCreator(String n){} }""")
d("org/bukkit/entity/TextDisplay", """package org.bukkit.entity;
public interface TextDisplay extends Display { void setText(String t); void setBackgroundColor(org.bukkit.Color c); }""")
d("org/bukkit/entity/EntityType", """package org.bukkit.entity;
public enum EntityType { ARMOR_STAND, TEXT_DISPLAY, ITEM_DISPLAY, ZOMBIE, FROG; }""")
d("org/bukkit/event/Event", """package org.bukkit.event;
public abstract class Event { }""")
d("org/bukkit/event/Cancellable", """package org.bukkit.event;
public interface Cancellable { boolean isCancelled(); void setCancelled(boolean c); }""")
d("org/bukkit/event/Listener", """package org.bukkit.event;
public interface Listener {}""")
d("org/bukkit/event/EventPriority", """package org.bukkit.event;
public enum EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }""")
d("org/bukkit/event/EventHandler", """package org.bukkit.event;
import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler { EventPriority priority() default EventPriority.NORMAL; boolean ignoreCancelled() default false; }""")
d("org/bukkit/event/block/Action", """package org.bukkit.event.block;
public enum Action { LEFT_CLICK_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_AIR, RIGHT_CLICK_AIR, PHYSICAL }""")
d("org/bukkit/event/block/BlockEvent", """package org.bukkit.event.block;
public abstract class BlockEvent extends org.bukkit.event.Event { public final org.bukkit.block.Block getBlock(){return null;} }""")
d("org/bukkit/event/block/BlockExpEvent", """package org.bukkit.event.block;
public class BlockExpEvent extends BlockEvent {}""")
d("org/bukkit/event/block/BlockBreakEvent", """package org.bukkit.event.block;
public class BlockBreakEvent extends BlockExpEvent implements org.bukkit.event.Cancellable { public org.bukkit.entity.Player getPlayer(){return null;} public void setDropItems(boolean b){}
 public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }""")
d("org/bukkit/event/player/PlayerEvent", """package org.bukkit.event.player;
public abstract class PlayerEvent extends org.bukkit.event.Event { public final org.bukkit.entity.Player getPlayer(){return null;} }""")
for n, extra, canc in [
 ("PlayerJoinEvent","",False),("PlayerQuitEvent","",False),
 ("PlayerToggleSneakEvent","public boolean isSneaking(){return false;}",True),
 ("PlayerInputEvent","public org.bukkit.Input getInput(){return null;}",False),
 ("PlayerAnimationEvent","",True),
 ("PlayerRespawnEvent","public void setRespawnLocation(org.bukkit.Location l){}",False),
 ("PlayerItemConsumeEvent","public org.bukkit.inventory.ItemStack getItem(){return null;}",True),
 ("PlayerInteractEvent","public org.bukkit.event.block.Action getAction(){return null;} public org.bukkit.inventory.ItemStack getItem(){return null;} public org.bukkit.inventory.EquipmentSlot getHand(){return null;} public org.bukkit.block.Block getClickedBlock(){return null;}",True),
 ("PlayerInteractEntityEvent","public org.bukkit.entity.Entity getRightClicked(){return null;} public org.bukkit.inventory.EquipmentSlot getHand(){return null;}",True),
 ("PlayerMoveEvent","public org.bukkit.Location getFrom(){return null;} public org.bukkit.Location getTo(){return null;} public void setTo(org.bukkit.Location l){}",True),
 ("AsyncPlayerChatEvent","public String getMessage(){return null;} public void setFormat(String f){}",True),
]:
  impl = " implements org.bukkit.event.Cancellable" if canc else ""
  cm = "public boolean isCancelled(){return false;} public void setCancelled(boolean c){}" if canc else ""
  d("org/bukkit/event/player/"+n, "package org.bukkit.event.player;\npublic class %s extends PlayerEvent%s { %s %s }" % (n, impl, extra, cm))
d("org/bukkit/event/entity/EntityEvent", """package org.bukkit.event.entity;
public abstract class EntityEvent extends org.bukkit.event.Event { public org.bukkit.entity.Entity getEntity(){return null;} }""")
d("org/bukkit/event/entity/EntityDamageEvent", """package org.bukkit.event.entity;
public class EntityDamageEvent extends EntityEvent implements org.bukkit.event.Cancellable { public DamageCause getCause(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){}
 public enum DamageCause { FALL } }""")
d("org/bukkit/event/entity/EntityPickupItemEvent", """package org.bukkit.event.entity;
public class EntityPickupItemEvent extends EntityEvent implements org.bukkit.event.Cancellable { public org.bukkit.entity.LivingEntity getEntity(){return null;} public org.bukkit.entity.Item getItem(){return null;} public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }""")
d("org/bukkit/event/entity/EntityDismountEvent", """package org.bukkit.event.entity;
public class EntityDismountEvent extends EntityEvent implements org.bukkit.event.Cancellable { public boolean isCancelled(){return false;} public void setCancelled(boolean c){} }""")
d("org/bukkit/event/entity/PlayerDeathEvent", """package org.bukkit.event.entity;
public class PlayerDeathEvent extends EntityDeathEvent { public org.bukkit.entity.Player getEntity(){return null;} public void setKeepInventory(boolean b){} public void setKeepLevel(boolean b){} }""")
d("org/bukkit/event/entity/EntityDeathEvent", """package org.bukkit.event.entity;
public class EntityDeathEvent extends EntityEvent { public java.util.List<org.bukkit.inventory.ItemStack> getDrops(){return null;} public void setDroppedExp(int e){} }""")
d("org/bukkit/inventory/EquipmentSlot", """package org.bukkit.inventory;
public enum EquipmentSlot { HAND, OFF_HAND, FEET, LEGS, CHEST, HEAD }""")
d("org/bukkit/inventory/InventoryHolder", """package org.bukkit.inventory;
public interface InventoryHolder {}""")
d("org/bukkit/inventory/InventoryView", """package org.bukkit.inventory;
public interface InventoryView {}""")
d("org/bukkit/inventory/Inventory", """package org.bukkit.inventory;
import java.util.HashMap;
public interface Inventory extends Iterable<ItemStack> { int getSize(); ItemStack getItem(int i); void setItem(int i, ItemStack it); HashMap<Integer, ItemStack> addItem(ItemStack... items);
 HashMap<Integer, ItemStack> removeItem(ItemStack... items); ItemStack[] getContents(); }""")
d("org/bukkit/inventory/PlayerInventory", """package org.bukkit.inventory;
public interface PlayerInventory extends Inventory { ItemStack getItemInMainHand(); void setItemInMainHand(ItemStack i); }""")
d("org/bukkit/inventory/ItemStack", """package org.bukkit.inventory;
import org.bukkit.Material;
public class ItemStack implements Cloneable { public ItemStack(Material m){} public ItemStack(Material m, int a){}
 public Material getType(){return null;} public int getAmount(){return 0;} public void setAmount(int a){} public boolean hasItemMeta(){return false;}
 public org.bukkit.inventory.meta.ItemMeta getItemMeta(){return null;} public boolean setItemMeta(org.bukkit.inventory.meta.ItemMeta m){return false;}
 public int getMaxStackSize(){return 0;} public ItemStack clone(){return null;} public int getEnchantmentLevel(org.bukkit.enchantments.Enchantment e){return 0;}
 public byte[] serializeAsBytes(){return null;} public static ItemStack deserializeBytes(byte[] b){return null;} }""")
d("org/bukkit/inventory/meta/ItemMeta", """package org.bukkit.inventory.meta;
import org.bukkit.enchantments.Enchantment;
public interface ItemMeta extends org.bukkit.persistence.PersistentDataHolder, Cloneable { boolean hasDisplayName(); String getDisplayName(); void setDisplayName(String n); void setLore(java.util.List<String> l);
 void setCustomModelData(Integer d); void setItemModel(org.bukkit.NamespacedKey k); boolean hasEnchants(); int getEnchantLevel(Enchantment e);
 boolean addEnchant(Enchantment e, int level, boolean ignore); boolean removeEnchant(Enchantment e); }""")
d("org/bukkit/inventory/meta/EnchantmentStorageMeta", """package org.bukkit.inventory.meta;
public interface EnchantmentStorageMeta extends ItemMeta { boolean addStoredEnchant(org.bukkit.enchantments.Enchantment e, int level, boolean ignore); }""")
d("org/bukkit/inventory/meta/LeatherArmorMeta", """package org.bukkit.inventory.meta;
public interface LeatherArmorMeta extends ItemMeta { void setColor(org.bukkit.Color c); }""")
d("org/bukkit/persistence/PersistentDataHolder", """package org.bukkit.persistence;
public interface PersistentDataHolder { PersistentDataContainer getPersistentDataContainer(); }""")
d("org/bukkit/persistence/PersistentDataViewHolder", """package org.bukkit.persistence;
public interface PersistentDataViewHolder {}""")
d("org/bukkit/persistence/PersistentDataContainer", """package org.bukkit.persistence;
import org.bukkit.NamespacedKey;
public interface PersistentDataContainer { <P, C> void set(NamespacedKey k, PersistentDataType<P, C> t, C v); <P, C> C get(NamespacedKey k, PersistentDataType<P, C> t); void remove(NamespacedKey k); }""")
d("org/bukkit/persistence/PersistentDataType", """package org.bukkit.persistence;
public interface PersistentDataType<P, C> { PersistentDataType<String, String> STRING = null; PersistentDataType<Integer, Integer> INTEGER = null; PersistentDataType<Long, Long> LONG = null; }""")
d("org/bukkit/plugin/Plugin", """package org.bukkit.plugin;
public interface Plugin { java.io.File getDataFolder(); org.bukkit.configuration.file.FileConfiguration getConfig(); java.util.logging.Logger getLogger(); org.bukkit.Server getServer(); void saveConfig(); void saveDefaultConfig(); void reloadConfig(); void saveResource(String p, boolean r); }""")
d("org/bukkit/plugin/PluginBase", """package org.bukkit.plugin;
public abstract class PluginBase implements Plugin {}""")
d("org/bukkit/plugin/java/JavaPlugin", """package org.bukkit.plugin.java;
public abstract class JavaPlugin extends org.bukkit.plugin.PluginBase { public java.io.File getDataFolder(){return null;} public org.bukkit.configuration.file.FileConfiguration getConfig(){return null;}
 public java.util.logging.Logger getLogger(){return null;} public org.bukkit.Server getServer(){return null;} public void saveConfig(){} public void saveDefaultConfig(){} public void reloadConfig(){}
 public void saveResource(String p, boolean r){} public org.bukkit.command.PluginCommand getCommand(String n){return null;} public final boolean isEnabled(){return false;} public void onEnable(){} public void onDisable(){} }""")
d("org/bukkit/plugin/PluginManager", """package org.bukkit.plugin;
public interface PluginManager { void registerEvents(org.bukkit.event.Listener l, Plugin p); Plugin getPlugin(String n); void registerEvent(Class<? extends org.bukkit.event.Event> event, org.bukkit.event.Listener listener, org.bukkit.event.EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled); }""")
d("org/bukkit/plugin/EventExecutor", """package org.bukkit.plugin;
public interface EventExecutor { void execute(org.bukkit.event.Listener listener, org.bukkit.event.Event event) throws org.bukkit.event.EventException; }""")
d("org/bukkit/event/EventException", """package org.bukkit.event;
public class EventException extends Exception { }""")
d("org/bukkit/plugin/messaging/Messenger", """package org.bukkit.plugin.messaging;
public interface Messenger { void registerOutgoingPluginChannel(org.bukkit.plugin.Plugin p, String c); PluginMessageListenerRegistration registerIncomingPluginChannel(org.bukkit.plugin.Plugin p, String c, PluginMessageListener l); }""")
d("org/bukkit/plugin/messaging/PluginMessageListenerRegistration", """package org.bukkit.plugin.messaging;
public final class PluginMessageListenerRegistration {}""")
d("org/bukkit/plugin/messaging/PluginMessageListener", """package org.bukkit.plugin.messaging;
public interface PluginMessageListener { void onPluginMessageReceived(String c, org.bukkit.entity.Player p, byte[] m); }""")
d("org/bukkit/plugin/messaging/PluginMessageRecipient", """package org.bukkit.plugin.messaging;
public interface PluginMessageRecipient { void sendPluginMessage(org.bukkit.plugin.Plugin s, String c, byte[] m); java.util.Set<String> getListeningPluginChannels(); }""")
d("org/bukkit/potion/PotionEffect", """package org.bukkit.potion;
public class PotionEffect { public PotionEffect(PotionEffectType t, int d, int a){} public PotionEffect(PotionEffectType t, int d, int a, boolean amb, boolean part, boolean icon){} }""")
d("org/bukkit/potion/PotionEffectType", """package org.bukkit.potion;
public abstract class PotionEffectType { public static final PotionEffectType HASTE = null, MINING_FATIGUE = null; }""")
d("org/bukkit/scheduler/BukkitTask", """package org.bukkit.scheduler;
public interface BukkitTask { void cancel(); }""")
d("org/bukkit/scheduler/BukkitScheduler", """package org.bukkit.scheduler;
import org.bukkit.plugin.Plugin;
public interface BukkitScheduler { BukkitTask runTask(Plugin p, Runnable r); BukkitTask runTaskLater(Plugin p, Runnable r, long d); BukkitTask runTaskTimer(Plugin p, Runnable r, long d, long per); BukkitTask runTaskAsynchronously(Plugin p, Runnable r); }""")
d("org/bukkit/util/Vector", """package org.bukkit.util;
public class Vector { public Vector(double x, double y, double z){} public Vector normalize(){return this;} public double getX(){return 0;} public double getZ(){return 0;} public Vector multiply(double d){return this;} public Vector setY(double y){return this;} }""")

for p, b in S.items():
  f = os.path.join("src", p + ".java"); os.makedirs(os.path.dirname(f), exist_ok=True); open(f, "w").write(b + "\n")
print(len(S))
