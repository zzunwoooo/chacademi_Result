package com.diamondserver.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 상점: 보유 다이아로 구매, 광물을 팔면 다이아로 받음 (소수점은 쌓였다가 1 이 넘으면 다이아 1개) */
final class Shop {
    /** 구매 상품 한 칸 */
    record Entry(String id, String cat, String name, String desc, long price, int amount,
                 Material material, Enchantment ench, int level, String item) { }

    /** 판매 품목 한 줄 */
    record Sell(Material material, Material block, String name, double price) { }

    private final DiamondPlugin pl;
    private final Map<String, String> cats = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<Material, Sell> sells = new LinkedHashMap<>();
    private final Map<Material, Sell> sellBlocks = new LinkedHashMap<>();
    private JsonObject catalog = new JsonObject();

    Shop(DiamondPlugin pl) { this.pl = pl; }

    private static Enchantment ench(String name) {
        return switch (name == null ? "" : name.toLowerCase(Locale.ROOT)) {
            case "efficiency" -> Enchantment.EFFICIENCY;
            case "fortune" -> Enchantment.FORTUNE;
            case "unbreaking" -> Enchantment.UNBREAKING;
            case "mending" -> Enchantment.MENDING;
            case "silk_touch" -> Enchantment.SILK_TOUCH;
            default -> null;
        };
    }

    private static String mcId(Material m) { return m.getKey().toString(); }

    void load() {
        cats.clear(); entries.clear(); sells.clear(); sellBlocks.clear();
        ConfigurationSection cs = pl.getConfig().getConfigurationSection("shop.categories");
        if (cs != null) for (String k : cs.getKeys(false)) cats.put(k, cs.getString(k, k));

        for (Map<?, ?> raw : pl.getConfig().getMapList("shop.items")) {
            String id = String.valueOf(raw.get("id"));
            String cat = String.valueOf(raw.get("cat"));
            long price = raw.get("price") instanceof Number n ? n.longValue() : -1;
            int amount = raw.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            String name = raw.get("name") == null ? id : String.valueOf(raw.get("name"));
            String desc = raw.get("desc") == null ? "" : String.valueOf(raw.get("desc"));
            Material mat = raw.get("material") == null ? null : Material.matchMaterial(String.valueOf(raw.get("material")));
            Enchantment en = ench(raw.get("enchant") == null ? null : String.valueOf(raw.get("enchant")));
            int level = raw.get("level") instanceof Number n ? n.intValue() : 1;
            String item = raw.get("item") == null ? null : String.valueOf(raw.get("item"));
            boolean ok = price >= 0 && cats.containsKey(cat) && (mat != null || en != null || (item != null && Items.IDS.contains(item)));
            if (!ok) { pl.getLogger().warning("상점 상품 설정 오류로 건너뜀: " + raw); continue; }
            entries.put(id, new Entry(id, cat, name, desc, price, amount, mat, en, level, item));
        }

        ConfigurationSection ss = pl.getConfig().getConfigurationSection("sell");
        if (ss != null) for (String k : ss.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            double price = ss.getDouble(k + ".price", -1);
            if (m == null || price <= 0) { pl.getLogger().warning("판매 품목 설정 오류로 건너뜀: " + k); continue; }
            String b = ss.getString(k + ".block");
            Material block = b == null ? null : Material.matchMaterial(b);
            Sell s = new Sell(m, block, ss.getString(k + ".name", k), price);
            sells.put(m, s);
            if (block != null) sellBlocks.put(block, s);
        }
        catalog = buildCatalog();
        pl.getLogger().info("상점: 상품 " + entries.size() + "개, 판매 품목 " + sells.size() + "개");
    }

    private Material iconOf(Entry e) {
        if (e.item != null) return pl.items.materialOf(e.item);
        if (e.ench != null) return Material.ENCHANTED_BOOK;
        return e.material;
    }

