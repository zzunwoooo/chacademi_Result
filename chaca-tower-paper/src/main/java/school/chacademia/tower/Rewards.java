package school.chacademia.tower;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 탑 보상 (오너 2026-11-09). rewards.yml 에 아이템 그대로(Base64) 저장, 인게임 /탑관리 보상 창에서 넣고 뺀다.
 * <ul>
 *   <li>도달 보상: 판이 끝날 때 깬 층 기준 가장 높은 구간 하나만 (5층마다 구간).</li>
 *   <li>첫 도달 보상: 10·20·30·40·50층을 처음 깼을 때 사람마다 한 번 (한 번에 여러 층을 넘으면 넘은 것 모두).</li>
 *   <li>수문장 보상: 수문장을 잡을 때 chance% 로 묶음 전체.</li>
 * </ul>
 * 지급은 MagicCodexBridge RewardDelivery (가방 먼저, 넘치면 우편, source 키로 한 번만). 없으면 가방·발밑.
 */
final class Rewards {
    static final int[] TIERS = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50};
    static final int[] MILESTONES = {10, 20, 30, 40, 50};

    private final Plugin plugin;
    private final Logger log;
    private final File file;
    final Map<Integer, List<ItemStack>> tiers = new TreeMap<>();
    final Map<Integer, List<ItemStack>> milestones = new TreeMap<>();
    List<ItemStack> guardian = new ArrayList<>();
    double guardianChance = 30;

    Rewards(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "rewards.yml");
        load();
    }

    void load() {
        var y = YamlConfiguration.loadConfiguration(file);
        tiers.clear(); milestones.clear();
        for (int t : TIERS) tiers.put(t, decode(y.getStringList("tiers." + t)));
        for (int m : MILESTONES) milestones.put(m, decode(y.getStringList("first." + m)));
        guardian = decode(y.getStringList("guardian.items"));
        guardianChance = Math.clamp(y.getDouble("guardian.chance", 30), 0, 100);
    }

    void save() {
        var y = new YamlConfiguration();
        y.options().setHeader(List.of("탑 보상. 인게임 /탑관리 보상 으로 고치는 것을 권장 (아이템이 Base64 로 들어 있음).",
                "tiers = 도달 보상 (깬 층 기준 가장 높은 구간 하나), first = 첫 도달 보상, guardian = 수문장 보상 (chance %)"));
        for (var e : tiers.entrySet()) y.set("tiers." + e.getKey(), encode(e.getValue()));
        for (var e : milestones.entrySet()) y.set("first." + e.getKey(), encode(e.getValue()));
        y.set("guardian.chance", guardianChance);
        y.set("guardian.items", encode(guardian));
        try { y.save(file); } catch (Exception e) { log.severe("rewards.yml 저장 실패: " + e); }
    }

    // [기능:탑기본보상 시작] 오너 11/12 "보상도 적당히, 소모품·강화 위주": /탑관리 보상기본 으로 아래 표를 채운다 (빈 칸만, '덮어쓰기' 면 전부).
    // 강화 = 마력코어(미감정) + 돈(재화 주머니) + 주문서(복구·강화 초기화·성향 변환). 소모품 = 체력·마나 포션(일반·상급)·차큐빵·던전 입장권.
    static final java.util.Map<String, String[]> DEFAULTS = new java.util.LinkedHashMap<>();
    static {
        // 11/12 오너 "10층 단계마다 아티팩트 열쇠 · 매력적인 보상": 열쇠·아티팩트 확정 뽑기권(40층 유일·50층 전설)·가방 확장권 (타로카드·행운상자는 넣지 않음). "팍팍 뿌려, 강화 재료 더 좋게": 마력코어 약 3배·재화 주머니 약 2배·주문서 약 2배, 감정 코어는 안 줌 (미감정만 — 감정은 유저가 직접)
        DEFAULTS.put("tier:5", new String[]{"item:core_unidentified 2", "item:money_pouch 1", "item:health_potion 4", "item:mana_potion 4"});
        DEFAULTS.put("tier:10", new String[]{"item:core_unidentified 4", "item:money_pouch 2", "item:core_scroll_blue 1", "item:health_potion 5", "item:mana_potion 5", "item:random_artifact 1"});
        DEFAULTS.put("tier:15", new String[]{"item:core_unidentified 5", "item:money_pouch 3", "item:core_scroll_blue 1", "item:health_potion_big 2", "item:mana_potion_big 2", "item:dungeon_ticket 1"});
        DEFAULTS.put("tier:20", new String[]{"item:core_unidentified 7", "item:money_pouch 4", "item:core_scroll_blue 2", "item:core_scroll_red 1", "item:health_potion_big 3", "item:mana_potion_big 3", "item:random_artifact 1"});
        DEFAULTS.put("tier:25", new String[]{"item:core_unidentified 8", "item:money_pouch 5", "item:core_scroll_blue 2", "item:core_scroll_red 1", "item:core_scroll_purple 1", "item:health_potion_big 4", "item:mana_potion_big 4", "item:dungeon_ticket 1", "item:artifact_shard 3"});
        DEFAULTS.put("tier:30", new String[]{"item:core_unidentified 10", "item:money_pouch 6", "item:core_scroll_blue 3", "item:core_scroll_red 2", "item:core_scroll_purple 1", "item:health_potion_big 4", "item:mana_potion_big 4", "item:random_artifact 2"});
        DEFAULTS.put("tier:35", new String[]{"item:core_unidentified 12", "item:money_pouch 7", "item:core_scroll_blue 4", "item:core_scroll_red 2", "item:core_scroll_purple 2", "item:health_potion_big 5", "item:mana_potion_big 5", "item:dungeon_ticket 2", "item:artifact_shard 5"});
        DEFAULTS.put("tier:40", new String[]{"item:core_unidentified 14", "item:money_pouch 8", "item:core_scroll_blue 4", "item:core_scroll_red 3", "item:core_scroll_purple 2", "item:health_potion_big 5", "item:mana_potion_big 5", "item:random_artifact 2"});
        DEFAULTS.put("tier:45", new String[]{"item:core_unidentified 16", "item:money_pouch 10", "item:core_scroll_blue 5", "item:core_scroll_red 3", "item:core_scroll_purple 3", "item:health_potion_big 6", "item:mana_potion_big 6", "item:dungeon_ticket 2", "item:artifact_shard 8"});
        DEFAULTS.put("tier:50", new String[]{"item:core_unidentified 20", "item:money_pouch 12", "item:core_scroll_blue 6", "item:core_scroll_red 4", "item:core_scroll_purple 4", "item:health_potion_big 8", "item:mana_potion_big 8", "item:dungeon_ticket 3", "item:random_artifact 3"});
        DEFAULTS.put("first:10", new String[]{"item:core_unidentified 6", "item:money_pouch 4", "item:core_scroll_blue 2", "item:random_artifact 2"});
        DEFAULTS.put("first:20", new String[]{"item:core_unidentified 10", "item:money_pouch 6", "item:core_scroll_blue 3", "item:core_scroll_red 2", "item:random_artifact 2", "item:artifact_ticket_rare 1"});
        DEFAULTS.put("first:30", new String[]{"item:core_unidentified 14", "item:money_pouch 8", "item:core_scroll_blue 4", "item:core_scroll_red 2", "item:core_scroll_purple 2", "item:random_artifact 3", "item:artifact_ticket_rare 1"});
        DEFAULTS.put("first:40", new String[]{"item:core_unidentified 18", "item:money_pouch 10", "item:core_scroll_blue 5", "item:core_scroll_red 3", "item:core_scroll_purple 3", "item:random_artifact 3", "item:artifact_ticket_unique 1", "item:bag_expansion 1"});
        DEFAULTS.put("first:50", new String[]{"item:core_unidentified 25", "item:money_pouch 15", "item:core_scroll_blue 8", "item:core_scroll_red 5", "item:core_scroll_purple 5", "item:random_artifact 5", "item:artifact_ticket_legendary 1"});
        DEFAULTS.put("guardian", new String[]{"item:core_unidentified 3", "item:money_pouch 2", "item:core_scroll_blue 1", "item:health_potion_big 1", "item:random_artifact 1"});
    }

    /** @return 채운 칸 수 · 못 만든 아이템 목록 (아이템애더 이름이 틀렸거나 아직 안 켜짐). */
    String seed(ItemsAdderItems items, boolean overwrite) {
        int filled = 0;
        var missing = new java.util.LinkedHashSet<String>();
        for (var e : DEFAULTS.entrySet()) {
            var list = new ArrayList<ItemStack>();
            for (String spec : e.getValue()) {
                String[] p = spec.split(" ");
                int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                var it = items.create(p[0], 1);
                if (it == null) { missing.add(p[0]); continue; }
                while (amount > 0) { var c = it.clone(); int n = Math.min(amount, it.getMaxStackSize()); c.setAmount(n); list.add(c); amount -= n; }
            }
            if (list.isEmpty()) continue;
            String key = e.getKey();
            if (key.equals("guardian")) { if (overwrite || guardian.isEmpty()) { guardian = list; guardianChance = Math.max(guardianChance, 50); filled++; } continue; }   // 수문장 보상 확률 50%
            int at = Integer.parseInt(key.substring(key.indexOf(':') + 1));
            var map = key.startsWith("tier") ? tiers : milestones;
            if (overwrite || map.getOrDefault(at, List.of()).isEmpty()) { map.put(at, list); filled++; }
        }
        save();
        return filled + "칸 채움" + (missing.isEmpty() ? "" : " · 못 만든 아이템: " + String.join(", ", missing));
    }
    // [기능:탑기본보상 끝]

    /** 깬 층에 맞는 도달 구간 (없으면 0). */
    static int tierFor(int cleared) {
        int out = 0;
        for (int t : TIERS) if (cleared >= t) out = t;
        return out;
    }

    // ───────────────────── 지급 ─────────────────────

    /** 판 끝: 도달 구간 하나 + 처음 넘은 첫 도달 보상들. previousBest = 이번 판 전 최고 층. */
    void finish(UUID owner, Run run, int cleared, int previousBest) {
        int tier = tierFor(cleared);
        if (tier > 0 && !tiers.getOrDefault(tier, List.of()).isEmpty())
            give(owner, tiers.get(tier), "tower:" + run.runId + ":tier", "무한의 탑 보상", tier + "층 이상 도달 보상이에요.");
        for (int m : MILESTONES) {
            if (m <= previousBest || m > cleared) continue;
            var list = milestones.getOrDefault(m, List.of());
            if (!list.isEmpty()) give(owner, list, "tower:first:" + owner + ":" + m, "무한의 탑 첫 도달 보상", m + "층에 처음 올랐어요.");
        }
    }

    /** 수문장 처치: 당첨만 정해 두고 지급은 판이 끝난 뒤 (탑 안 소지품은 끝날 때 사라지므로). */
    void guardian(Run run, int floor) {
        if (guardian.isEmpty() || Math.random() * 100 >= guardianChance) return;
        run.pendingRewards.add(floor);
    }

    /** 판이 끝나고 소지품을 돌려받은 뒤 수문장 보상 지급. */
    void guardianAfter(UUID owner, Player p, Run run) {
        for (int floor : run.pendingRewards)
            give(owner, guardian, "tower:" + run.runId + ":guardian:" + floor, "무한의 탑 수문장 보상", floor + "층 수문장을 쓰러뜨렸어요.");
        if (p != null && !run.pendingRewards.isEmpty())
            ChacaLink.notify(p, "shop", "tower:guardian", "수문장 보상", "수문장 " + run.pendingRewards.size() + "마리 보상을 받았어요", 0);
    }

    /** 탑 안에서 다른 플러그인이 가방에 넣어 준 물건(의뢰 보상 등): 탑 소지품과 함께 사라지지 않게 가방·우편으로 보낸다. */
    void forward(UUID owner, List<ItemStack> items, String runId) {
        if (items.isEmpty()) return;
        give(owner, items, "tower:" + runId + ":forward:" + System.currentTimeMillis(), "무한의 탑에서 받은 물건", "탑 안에서 받은 물건을 보내 드려요.");
    }

    private void give(UUID owner, List<ItemStack> items, String source, String title, String body) {
        var copy = items.stream().map(ItemStack::clone).toList();
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge != null && bridge.isEnabled()) {
            try {
                Class<?> type = Class.forName("school.magiccodex.paper.RewardDelivery", true, bridge.getClass().getClassLoader());
                Method get = type.getDeclaredMethod("get");
                get.setAccessible(true);
                Object delivery = get.invoke(null);
                if (delivery != null) {
                    Method give = type.getDeclaredMethod("give", UUID.class, List.class, String.class, String.class, String.class, Consumer.class);
                    give.setAccessible(true);
                    Consumer<Boolean> mailed = ok -> { if (!ok) log.warning("보상 우편 저장 실패 player=" + owner + " source=" + source); };
                    give.invoke(delivery, owner, new ArrayList<>(copy), source, title, body, mailed);
                    return;
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                log.warning("보상 전달(RewardDelivery) 연결 실패, 직접 지급으로 대신함: " + e);
            }
        }
        Player p = Bukkit.getPlayer(owner);
        if (p == null) { log.severe("보상 지급 실패 (오프라인) player=" + owner + " source=" + source); return; }
        var left = p.getInventory().addItem(copy.toArray(ItemStack[]::new));
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    // ───────────────────── 입장창 미리보기 ─────────────────────

    /** 도달 보상 전부 (아이템 설명 첫 줄에 "N층 이상"). */
    List<ItemStack> tierPreview() {
        var out = new ArrayList<ItemStack>();
        for (var e : tiers.entrySet()) for (var it : e.getValue()) out.add(tagged(it, "◆ " + e.getKey() + "층 이상 (가장 높은 구간 하나)"));
        return out;
    }

    /** 아직 안 받은 첫 도달 보상. */
    List<ItemStack> firstPreview(int best) {
        var out = new ArrayList<ItemStack>();
        for (var e : milestones.entrySet()) if (e.getKey() > best) for (var it : e.getValue()) out.add(tagged(it, "◆ " + e.getKey() + "층 첫 도달"));
        return out;
    }

    List<ItemStack> guardianPreview() {
        var out = new ArrayList<ItemStack>();
        for (var it : guardian) out.add(tagged(it, "◆ 수문장 처치 " + Math.round(guardianChance) + "%"));
        return out;
    }

    private static ItemStack tagged(ItemStack it, String line) {
        var c = it.clone();
        c.editMeta(m -> {
            var lore = new ArrayList<Component>();
            lore.add(Component.text(line, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            if (m.lore() != null) lore.addAll(m.lore());
            m.lore(lore);
        });
        return c;
    }

    // ───────────────────── 설정 창 ─────────────────────

    /** /탑관리 보상: 고르는 창. */
    final class Menu implements InventoryHolder {
        private final Inventory inv = Bukkit.createInventory(this, 36, Component.text("탑 보상 설정"));

        Menu() {
            var glass = CardMenu.named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.text(" "), List.of());
            for (int i = 0; i < 36; i++) inv.setItem(i, glass);
            inv.setItem(4, CardMenu.named(new ItemStack(Material.BOOK), Component.text("탑 보상 설정", NamedTextColor.WHITE), List.of(
                    Component.text("칸을 누르면 그 보상 상자가 열려요.", NamedTextColor.GRAY),
                    Component.text("내 가방에서 아이템을 넣고 빼고, 닫으면 저장돼요.", NamedTextColor.GRAY))));
            for (int i = 0; i < TIERS.length; i++) inv.setItem(tierSlot(i), button(Material.CHEST, "도달 " + TIERS[i] + "층", tiers.get(TIERS[i]), "판이 끝날 때 깬 층 기준 가장 높은 구간 하나"));
            for (int i = 0; i < MILESTONES.length; i++) inv.setItem(27 + 2 + i, button(Material.ENDER_CHEST, "첫 도달 " + MILESTONES[i] + "층", milestones.get(MILESTONES[i]), "처음 깼을 때 한 번"));
            inv.setItem(35, button(Material.WITHER_SKELETON_SKULL, "수문장 " + Math.round(guardianChance) + "%", guardian, "좌클릭 +5% · 우클릭 -5% · 가운데(또는 Shift) 클릭 = 아이템 넣기"));
        }

        private ItemStack button(Material m, String name, List<ItemStack> list, String hint) {
            return CardMenu.named(new ItemStack(m), Component.text(name, NamedTextColor.YELLOW), List.of(
                    Component.text(hint, NamedTextColor.GRAY), Component.text("아이템 " + (list == null ? 0 : list.size()) + "개", NamedTextColor.WHITE)));
        }

        private static int tierSlot(int i) { return i < 5 ? 11 + i : 20 + (i - 5); }

        @Override public Inventory getInventory() { return inv; }

        /** 누른 칸 → 편집할 묶음 이름 ("tier:10" · "first:20" · "guardian"), 없으면 null. */
        String target(int slot) {
            for (int i = 0; i < TIERS.length; i++) if (slot == tierSlot(i)) return "tier:" + TIERS[i];
            for (int i = 0; i < MILESTONES.length; i++) if (slot == 27 + 2 + i) return "first:" + MILESTONES[i];
            return slot == 35 ? "guardian" : null;
        }
    }

    /** 묶음 하나 편집 상자 (54칸). 닫으면 저장. */
    final class Edit implements InventoryHolder {
        final String target;
        private final Inventory inv;

        Edit(String target) {
            this.target = target;
            inv = Bukkit.createInventory(this, 54, Component.text(title(target) + " 보상 (닫으면 저장)"));
            var list = list(target);
            for (int i = 0; i < list.size() && i < 54; i++) inv.setItem(i, list.get(i).clone());
        }

        @Override public Inventory getInventory() { return inv; }

        void save() {
            var out = new ArrayList<ItemStack>();
            for (var it : inv.getContents()) if (it != null && !it.getType().isAir()) out.add(it.clone());
            if (target.equals("guardian")) guardian = out;
            else if (target.startsWith("tier:")) tiers.put(Integer.parseInt(target.substring(5)), out);
            else milestones.put(Integer.parseInt(target.substring(6)), out);
            Rewards.this.save();
        }
    }

    static String title(String target) {
        if (target.equals("guardian")) return "수문장";
        if (target.startsWith("tier:")) return "도달 " + target.substring(5) + "층";
        return "첫 도달 " + target.substring(6) + "층";
    }

    private List<ItemStack> list(String target) {
        if (target.equals("guardian")) return guardian;
        if (target.startsWith("tier:")) return tiers.getOrDefault(Integer.parseInt(target.substring(5)), List.of());
        return milestones.getOrDefault(Integer.parseInt(target.substring(6)), List.of());
    }

    private static List<String> encode(List<ItemStack> items) {
        var out = new ArrayList<String>();
        for (var it : items) out.add(Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return out;
    }

    private List<ItemStack> decode(List<String> lines) {
        var out = new ArrayList<ItemStack>();
        for (String s : lines) {
            try { out.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(s))); }
            catch (RuntimeException e) { log.warning("rewards.yml 아이템 하나를 읽지 못했어요: " + e); }
        }
        return out;
    }
}
