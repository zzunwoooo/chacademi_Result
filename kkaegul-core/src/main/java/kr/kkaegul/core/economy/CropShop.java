package kr.kkaegul.core.economy;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상점 "농작물" 탭 (AddCrop · CustomCrops ItemsAdder 아이템).
 *  - 씨앗: economy.crops.list 의 작물마다 addcrop:addcrop_<작물>_seed seed-bundle 개를 seed-price 다이아로 (모든 작물 같은 값)
 *  - 도구: economy.crops.tools (물뿌리개·경작지·스프링클러 등) 다이아로 구매. id 가 minecraft: 면 바닐라 아이템, name 으로 화면 이름 지정
 *  - 판매: 작물 1개 시세 × 등급 배수 (economy.crops.grade-multiplier). 시세는 sell-range 안에서 작물마다 랜덤,
 *    농사 시세(economy.farm-reroll-minutes)가 바뀔 때 같이 바뀜 → 한 작물만 계속 비싸지 않게
 */
public final class CropShop {
    private static final String[] GRADES = {"normal", "silver", "golden", "big", "rare"};

    private final KkaegulCore plugin;
    private final EconomyService economy;
    /** 아이템 id → 화면 이름/아이콘 (처음 만들 때 한 번) */
    private final Map<String, String[]> look = new HashMap<>();
    /** 도구 id → 설정의 화면 이름 (name) */
    private final Map<String, String> names = new HashMap<>();

    CropShop(KkaegulCore plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    private ConfigurationSection cfg() { return plugin.getConfig().getConfigurationSection("economy.crops"); }

    public boolean enabled() { ConfigurationSection c = cfg(); return c != null && c.getBoolean("enabled", true) && plugin.items().itemsAdder() != null; }

    private ItemStack make(String id, int amount) {
        if (id.startsWith("minecraft:")) {
            Material m = Material.matchMaterial(id);
            return m == null ? null : new ItemStack(m, Math.max(1, amount));
        }
        return plugin.items().itemsAdder().createAny(id, amount);
    }

    /** 작물 이름 목록 (list: [bean, ...] 또는 예전 list.<작물>: [...]) */
    private List<String> cropNames() {
        ConfigurationSection c = cfg();
        if (c == null) return List.of();
        if (c.isList("list")) return c.getStringList("list");
        ConfigurationSection list = c.getConfigurationSection("list");
        return list == null ? List.of() : new ArrayList<>(list.getKeys(false));
    }

    private double[] range() {
        ConfigurationSection c = cfg();
        List<?> r = c == null ? null : c.getList("sell-range");
        double lo = r != null && r.size() > 0 ? ((Number) r.get(0)).doubleValue() : 0.02;
        double hi = r != null && r.size() > 1 ? ((Number) r.get(1)).doubleValue() : lo;
        return new double[]{lo, Math.max(lo, hi)};
    }

    /** 시세가 없는 작물이 있거나 범위를 벗어나면 true (처음 켤 때 새로 뽑기) */
    boolean needsReroll() {
        double[] r = range();
        for (String crop : cropNames()) {
            Double v = plugin.global().cropPrices.get(crop);
            if (v == null || v < r[0] - 1e-9 || v > r[1] + 1e-9) return true;
        }
        return false;
    }

    /** 작물 시세 새로 뽑기 (작물마다 sell-range 안에서 랜덤, 소수 셋째 자리) */
    void reroll() {
        double[] r = range();
        plugin.global().cropPrices.clear();
        for (String crop : cropNames())
            plugin.global().cropPrices.put(crop, Math.round((r[0] + Math.random() * (r[1] - r[0])) * 1000) / 1000.0);
        plugin.global().dirty = true;
    }

    /** 판매가 표: 아이템 id → 1개당 다이아 */
    private Map<String, Double> sellTable() {
        Map<String, Double> out = new LinkedHashMap<>();
        ConfigurationSection c = cfg();
        if (c == null) return out;
        ConfigurationSection mul = c.getConfigurationSection("grade-multiplier");
        double[] r = range();
        for (String crop : cropNames()) {
            double base = plugin.global().cropPrices.getOrDefault(crop, (r[0] + r[1]) / 2);
            for (String g : GRADES) {
                double m = mul == null ? 1 : mul.getDouble(g, 0);
                if (m <= 0) continue;
                out.put("addcrop:addcrop_" + crop + (g.equals("normal") ? "" : "_" + g), Math.round(base * m * 1000) / 1000.0);
            }
        }
        return out;
    }

    private record Offer(String id, int amount, long price, String group) {}

    private List<Offer> offers() {
        List<Offer> out = new ArrayList<>();
        ConfigurationSection c = cfg();
        if (c == null) return out;
        int bundle = Math.max(1, c.getInt("seed-bundle", 10));
        long seed = c.getLong("seed-price", 1);
        if (seed > 0) for (String crop : cropNames()) out.add(new Offer("addcrop:addcrop_" + crop + "_seed", bundle, seed, "seed"));
        for (Map<?, ?> m : c.getMapList("tools")) {
            Object id = m.get("id"), price = m.get("price"), amount = m.get("amount");
            if (id == null || !(price instanceof Number pn)) continue;
            if (m.get("name") != null) names.put(id.toString(), m.get("name").toString());
            out.add(new Offer(id.toString(), amount instanceof Number an ? an.intValue() : 1, pn.longValue(), "tool"));
        }
        return out;
    }

    /** [이름, 아이콘] — 아이콘 = "재료|m=item_model|f=cmd 실수,..|s=cmd 문자열,.." (모드가 같은 모양으로 그림) */
    private String[] look(String id) {
        String[] l = look.get(id);
        if (l != null) return l;
        ItemStack it = make(id, 1);
        if (it == null) return new String[]{id, ""};
        String[] dsc = describe(it, id.substring(id.indexOf(':') + 1));
        String name = Txt.strip(names.getOrDefault(id, dsc[0])).replace("★", "").trim();
        if (id.endsWith("_silver")) name += " (은별)";
        else if (id.endsWith("_golden")) name += " (금별)";
        l = new String[]{name, dsc[1]};
        look.put(id, l);
        return l;
    }

    /** 아이템 → [보이는 이름, 모드 아이콘 문자열] (코인 아이템 탭도 같이 씀) */
    static String[] describe(ItemStack it, String fallback) {
        if (it == null) return new String[]{fallback, ""};
        String name = fallback;
        StringBuilder icon = new StringBuilder(it.getType().getKey().toString());
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            else if (meta.hasItemName()) name = PlainTextComponentSerializer.plainText().serialize(meta.itemName());
            try {
                if (meta.hasItemModel() && meta.getItemModel() != null) icon.append("|m=").append(meta.getItemModel());
                if (meta.hasCustomModelData()) {
                    var cmd = meta.getCustomModelDataComponent();
                    if (!cmd.getFloats().isEmpty()) icon.append("|f=").append(String.join(",", cmd.getFloats().stream().map(String::valueOf).toList()));
                    if (!cmd.getStrings().isEmpty()) icon.append("|s=").append(String.join(",", cmd.getStrings()));
                }
            } catch (LinkageError | RuntimeException ignored) { }
        }
        return new String[]{Txt.strip(name).trim(), icon.toString()};
    }

