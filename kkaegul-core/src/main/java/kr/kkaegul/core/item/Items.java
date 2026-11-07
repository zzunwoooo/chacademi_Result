package kr.kkaegul.core.item;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** 커스텀 아이템 생성/판별 */
public final class Items {
    private final KkaegulCore plugin;
    public final NamespacedKey KEY_ID;

    private final ItemsAdderHook ia;

    public Items(KkaegulCore plugin) {
        this.plugin = plugin;
        this.KEY_ID = new NamespacedKey(plugin, "item");
        this.ia = new ItemsAdderHook(plugin);
    }

    public ItemsAdderHook itemsAdder() { return ia; }

    public ItemStack create(CustomItem type, int amount) {
        ItemStack fromIa = ia.create(type.id, amount);   // ItemsAdder 아이템 (item:<id>) 우선
        if (fromIa != null) return fromIa;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("items." + type.id);
        Material mat = type.material;
        String name = type.defName;
        List<String> lore = new ArrayList<>(List.of(type.defLore));
        String model = "kkaegul:" + type.id;
        int cmd = 0;
        if (c != null) {
            Material m = Material.matchMaterial(c.getString("material", ""));
            if (m != null) mat = m;
            name = c.getString("name", name);
            if (c.contains("lore")) lore = c.getStringList("lore");
            model = c.getString("model", model);
            cmd = c.getInt("custom-model-data", 0);
        }
        ItemStack it = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Txt.color(name));
            meta.setLore(Txt.color(lore));
            if (cmd > 0) meta.setCustomModelData(cmd);
            boolean hasModel = c != null && c.contains("model")
                    || plugin.getConfig().getStringList("items.model-ids").contains(type.id);
            if (plugin.getConfig().getBoolean("items.use-item-model", false) && hasModel && model != null && !model.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(model);
                if (key != null) meta.setItemModel(key);
            }
            meta.getPersistentDataContainer().set(KEY_ID, PersistentDataType.STRING, type.id);
            it.setItemMeta(meta);
        }
        return it;
    }

    public CustomItem type(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        String iaId = ia.idOf(it);
        if (iaId != null) return CustomItem.byId(iaId);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
        return id == null ? null : CustomItem.byId(id);
    }

    public boolean is(ItemStack it, CustomItem type) { return type(it) == type; }

    public ItemStack mendingBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.addStoredEnchant(Enchantment.MENDING, 1, true);
            book.setItemMeta(esm);
        }
        return book;
    }
}
