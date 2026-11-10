package kr.kkaegul.core.box;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 상자.
 *  - 상자 아이템(일반/고급/행운)을 우클릭하면 boxes.<종류>.pool 에서 가중치로 n번 뽑는다.
 *  - 세트(1111/5555)는 boxes.<종류>.set 의 액션 목록을 그대로 지급.
 *  - 상자 피버 중이면 뽑기 횟수 ×2 (1+1).
 *  - 행운상자는 연 사람 보상 + 전체 유저 이벤트.
 */
public final class BoxService {
    private final KkaegulCore plugin;

    public BoxService(KkaegulCore plugin) { this.plugin = plugin; }

    public CustomItem itemOf(String kind) {
        return switch (kind.toLowerCase()) {
            case "premium", "고급" -> CustomItem.PREMIUM_BOX;
            case "lucky", "행운" -> CustomItem.LUCKY_BOX;
            default -> CustomItem.NORMAL_BOX;
        };
    }

    public String kindOf(CustomItem item) {
        return switch (item) {
            case PREMIUM_BOX -> "premium";
            case LUCKY_BOX -> "lucky";
            default -> "normal";
        };
    }

    public void giveBox(UUID to, String kind, int n) {
        Player p = Bukkit.getPlayer(to);
        var it = plugin.items().create(itemOf(kind), Math.max(1, n));
        if (p != null) {
            plugin.mail().giveOrMail(p, it);
            plugin.msg(p, "box.got", "box", Txt.strip(Txt.color(it.getItemMeta().getDisplayName())), "amount", n);
        } else plugin.mail().send(to, it, "상자 지급");
    }

    public void giveSet(UUID to, String kind) {
        List<String> set = plugin.getConfig().getStringList("boxes." + kind + ".set");
        Player p = Bukkit.getPlayer(to);
        if (p != null) plugin.ui().center(p, "&b" + plugin.getConfig().getString("boxes." + kind + ".set-label", kind + " 세트"), "");
        plugin.actions().runAll(to, set, kind + " 세트");
    }

    /** 보상 카드에 그릴 실제 아이템 모양 (아이템을 주는 보상만, 나머지는 "" → 모드가 기본 그림) */
    private String iconFor(String action) {
        String[] a = action.trim().split(" +");
        if (a.length == 0) return "";
        try {
            org.bukkit.inventory.ItemStack it = switch (a[0]) {
                case "item" -> {
                    if (a.length < 2) yield null;
                    CustomItem ci = CustomItem.byId(a[1]);
                    if (ci != null) yield plugin.items().create(ci, 1);
                    org.bukkit.Material m = org.bukkit.Material.matchMaterial(a[1]);
                    yield m == null ? null : new org.bukkit.inventory.ItemStack(m);
                }
                case "food" -> plugin.items().create(plugin.clock().isPeperoSeason() ? CustomItem.KKAEKKAERO : CustomItem.COLA, 1);
                case "box" -> {
                    String k = a.length > 1 ? a[1] : "normal";
                    yield plugin.items().create(k.equals("lucky") ? CustomItem.LUCKY_BOX : k.equals("premium") ? CustomItem.PREMIUM_BOX : CustomItem.NORMAL_BOX, 1);
                }
                case "mending" -> new org.bukkit.inventory.ItemStack(org.bukkit.Material.ENCHANTED_BOOK);
                default -> null;
            };
            return kr.kkaegul.core.util.ItemIcon.of(it);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 상자 아이템 사용 */
    public void open(Player p, String kind) { open(p, kind, 1); }

    /** count 개를 한 번에 연다 (웅크리고 우클릭 = 최대 10개). 보상은 같은 것끼리 묶어 연출 한 번 */
    public void open(Player p, String kind, int count) {
        PlayerData d = plugin.data().get(p);
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("boxes." + kind);
        if (c == null) { plugin.ui().error(p, "&c상자 설정이 없어요: " + kind); return; }
        count = Math.max(1, count);
        int rolls = c.getInt("rolls", 1) * plugin.fever().boxMultiplier();
        Map<String, Integer> pool = readPool(c.getConfigurationSection("pool"));
        Map<String, Json> byId = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int b = 0; b < count; b++)
            for (int i = 0; i < rolls; i++) {
                String entry = Rng.weighted(pool);
                if (entry == null) continue;
                String action = c.getString("pool." + entry + ".action", "");
                String label = c.getString("pool." + entry + ".label", entry);
                boolean rare = c.getBoolean("pool." + entry + ".rare", false);
                byId.putIfAbsent(entry, Json.obj().put("id", entry).put("label", label).put("rare", rare).put("icon", iconFor(action)));
                counts.merge(entry, 1, Integer::sum);
                plugin.actions().run(p.getUniqueId(), action, kind + " 상자");
                if (rare) plugin.announce(Txt.fmt(plugin.getConfig().getString("boxes.rare-broadcast",
                        "&d[대박] &f{name}님이 {box}에서 &e{item}&f 획득!"), "name", plugin.displayName(p), "box", c.getString("label", kind), "item", label), true);
            }
        List<Object> got = new ArrayList<>();
        for (var e : byId.entrySet()) {
            int n = counts.get(e.getKey());
            Json r = e.getValue();
            if (n > 1) r.put("label", r.map().get("label") + " ×" + n).put("count", n);
            got.add(r);
        }
        d.boxesOpened += count;
        d.markDirty();
        String title = c.getString("label", kind) + (count > 1 ? " " + count + "개" : "");
        Json payload = Json.obj().put("box", kind).put("label", title).put("rewards", got).put("count", count)
                .put("fever", plugin.fever().boxFeverActive());
        if (!plugin.ui().send(p, Json.obj().put("type", "fx").put("id", "box_open").put("data", payload))) {
            StringBuilder sb = new StringBuilder();
            for (Object o : got) sb.append(((Json) o).map().get("label")).append(", ");
            p.sendMessage(Txt.color(Txt.notice("&6[" + c.getString("label", kind) + "] &f" + (sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "꽝"))));
        }
        p.playSound(p.getLocation(), "minecraft:block.chest.open", 1f, 1f);
        if (kind.equals("lucky")) {
            // 상자 연출(보상 공개)이 끝난 뒤에 "모두에게 적용되는 이벤트"를 따로 띄운다 — 보상과 헷갈리지 않게
            long ms = plugin.ui().hasRenderer(p) ? (4600 + Math.min(8, got.size()) * 230L) * 10 / 16 + 400 : 1000;   // 모드 연출 1.6배속
            Bukkit.getScheduler().runTaskLater(plugin, () -> luckyEvent(p), Math.max(1, ms / 50));
        }
    }

    private Map<String, Integer> readPool(ConfigurationSection pool) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (pool == null) return out;
        for (String k : pool.getKeys(false)) out.put(k, pool.getInt(k + ".weight", 1));
        return out;
    }