    private JsonObject buildCatalog() {
        JsonObject o = new JsonObject();
        o.addProperty("t", "shop");
        JsonArray ca = new JsonArray();
        cats.forEach((id, label) -> { JsonObject c = new JsonObject(); c.addProperty("id", id); c.addProperty("label", label); ca.add(c); });
        o.add("cats", ca);
        JsonArray ia = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject i = new JsonObject();
            i.addProperty("id", e.id); i.addProperty("cat", e.cat); i.addProperty("name", e.name); i.addProperty("desc", e.desc);
            i.addProperty("price", e.price); i.addProperty("amount", e.amount); i.addProperty("icon", mcId(iconOf(e)));
            ia.add(i);
        }
        o.add("items", ia);
        JsonArray sa = new JsonArray();
        for (Sell s : sells.values()) {
            JsonObject i = new JsonObject();
            i.addProperty("mat", mcId(s.material)); i.addProperty("name", s.name); i.addProperty("price", s.price);
            if (s.block != null) i.addProperty("block", mcId(s.block));
            sa.add(i);
        }
        o.add("sell", sa);
        return o;
    }

    JsonObject catalog() { return catalog; }

    private ItemStack make(Entry e, int qty) {
        if (e.item != null) return pl.items.make(e.item, e.amount * qty);
        if (e.ench != null) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, qty);
            EnchantmentStorageMeta m = (EnchantmentStorageMeta) book.getItemMeta();
            m.addStoredEnchant(e.ench, e.level, true);
            book.setItemMeta(m);
            return book;
        }
        return new ItemStack(e.material, e.amount * qty);
    }

    void buy(Player p, String id, int qty) {
        Entry e = entries.get(id);
        if (e == null) { pl.net.toast(p, "판매하지 않는 상품입니다.", false); return; }
        qty = Math.max(1, Math.min(64, qty));
        long total = e.price * qty;
        if (!pl.ledger.takeHeld(p, total)) {
            pl.net.toast(p, "보유 다이아가 부족합니다. (필요 " + total + "개)", false);
            return;
        }
        ItemStack it = make(e, qty);
        // 한 묶음 크기를 넘으면 나눠서 준다 (곡괭이·인챈트북은 1개씩만 쌓임)
        int left = it.getAmount(), max = Math.max(1, it.getMaxStackSize());
        List<ItemStack> parts = new ArrayList<>();
        while (left > 0) { ItemStack part = it.clone(); part.setAmount(Math.min(max, left)); parts.add(part); left -= part.getAmount(); }
        for (ItemStack part : parts) Items.give(p, part);
        pl.net.toast(p, e.name + (qty > 1 ? " " + qty + "개" : "") + " 구매 완료 (다이아 -" + total + ")", true);
    }

    /** 인벤토리의 광물을 판다. only 가 null 이면 전부, 아니면 그 품목(블록 포함)만 */
    void sell(Player p, Material only) {
        double value = 0;
        int count = 0;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it == null || pl.items.idOf(it) != null) continue;
            Sell s = sells.get(it.getType());
            int mul = 1;
            if (s == null) { s = sellBlocks.get(it.getType()); mul = 9; }
            if (s == null || (only != null && s.material != only)) continue;
            value += s.price * mul * it.getAmount();
            count += it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (count == 0) { pl.net.toast(p, "판매할 수 있는 광물이 없습니다.", false); return; }
        Store.PData d = pl.store.get(p);
        double sum = d.frac + value;
        long whole = (long) Math.floor(sum + 1e-9);
        d.frac = Math.max(0, sum - whole);
        d.dirty = true;
        if (whole > 0) pl.ledger.addHeld(p, whole);
        pl.net.toast(p, whole > 0 ? "판매 완료 (다이아 +" + whole + ")"
                : "판매 완료. 다이아 1개가 되려면 조금 더 팔아야 합니다.", true);
    }

    Material sellMaterial(String mcKey) {
        Material m = Material.matchMaterial(mcKey);
        return m != null && sells.containsKey(m) ? m : null;
    }
}
