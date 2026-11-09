package kr.kkaegul.core.economy;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 돈 / 광물 판매 / 깨굴코인 구매 / 농사 시세.
 *  - 판매 = 다이아로 받음. economy.sell.<MATERIAL>: 0.08 → 그 아이템 1개 = 다이아 0.08개 (소수점은 쌓였다가 1이 되면 지급)
 *  - 작물 시세: economy.farm.<MATERIAL>: {min, max} (1개당 다이아) 를 1시간마다 랜덤
 *  - 코인 구매: 실패 10 / 정상 79 / 2배 10 / 3배 1 (economy.coin.outcomes)
 */
public final class EconomyService {
    private final KkaegulCore plugin;

    public EconomyService(KkaegulCore plugin) {
        this.plugin = plugin;
        if (plugin.global().farmPrices.isEmpty() || farmOutOfRange()) rerollFarm(false); // 예전(돈) 시세가 남아 있으면 새로 뽑기
        long period = Math.max(1, plugin.getConfig().getLong("economy.farm-reroll-minutes", 60)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long mins = plugin.getConfig().getLong("economy.farm-reroll-minutes", 60);
            if (System.currentTimeMillis() - plugin.global().farmUpdatedAt >= mins * 60_000L - 5_000) rerollFarm(true);
        }, 20L * 60, Math.min(period, 20L * 60));
    }

    /** 상점 표시 이름 */
    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("COAL", "석탄"), Map.entry("RAW_IRON", "철 원석"), Map.entry("IRON_INGOT", "철 주괴"),
            Map.entry("RAW_COPPER", "구리 원석"), Map.entry("COPPER_INGOT", "구리 주괴"), Map.entry("RAW_GOLD", "금 원석"),
            Map.entry("GOLD_INGOT", "금 주괴"), Map.entry("REDSTONE", "레드스톤"), Map.entry("LAPIS_LAZULI", "청금석"),
            Map.entry("EMERALD", "에메랄드"), Map.entry("QUARTZ", "석영"), Map.entry("AMETHYST_SHARD", "자수정 조각"),
            Map.entry("WHEAT", "밀"), Map.entry("CARROT", "당근"), Map.entry("POTATO", "감자"), Map.entry("BEETROOT", "비트"),
            Map.entry("MELON_SLICE", "수박 조각"), Map.entry("PUMPKIN", "호박"), Map.entry("SUGAR_CANE", "사탕수수"),
            Map.entry("COAL_BLOCK", "석탄 블록"), Map.entry("RAW_IRON_BLOCK", "철 원석 블록"), Map.entry("IRON_BLOCK", "철 블록"),
            Map.entry("RAW_COPPER_BLOCK", "구리 원석 블록"), Map.entry("COPPER_BLOCK", "구리 블록"), Map.entry("RAW_GOLD_BLOCK", "금 원석 블록"),
            Map.entry("GOLD_BLOCK", "금 블록"), Map.entry("REDSTONE_BLOCK", "레드스톤 블록"), Map.entry("LAPIS_BLOCK", "청금석 블록"),
            Map.entry("EMERALD_BLOCK", "에메랄드 블록"), Map.entry("QUARTZ_BLOCK", "석영 블록"), Map.entry("AMETHYST_BLOCK", "자수정 블록"));
    /** 광물 블록 → (재료, 개수). 블록 가격 = 재료 가격 × 개수 (economy.sell-blocks) */
    private record Block(String base, int count) {}
    private static final Map<String, Block> BLOCKS = Map.ofEntries(
            Map.entry("COAL_BLOCK", new Block("COAL", 9)), Map.entry("RAW_IRON_BLOCK", new Block("RAW_IRON", 9)),
            Map.entry("IRON_BLOCK", new Block("IRON_INGOT", 9)), Map.entry("RAW_COPPER_BLOCK", new Block("RAW_COPPER", 9)),
            Map.entry("COPPER_BLOCK", new Block("COPPER_INGOT", 9)), Map.entry("RAW_GOLD_BLOCK", new Block("RAW_GOLD", 9)),
            Map.entry("GOLD_BLOCK", new Block("GOLD_INGOT", 9)), Map.entry("REDSTONE_BLOCK", new Block("REDSTONE", 9)),
            Map.entry("LAPIS_BLOCK", new Block("LAPIS_LAZULI", 9)), Map.entry("EMERALD_BLOCK", new Block("EMERALD", 9)),
            Map.entry("QUARTZ_BLOCK", new Block("QUARTZ", 4)), Map.entry("AMETHYST_BLOCK", new Block("AMETHYST_SHARD", 4)));
    /** 재료 → 블록 (판매표에서 재료 바로 뒤에 블록을 넣을 때) */
    private static String blockOf(String base) {
        for (var e : BLOCKS.entrySet()) if (e.getValue().base().equals(base)) return e.getKey();
        return null;
    }
    /** 직전 시세 (상승/하락 표시용) */
    private final Map<String, Double> prevFarm = new LinkedHashMap<>();

    /** 상점 창 열기 (tab: coin / sell). 렌더러 없으면 false */
    public boolean openShop(Player p, String tab) {
        if (!plugin.ui().hasRenderer(p)) return false;
        plugin.ui().open(p, "shop", shopInfo(p).put("tab", tab));
        return true;
    }

    /** 고른 아이템을 amount 개 판매 (상점 창) */
    public long sellItem(Player p, String id, long amount) {
        Material m = Material.matchMaterial(id == null ? "" : id);
        if (m == null || priceOf(m) <= 0 || amount <= 0) { plugin.msg(p, "shop.cannot-sell"); return 0; }
        PlayerInventory inv = p.getInventory();
        long left = amount;
        int sold = 0;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!sellable(it) || it.getType() != m) continue;
            int take = (int) Math.min(left, it.getAmount());
            left -= take;
            sold += take;
            if (take >= it.getAmount()) inv.setItem(i, null);
            else { it.setAmount(it.getAmount() - take); inv.setItem(i, it); }
        }
        if (sold <= 0) { plugin.msg(p, "shop.nothing"); return 0; }
        return pay(p, priceOf(m) * sold, NAMES.getOrDefault(m.name(), m.name()) + " " + sold + "개 판매");
    }

    // ───────── 판매 (다이아로 받음) ─────────
    /** 아이템 1개 = 다이아 몇 개 (예: 금 0.08). 0 = 판매 불가 */
    public double priceOf(Material m) {
        Double farm = plugin.global().farmPrices.get(m.name());
        if (farm != null) return Math.max(0, farm);
        Block b = BLOCKS.get(m.name());
        if (b != null && !plugin.getConfig().contains("economy.sell." + m.name())) {
            if (!plugin.getConfig().getBoolean("economy.sell-blocks", true)) return 0;
            return Math.max(0, plugin.getConfig().getDouble("economy.sell." + b.base(), 0)) * b.count();
        }
        return Math.max(0, plugin.getConfig().getDouble("economy.sell." + m.name(), 0));
    }

    /** 0.08 / 1.5 / 0.015 처럼 보기 좋게 */
    public static String fmt(double v) {
        java.math.BigDecimal b = new java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
    }

    private boolean sellable(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || plugin.items().type(it) != null) return false;
        if (it.hasItemMeta() && (it.getItemMeta().hasEnchants() || it.getItemMeta().hasDisplayName())) return false;
        return priceOf(it.getType()) > 0;
    }

    /** 손에 든 것 전부 판매. 소수점은 쌓아뒀다가 1이 되면 다이아 1개. @return 받은 다이아 */
    public long sellHand(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!sellable(it)) { plugin.msg(p, "shop.cannot-sell"); return 0; }
        double value = priceOf(it.getType()) * it.getAmount();
        int n = it.getAmount();
        p.getInventory().setItemInMainHand(null);
        return pay(p, value, n + "개 판매");
    }

    /** 인벤토리의 판매 가능한 것 전부 판매 */
    public long sellAll(Player p) {
        PlayerInventory inv = p.getInventory();
        double value = 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (!sellable(it)) continue;
            value += priceOf(it.getType()) * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }
        if (count <= 0) { plugin.msg(p, "shop.nothing"); return 0; }
        return pay(p, value, count + "개 판매");
    }

    private static double diamonds(double v) { return Math.round(v * 1000) / 1000.0; }

    /** 판매 대금 = 보유 다이아 (정수 부분), 소수점은 sellDust 에 쌓임 */
    private long pay(Player p, double value, String why) {
        PlayerData d = plugin.data().get(p);
        double sum = d.sellDust + value + 1e-9;
        long gain = (long) Math.floor(sum);
        d.sellDust = Math.max(0, sum - gain - 1e-9);
        d.held += gain;
        d.markDirty();
        if (plugin.ui().hasRenderer(p)) plugin.ui().fx(p, "shop_sold", Json.obj().put("value", diamonds(value)).put("got", gain).put("dust", d.sellDust).put("what", why));
        plugin.msg(p, "shop.sold", "amount", fmt(value), "got", Txt.num(gain), "what", why, "held", Txt.num(d.held), "dust", fmt(d.sellDust));
        plugin.hud().refresh(p);
        if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "shop", shopInfo(p));
        return gain;
    }

    private java.util.List<Object> outcomes() {
        java.util.List<Object> l = new java.util.ArrayList<>();
        ConfigurationSection oc = plugin.getConfig().getConfigurationSection("economy.coin.outcomes");
        if (oc != null) for (String k : oc.getKeys(false))
            l.add(Json.obj().put("id", k).put("chance", oc.getDouble(k + ".chance")).put("multiplier", oc.getInt(k + ".multiplier", 1)));
        return l;
    }

    /** 상점 창 데이터: 다이아, 코인, 코인 가격, 판매표(아이템별 다이아 1개당 개수 + 내 보유 수) */
    public Json shopInfo(Player p) {
        PlayerData d = plugin.data().get(p);
        Map<Material, Long> have = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (sellable(it)) have.merge(it.getType(), (long) it.getAmount(), Long::sum);
        }
        java.util.List<Object> sell = new java.util.ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("economy.sell");
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (s != null) for (String k : s.getKeys(false)) {
            keys.add(k);
            String blk = blockOf(k.toUpperCase());
            if (blk != null && !s.contains(blk)) keys.add(blk);   // 광물 바로 뒤에 그 블록
        }
        keys.addAll(plugin.global().farmPrices.keySet());
        for (String k : keys) {
            Material m = Material.matchMaterial(k);
            if (m == null) continue;
            double per = priceOf(m);
            if (per <= 0) continue;
            long own = have.getOrDefault(m, 0L);
            Double prev = prevFarm.get(m.name());
            int trend = prev == null || Math.abs(prev - per) < 1e-9 ? 0 : per > prev ? 1 : -1;
            sell.add(Json.obj().put("id", m.name()).put("name", NAMES.getOrDefault(m.name(), m.name().toLowerCase())).put("trend", trend)
                    .put("price", per).put("have", own).put("value", per * own)
                    .put("farm", plugin.global().farmPrices.containsKey(m.name())));
        }
        long next = plugin.global().farmUpdatedAt + plugin.getConfig().getLong("economy.farm-reroll-minutes", 60) * 60_000L;
        return Json.obj().put("held", d.held).put("dust", d.sellDust).put("available", plugin.submit().available(p)).put("coins", d.coins)
                .put("coinPrice", plugin.getConfig().getLong("economy.coin.price-diamonds", 1))
                .put("coinMax", plugin.getConfig().getInt("economy.coin.max-per-buy", 100)).put("outcomes", outcomes())
                .put("toDiamond", toDiamondRate())
                .put("sell", sell).put("farmNextMs", Math.max(0, next - System.currentTimeMillis()));
    }

    // ───────── 코인 구매 ─────────
    public void buyCoins(Player p, int amount) {
        if (amount <= 0) return;
        amount = Math.min(amount, Math.max(1, plugin.getConfig().getInt("economy.coin.max-per-buy", 100)));   // 한 번에 최대 (모든 경로 공통)
        PlayerData d = plugin.data().get(p);
        if (plugin.getConfig().getString("economy.coin.currency", "diamond").equalsIgnoreCase("diamond")) {
            // 다이아(보유 → 인벤토리 순)로 결제
            long unit = plugin.getConfig().getLong("economy.coin.price-diamonds", 1);
            long cost = unit * amount;
            long have = plugin.submit().available(p);
            if (have < cost) { plugin.msg(p, "shop.no-diamond", "need", Txt.num(cost), "have", Txt.num(have)); return; }
            plugin.submit().takeDiamonds(p, cost);
        } else {
            long unit = plugin.getConfig().getLong("economy.coin.price", 1000);
            long cost = unit * amount;
            if (d.money < cost) { plugin.msg(p, "shop.no-money", "need", Txt.num(cost), "money", Txt.num(d.money)); return; }
            d.money -= cost;
        }
        ConfigurationSection oc = plugin.getConfig().getConfigurationSection("economy.coin.outcomes");
        Map<String, Double> w = new LinkedHashMap<>();
        if (oc != null) for (String k : oc.getKeys(false)) w.put(k, oc.getDouble(k + ".chance"));
        String out = Rng.weighted(w);
        int mult = out == null || oc == null ? 1 : oc.getInt(out + ".multiplier", 1);
        String msg = out == null || oc == null ? "" : oc.getString(out + ".message", "");
        long got = (long) amount * mult;
        d.coins += got;
        d.markDirty();
        plugin.ui().fx(p, "coin_buy", Json.obj().put("outcome", out).put("multiplier", mult).put("amount", amount).put("got", got).put("message", Txt.fmt(msg, "got", Txt.num(got))));
        if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "shop", shopInfo(p));
        else plugin.ui().center(p, Txt.fmt(msg, "got", Txt.num(got)), "&f보유 코인 " + Txt.num(d.coins));
        plugin.hud().refresh(p);
    }

    // ───────── 코인 → 다이아 교환 (제출용) ─────────
    /** 코인 1개 = 보유 다이아 몇 개 (0 = 교환 꺼짐) */
    public long toDiamondRate() { return Math.max(0, plugin.getConfig().getLong("economy.coin.to-diamond", 15)); }

    public void coinToDiamond(Player p, long coins) {
        long rate = toDiamondRate();
        if (rate <= 0) { plugin.msg(p, "shop.disabled"); return; }
        if (coins <= 0) return;
        PlayerData d = plugin.data().get(p);
        if (d.coins < coins) { plugin.ui().error(p, "&c깨굴코인이 부족합니다. &7(보유 " + Txt.num(d.coins) + "개)"); return; }
        d.coins -= coins;
        long got = coins * rate;
        plugin.wallet().deposit(d, got, p);
        plugin.ui().fx(p, "coin_exchange", Json.obj().put("coins", coins).put("got", got));
        if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "shop", shopInfo(p));
        else p.sendMessage(Txt.color(Txt.notice("&a깨굴코인 " + Txt.num(coins) + "개를 다이아 " + Txt.num(got) + "개로 바꿨습니다.")));
        plugin.hud().refresh(p);
    }

    /** 다이아로 코인 확정 교환 (의뢰인 고민 중 → 기본 꺼짐) */
    public void exchangeDiamond(Player p, int diamonds) {
        if (!plugin.getConfig().getBoolean("economy.diamond-exchange.enabled", false)) { plugin.msg(p, "shop.disabled"); return; }
        int have = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == Material.DIAMOND && !it.hasItemMeta()) have += it.getAmount();
        if (have < diamonds || diamonds <= 0) { plugin.ui().error(p, "&c다이아가 부족해요 (" + have + "개)"); return; }
        p.getInventory().removeItem(new ItemStack(Material.DIAMOND, diamonds));
        long per = plugin.getConfig().getLong("economy.diamond-exchange.coins-per-diamond", 1);
        PlayerData d = plugin.data().get(p);
        d.coins += per * diamonds;
        d.markDirty();
        p.sendMessage(Txt.color(Txt.notice("&a다이아 " + diamonds + "개 → 깨굴코인 " + Txt.num(per * diamonds) + "개")));
    }

    // ───────── 농사 시세 ─────────
    private boolean farmOutOfRange() {
        ConfigurationSection f = plugin.getConfig().getConfigurationSection("economy.farm");
        if (f == null) return false;
        for (String k : f.getKeys(false)) {
            Double v = plugin.global().farmPrices.get(k.toUpperCase());
            if (v == null || v < f.getDouble(k + ".min", 0) - 1e-9 || v > f.getDouble(k + ".max", 1) + 1e-9) return true;
        }
        return false;
    }

    public void rerollFarm(boolean announce) {
        ConfigurationSection f = plugin.getConfig().getConfigurationSection("economy.farm");
        if (f == null) return;
        prevFarm.clear();
        prevFarm.putAll(plugin.global().farmPrices);
        plugin.global().farmPrices.clear();
        for (String k : f.getKeys(false)) {
            double lo = f.getDouble(k + ".min", 0.01), hi = Math.max(lo, f.getDouble(k + ".max", lo));
            double v = Math.round((lo + Math.random() * (hi - lo)) * 1000) / 1000.0; // 소수 셋째 자리
            plugin.global().farmPrices.put(k.toUpperCase(), v);
        }
        plugin.global().farmUpdatedAt = System.currentTimeMillis();
        plugin.global().dirty = true;
        if (announce) {
            plugin.announce("&2[농사] &f작물 시세가 바뀌었습니다! &7/시세", false);
            Json j = farmSnapshot();
            for (Player p : Bukkit.getOnlinePlayers()) { plugin.ui().update(p, "farm", j); plugin.ui().update(p, "shop", shopInfo(p)); }
        }
    }

    public Json farmSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>(plugin.global().farmPrices);
        long next = plugin.global().farmUpdatedAt + plugin.getConfig().getLong("economy.farm-reroll-minutes", 60) * 60_000L;
        return Json.obj().put("prices", m).put("nextMs", Math.max(0, next - System.currentTimeMillis()));
    }
}