    /** 행운상자: "ㅇㅇ님이 행운상자를 오픈!" 후 전체에게 이벤트 1종 */
    private void luckyEvent(Player opener) {
        ConfigurationSection ev = plugin.getConfig().getConfigurationSection("boxes.lucky.global-events");
        plugin.announce(Txt.fmt(plugin.getConfig().getString("boxes.lucky.broadcast", "&6{name}님이 행운상자를 오픈!"), "name", plugin.displayName(opener)), true);
        if (ev == null) return;
        Map<String, Integer> w = new LinkedHashMap<>();
        for (String k : ev.getKeys(false)) w.put(k, ev.getInt(k + ".weight", 1));
        String pick = Rng.weighted(w);
        if (pick == null) return;
        List<String> actions = ev.getStringList(pick + ".actions");
        String effect = ev.getString(pick + ".effect", "");
        int seconds = ev.getInt(pick + ".seconds", 60);
        // 버프/디버프는 행운·효율 중 하나를 정해 모두에게 같은 걸 줌 → 공지에 무엇인지 적을 수 있게
        String stat = Rng.chance(50) ? "fortune" : "efficiency";
        String label = ev.getString(pick + ".label", pick);
        if (effect.equals("buff") || effect.equals("debuff"))
            label = (seconds % 60 == 0 ? seconds / 60 + "분" : Txt.duration(seconds)) + "간 " + (stat.equals("fortune") ? "행운" : "효율") + (effect.equals("buff") ? " +1" : " -1");
        // 채팅: "ㅇㅇ님이 행운상자를 오픈!" 바로 밑에 효과
        Bukkit.broadcastMessage(Txt.color("&f{e:system} &f└ 효과: &e" + label + " &7(모두에게 적용)"));
        String shown = label;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // 상자 여는 연출이 끝난 뒤 가운데에 크게
            for (Player p : Bukkit.getOnlinePlayers()) plugin.ui().taunt(p, "&6행운상자: &f" + shown, "&7모두에게 적용");
        }, plugin.getConfig().getLong("boxes.lucky.notice-delay-ticks", 50));
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.actions().runAll(p.getUniqueId(), actions, "행운상자 이벤트");
            applyEffect(p, effect, stat, seconds);
        }
    }

    /** buff / debuff / fatigue / freeze */
    private void applyEffect(Player p, String effect, String stat, int seconds) {
        switch (effect) {
            case "buff" -> plugin.npcs().fortune().addTempBonus(p, stat, +1, seconds);
            case "debuff" -> plugin.npcs().fortune().addTempBonus(p, stat, -1, seconds);
            case "fatigue" -> p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, seconds * 20, 1));
            case "freeze" -> plugin.harass().freeze(p, seconds);
            default -> { }
        }
    }
}