    /** 상점 창 데이터 crop: {buy:[...], sell:[...]} */
    public Json info(Player p) {
        List<Object> buy = new ArrayList<>(), sell = new ArrayList<>();
        if (!enabled()) return Json.obj().put("buy", buy).put("sell", sell);
        for (Offer o : offers()) {
            String[] l = look(o.id());
            if (l[1].isEmpty()) continue;   // ItemsAdder 에 없는 아이템
            buy.add(Json.obj().put("id", o.id()).put("name", l[0]).put("icon", l[1]).put("amount", o.amount()).put("price", o.price()).put("group", o.group()));
        }
        Map<String, Long> have = owned(p);
        for (var e : sellTable().entrySet()) {
            String[] l = look(e.getKey());
            if (l[1].isEmpty()) continue;
            long own = have.getOrDefault(e.getKey(), 0L);
            sell.add(Json.obj().put("id", e.getKey()).put("name", l[0]).put("icon", l[1]).put("price", e.getValue()).put("have", own).put("value", e.getValue() * own));
        }
        return Json.obj().put("buy", buy).put("sell", sell);
    }

    private Map<String, Long> owned(Player p) {
        Map<String, Long> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            String id = plugin.items().itemsAdder().namespacedIdOf(it);
            if (id != null) out.merge(id, (long) it.getAmount(), Long::sum);
        }
        return out;
    }

    /** 묶음 n 개 구매 (다이아: 보유 → 인벤토리 순) */
    public void buy(Player p, String id, long n) {
        if (!enabled() || n <= 0) return;
        Offer o = null;
        for (Offer x : offers()) if (x.id().equals(id)) { o = x; break; }
        if (o == null) { plugin.ui().error(p, "&c살 수 없는 아이템이에요."); return; }
        n = Math.min(n, 64);
        long cost = o.price() * n;
        long have = plugin.submit().available(p);
        if (have < cost) { plugin.msg(p, "shop.no-diamond", "need", Txt.num(cost), "have", Txt.num(have)); return; }
        if (make(id, 1) == null) { plugin.ui().error(p, "&c아이템을 준비하지 못했어요. 잠시 뒤 다시 시도해 주세요."); return; }
        plugin.submit().takeDiamonds(p, cost);
        long left = o.amount() * n;
        while (left > 0) {
            ItemStack it = make(id, 1);
            int stack = Math.max(1, it.getMaxStackSize());
            int give = (int) Math.min(left, stack);
            it.setAmount(give);
            left -= give;
            for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        String name = look(id)[0];
        plugin.ui().actionbar(p, "&a" + name + " " + Txt.num(o.amount() * n) + "개 구매 &7(다이아 -" + Txt.num(cost) + ")");
        plugin.ui().successSound(p);
        plugin.hud().refresh(p);
    }

    /** 고른 작물 amount 개 판매 */
    public void sell(Player p, String id, long amount) {
        Double price = sellTable().get(id);
        if (!enabled() || price == null || amount <= 0) { plugin.msg(p, "shop.cannot-sell"); return; }
        PlayerInventory inv = p.getInventory();
        long left = amount;
        int sold = 0;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!id.equals(plugin.items().itemsAdder().namespacedIdOf(it))) continue;
            int take = (int) Math.min(left, it.getAmount());
            left -= take;
            sold += take;
            if (take >= it.getAmount()) inv.setItem(i, null);
            else { it.setAmount(it.getAmount() - take); inv.setItem(i, it); }
        }
        if (sold <= 0) { plugin.msg(p, "shop.nothing"); return; }
        economy.pay(p, price * sold, look(id)[0] + " " + sold + "개 판매");
    }

    /** 인벤토리의 작물 전부 판매 */
    public void sellAll(Player p) {
        if (!enabled()) return;
        Map<String, Double> table = sellTable();
        PlayerInventory inv = p.getInventory();
        double value = 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            Double price = table.get(plugin.items().itemsAdder().namespacedIdOf(it));
            if (price == null) continue;
            value += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }
        if (count <= 0) { plugin.msg(p, "shop.nothing"); return; }
        economy.pay(p, value, "작물 " + count + "개 판매");
    }
}
