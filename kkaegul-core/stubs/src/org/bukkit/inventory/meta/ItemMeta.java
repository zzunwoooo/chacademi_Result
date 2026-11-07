package org.bukkit.inventory.meta;
import org.bukkit.enchantments.Enchantment;
public interface ItemMeta extends org.bukkit.persistence.PersistentDataHolder, Cloneable { boolean hasDisplayName(); String getDisplayName(); void setDisplayName(String n); void setLore(java.util.List<String> l);
 void setCustomModelData(Integer d); void setItemModel(org.bukkit.NamespacedKey k); boolean hasEnchants(); int getEnchantLevel(Enchantment e);
 boolean addEnchant(Enchantment e, int level, boolean ignore); boolean removeEnchant(Enchantment e); }
