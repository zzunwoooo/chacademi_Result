package org.bukkit.inventory;
import java.util.HashMap;
public interface Inventory extends Iterable<ItemStack> { int getSize(); ItemStack getItem(int i); void setItem(int i, ItemStack it); HashMap<Integer, ItemStack> addItem(ItemStack... items);
 HashMap<Integer, ItemStack> removeItem(ItemStack... items); ItemStack[] getContents(); void clear(); }
