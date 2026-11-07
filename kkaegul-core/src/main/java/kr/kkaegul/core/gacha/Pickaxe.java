package kr.kkaegul.core.gacha;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Txt;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 곡괭이 가챠 수치(행운/효율/내구성, 최대 15). PDC 에 기록하고 같은 레벨의 실제 인챈트를 붙인다.
 * 다이아 원석 드랍은 이 행운 수치로 플러그인이 직접 계산한다 (CoreListener).
 */
public final class Pickaxe {
    public static final String[] STATS = {"fortune", "efficiency", "unbreaking"};
    private final KkaegulCore plugin;
    private final NamespacedKey kFortune, kEff, kUnb;

    public Pickaxe(KkaegulCore plugin) {
        this.plugin = plugin;
        kFortune = new NamespacedKey(plugin, "pick_fortune");
        kEff = new NamespacedKey(plugin, "pick_efficiency");
        kUnb = new NamespacedKey(plugin, "pick_unbreaking");
    }

    public static boolean isPickaxe(ItemStack it) {
        return it != null && it.getType().name().endsWith("_PICKAXE");
    }

    private NamespacedKey key(String stat) {
        return switch (stat) {
            case "fortune" -> kFortune;
            case "efficiency" -> kEff;
            default -> kUnb;
        };
    }

    public static String label(String stat) {
        return switch (stat) {
            case "fortune" -> "행운";
            case "efficiency" -> "효율";
            default -> "내구성";
        };
    }

    private Enchantment ench(String stat) {
        return switch (stat) {
            case "fortune" -> Enchantment.FORTUNE;
            case "efficiency" -> Enchantment.EFFICIENCY;
            default -> Enchantment.UNBREAKING;
        };
    }

    public int get(ItemStack it, String stat) {
        if (!isPickaxe(it) || !it.hasItemMeta()) return 0;
        ItemMeta m = it.getItemMeta();
        Integer v = m.getPersistentDataContainer().get(key(stat), PersistentDataType.INTEGER);
        if (v != null) return v;
        return m.getEnchantLevel(ench(stat)); // 바닐라 인챈트만 있는 곡괭이
    }

    public void set(ItemStack it, String stat, int value) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(key(stat), PersistentDataType.INTEGER, Math.max(0, value));
        int cap = plugin.getConfig().getInt("gacha.real-cap." + stat, 255);
        int real = Math.min(Math.max(0, value), Math.min(255, cap));
        Enchantment e = ench(stat);
        m.removeEnchant(e);
        if (real > 0) m.addEnchant(e, real, true);
        it.setItemMeta(m);
        refreshLore(it);
    }

    public void refreshLore(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        List<String> lore = new ArrayList<>();
        lore.add(Txt.color("&8깨굴 가챠 곡괭이"));
        for (String s : STATS) {
            Integer v = m.getPersistentDataContainer().get(key(s), PersistentDataType.INTEGER);
            lore.add(Txt.color("&7" + label(s) + " &f" + Txt.num(v == null ? m.getEnchantLevel(ench(s)) : v)));
        }
        m.setLore(lore);
        it.setItemMeta(m);
    }

    public int max(ItemStack it) {
        int best = 0;
        for (String s : STATS) best = Math.max(best, get(it, s));
        return best;
    }
}
