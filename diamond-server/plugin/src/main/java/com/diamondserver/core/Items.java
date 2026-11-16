package com.diamondserver.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 전용 아이템 4종: 밥(food) · 인벤세이브권(inven_save_ticket) · 귀환권(return_ticket) · 텔레포트권(teleport_ticket) */
final class Items {
    static final String FOOD = "food", INVEN_SAVE = "inven_save_ticket", RETURN = "return_ticket", TELEPORT = "teleport_ticket";
    static final Set<String> IDS = Set.of(FOOD, INVEN_SAVE, RETURN, TELEPORT);

    private final DiamondPlugin pl;
    private final NamespacedKey key;

    Items(DiamondPlugin pl) {
        this.pl = pl;
        this.key = new NamespacedKey(pl, "id");
    }

    /** 전용 아이템이면 id, 아니면 null */
    String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    String nameOf(String id) {
        return pl.getConfig().getString("items." + id + ".name", id);
    }

    Material materialOf(String id) {
        Material m = Material.matchMaterial(pl.getConfig().getString("items." + id + ".material", id.equals(FOOD) ? "BREAD" : "PAPER"));
        return m == null ? Material.PAPER : m;
    }

    ItemStack make(String id, int amount) {
        ConfigurationSection c = pl.getConfig().getConfigurationSection("items." + id);
        ItemStack it = new ItemStack(materialOf(id), Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(nameOf(id), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (c != null) for (String line : c.getStringList("lore"))
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) m.lore(lore);
        if (id.equals(FOOD) && c != null) {
            FoodComponent food = m.getFood();
            food.setNutrition(c.getInt("hunger", 8));
            food.setSaturation((float) c.getDouble("saturation", 12.8));
            food.setCanAlwaysEat(false);
            m.setFood(food);
        }
        String model = c == null ? null : c.getString("model");          // 그림이 준비되면 config 에 model: "diamondserver:<이름>"
        if (model != null && !model.isEmpty()) {
            NamespacedKey mk = NamespacedKey.fromString(model);
            if (mk != null) m.setItemModel(mk);
        }
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    /** 인벤토리에서 그 전용 아이템을 1개 쓴다. 없으면 false */
    boolean takeOne(Player p, String id) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            if (!id.equals(idOf(c[i]))) continue;
            if (c[i].getAmount() <= 1) p.getInventory().setItem(i, null);
            else c[i].setAmount(c[i].getAmount() - 1);
            return true;
        }
        return false;
    }

    /** 아이템을 준다. 인벤토리에 안 들어간 것은 발밑에 떨어뜨린다 */
    static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values())
            p.getWorld().dropItem(p.getLocation(), left);
    }
}
