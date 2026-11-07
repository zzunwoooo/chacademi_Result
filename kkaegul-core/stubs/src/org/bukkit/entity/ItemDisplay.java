package org.bukkit.entity;
public interface ItemDisplay extends Display { void setItemStack(org.bukkit.inventory.ItemStack i); void setItemDisplayTransform(ItemDisplayTransform t); enum ItemDisplayTransform { NONE, FIXED, HEAD, GUI, GROUND } }
