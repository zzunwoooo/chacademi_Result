package org.bukkit.inventory;
import org.bukkit.Material;
public class ItemStack implements Cloneable { public ItemStack(Material m){} public ItemStack(Material m, int a){}
 public Material getType(){return null;} public int getAmount(){return 0;} public void setAmount(int a){} public boolean hasItemMeta(){return false;}
 public org.bukkit.inventory.meta.ItemMeta getItemMeta(){return null;} public boolean setItemMeta(org.bukkit.inventory.meta.ItemMeta m){return false;}
 public int getMaxStackSize(){return 0;} public ItemStack clone(){return null;} public int getEnchantmentLevel(org.bukkit.enchantments.Enchantment e){return 0;}
 public byte[] serializeAsBytes(){return null;} public static ItemStack deserializeBytes(byte[] b){return null;} }
