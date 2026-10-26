package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import school.magiccodex.protocol.TarotProtocol;

/**
 * 사용 아이템 3종 (2026-10-26 오너 결정). 설정 utility-items.yml, 고친 뒤 /서버관리 새로고침 사용아이템.
 * <ul>
 *   <li><b>변색종 변경권</b> {@code item:shiny_change}: 들고 야생 몹(CA_Wild_*)을 우클릭 → 그 몹을 변색종으로 (관리자
 *       /월드관리 변색종 지정 과 같은 ShinyBridge.mark: 변색종 모델 ca_xxx_s 로 바꾸고 표식·이름 색·반짝임). 성공하면 1개.
 *       변색종 모델이 없는 몹·이미 변색종·보스·교화 중·등록 안 된 몹·막힌 세계는 거절(아이템 그대로).
 *       교화하면 변색종 펫이 된다 (TamingBridge.profile: 변색종이면 pet-id + "_shiny").</li>
 *   <li><b>마법 힌트권</b> {@code item:spell_hint}: 우클릭 → 서버가 별(1~3)·힌트(LifeSpells.hintCard)·카드 그림(0~19)을 정하고
 *       1개 사용 → MagicCodex 모드에 「운명의 타로」 창 (TarotProtocol, §41). 모드 없는 클라이언트는 예전처럼 깨달음의 빛(221)의
 *       빛의 책 연출(카탈로그 enlighten_hint) + 알림·채팅 한 줄. 남은 마법이 없으면 거절.</li>
 *   <li><b>재화 주머니</b> {@code item:money_pouch}: 우클릭 → Vault 로 돈 지급 (금액 표 money-pouch.tiers), 결과 알림, 운영 기록 "돈".</li>
 * </ul>
 * 서버 부담: 우클릭 때만 (아이템 id 비교 → 해당할 때만 계산). 매 틱 작업·DB 없음. 메인 스레드 전용.
 */
final class UtilityItemsBridge implements Listener {
    private final MagicCodexBridge plugin;
    private final ItemsAdderItems itemsAdder;
    private final Map<UUID, Long> gate = new HashMap<>();

    private record Settings(String shinyItem, double shinyRange, List<String> shinyPrefixes, Set<String> shinyWorlds,
                            String hintItem, double[] stars, boolean tarotUi,
                            String pouchItem, long pouchStep, List<MoneyPouchRules.Tier> tiers) {}
    private Settings settings;

    UtilityItemsBridge(MagicCodexBridge plugin) throws Exception {
        this.plugin = plugin;
        this.itemsAdder = new ItemsAdderItems(plugin.getLogger());
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, TarotProtocol.CHANNEL);   // 운명의 타로 (§41)
        ChacaApi.reload(plugin, "사용아이템", "utility-items.yml (변색종 변경권·마법 힌트권·재화 주머니)", this::reload);
    }

    void reload() throws Exception {
        var file = new File(plugin.getDataFolder(), "utility-items.yml");
        if (!file.exists()) plugin.saveResource("utility-items.yml", false);
        var y = new YamlConfiguration();
        y.load(file);
        String shiny = id(y.getString("shiny-change.item", "item:shiny_change"), "shiny-change.item");
        double range = y.getDouble("shiny-change.range", 6);
        if (!Double.isFinite(range) || range < 2 || range > 16) throw new IllegalArgumentException("utility-items.yml shiny-change.range 는 2~16");
        var prefixes = List.copyOf(y.getStringList("shiny-change.mythic-prefixes"));
        var worlds = Set.copyOf(y.getStringList("shiny-change.excluded-worlds"));
        String hint = id(y.getString("spell-hint.item", "item:spell_hint"), "spell-hint.item");
        double[] stars = new double[3];
        for (int i = 0; i < 3; i++) {
            stars[i] = y.getDouble("spell-hint.stars." + (i + 1), new double[]{50, 40, 10}[i]);
            if (!Double.isFinite(stars[i]) || stars[i] < 0) throw new IllegalArgumentException("utility-items.yml spell-hint.stars 는 0 이상 숫자");
        }
        if (stars[0] + stars[1] + stars[2] <= 0) throw new IllegalArgumentException("utility-items.yml spell-hint.stars 합이 0");
        boolean tarotUi = y.getBoolean("spell-hint.tarot-ui", true);
        String pouch = id(y.getString("money-pouch.item", "item:money_pouch"), "money-pouch.item");
        long step = Math.clamp(y.getLong("money-pouch.round-to", 10), 1, 1000);
        var tiers = MoneyPouchRules.parse(y.getMapList("money-pouch.tiers"));
        settings = new Settings(shiny, range, prefixes.isEmpty() ? List.of("CA_Wild_") : prefixes, worlds, hint, stars, tarotUi, pouch, step, tiers);
    }

    private static String id(String v, String key) {
        String s = v == null ? "" : v.strip();
        if (!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("utility-items.yml " + key + ": namespace:id (예: item:money_pouch)");
        return s;
    }

    // ------------------------------------------------------------------ 우클릭

    private enum Kind { SHINY, HINT, POUCH }

    private Kind kind(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        String id = itemsAdder.idOf(it);
        if (id == null) return null;
        var s = settings;
        if (id.equals(s.shinyItem())) return Kind.SHINY;
        if (id.equals(s.hintItem())) return Kind.HINT;
        if (id.equals(s.pouchItem())) return Kind.POUCH;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        var kind = kind(event.getPlayer().getInventory().getItem(hand));
        if (kind == null) return;
        event.setCancelled(true);
        handle(event.getPlayer(), hand, kind);
    }

    /** 몹을 직접 우클릭하면 PlayerInteractEvent 대신 이게 온다 (변색종 변경권이 주로 이 길). */
    @EventHandler(priority = EventPriority.HIGH)
    public void useOn(PlayerInteractEntityEvent event) {
        EquipmentSlot hand = event.getHand();
        var kind = kind(event.getPlayer().getInventory().getItem(hand));
        if (kind == null) return;
        event.setCancelled(true);
        handle(event.getPlayer(), hand, kind);
    }

    private void handle(Player p, EquipmentSlot hand, Kind kind) {
        long now = System.currentTimeMillis();
        Long last = gate.get(p.getUniqueId());
        if (last != null && now - last < 400) return;   // 한 번 우클릭에 이벤트 여러 개(블록·엔티티·양손) → 한 번만
        gate.put(p.getUniqueId(), now);
        switch (kind) {
            case SHINY -> shiny(p, hand);
            case HINT -> hint(p, hand);
            case POUCH -> pouch(p, hand);
        }
    }

    /** 손의 아이템 1개 빼기 (크리에이티브도 뺌 — 운영자 시험 때 개수 확인이 쉽게). */
    private static ItemStack takeOne(Player p, EquipmentSlot hand) {
        ItemStack held = p.getInventory().getItem(hand);
        if (held == null || held.getType().isAir() || held.getAmount() < 1) return null;
        ItemStack one = held.clone(); one.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
        return one;
    }

    private static void giveBack(Player p, EquipmentSlot hand, ItemStack one) {
        ItemStack now = p.getInventory().getItem(hand);
        if (now == null || now.getType().isAir()) { p.getInventory().setItem(hand, one); return; }
        if (now.isSimilar(one) && now.getAmount() < now.getMaxStackSize()) { now.setAmount(now.getAmount() + 1); p.getInventory().setItem(hand, now); return; }
        for (var left : p.getInventory().addItem(one).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private static void warn(Player p, String key, String title, String body) {
        CmdKit.notify(p, "warning", "utility-" + key, title, body == null ? "" : body, 1200);
    }

    // ------------------------------------------------------------------ 변색종 변경권

    private void shiny(Player p, EquipmentSlot hand) {
        var s = settings;
        var taming = plugin.tamingBridge();
        ShinyBridge shiny = taming == null ? null : taming.shiny();
        if (shiny == null) { warn(p, "shiny", "지금은 변색종 변경권을 쓸 수 없어요", ""); return; }
        LivingEntity e = taming.target(p, s.shinyRange());
        String refusal = shinyRefusal(p, e, taming, shiny, s);
        if (refusal != null) { warn(p, "shiny", refusal, "변색종 변경권은 그대로예요"); return; }
        String type = taming.mythicType(e);
        try { shiny.mark(e, true); }
        catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().info("변색종 변경권 거절: " + p.getName() + " " + type + " — 변색종 모델 없음/연결 실패 (" + ex.getMessage() + ")");
            warn(p, "shiny", "이 몹은 변색종이 없어요", "변색종 변경권은 그대로예요");
            return;
        }
        if (!shiny.isShiny(e)) { warn(p, "shiny", "변색종으로 바꾸지 못했어요", "변색종 변경권은 그대로예요"); return; }
        takeOne(p, hand);
        String name = taming.hudInfo(e).name();
        CmdKit.notify(p, "system", "utility-shiny", (name.isBlank() ? "몹" : name) + "이(가) 변색종이 되었어요!", "교화하면 변색종 펫이 돼요", 0);
        p.playSound(e.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        p.playSound(e.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .5f, 1.6f);
        ChacaApi.stat(p.getUniqueId(), "변색종변경권", 1);
        plugin.getLogger().info("변색종 변경권 사용: " + p.getName() + " → " + type + " " + e.getUniqueId() + " @" + e.getWorld().getName());
    }

    /** null = 바꿀 수 있음. */
    private static String shinyRefusal(Player p, LivingEntity e, TamingBridge taming, ShinyBridge shiny, Settings s) {
        if (e == null) return "변색종으로 바꿀 야생 몹을 바라보고 우클릭해 주세요";
        if (s.shinyWorlds().contains(e.getWorld().getName())) return "여기서는 쓸 수 없어요";
        String type = taming.mythicType(e);
        boolean wild = type != null && taming.profileId(e) != null && s.shinyPrefixes().stream().anyMatch(type::startsWith);
        if (!wild) return "야생 몹에게만 쓸 수 있어요";
        if (type.endsWith("_shiny") || shiny.isShiny(e)) return "이미 변색종이에요";
        if (taming.hudInfo(e).boss()) return "보스에게는 쓸 수 없어요";
        if (taming.captureLocked(e)) return "교화 중인 몹에게는 쓸 수 없어요";
        if (!Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return "지금은 변색종 모델을 불러올 수 없어요";
        return null;
    }

    // ------------------------------------------------------------------ 마법 힌트권

    private void hint(Player p, EquipmentSlot hand) {
        var life = LifeSpells.current();
        if (life == null) { warn(p, "hint", "지금은 마법 힌트권을 쓸 수 없어요", ""); return; }
        int stars = rollStars(settings.stars());
        var pick = life.pickCard(p, stars);
        if (pick == null) { warn(p, "hint", "더 알려 줄 마법이 없어요", "마법 힌트권은 그대로예요"); return; }
        if (takeOne(p, hand) == null) return;
        ChacaApi.stat(p.getUniqueId(), "마법힌트권", 1);
        ChacaApi.stat(p.getUniqueId(), "마법힌트권" + pick.stars() + "성", 1);
        plugin.getLogger().info("마법 힌트권 사용: " + p.getName() + " → " + pick.condition().spell() + " (" + pick.stars() + "성)");
        // 3성 진행도는 MagicDiscovery 에서 읽는다 (보통 바로, DB 면 비동기·최대 3초) → 문장이 정해지면 타로 창·알림을 보낸다
        life.hintCard(p, pick, card -> { if (p.isOnline()) showHint(p, card); });
    }

    static int rollStars(double[] weights) {
        double total = weights[0] + weights[1] + weights[2], r = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < 3; i++) { r -= weights[i]; if (r < 0) return i + 1; }
        return 3;
    }

    /** 카드 그림 번호 0~19 (겉모습만, 힌트·별과 따로 뽑음 — 오너 명세). 지금은 20종 같은 확률. */
    static int rollIllustration() { return ThreadLocalRandom.current().nextInt(TarotProtocol.ILLUSTRATIONS); }

    /**
     * 보여 주기 (힌트권은 이미 1개 썼고 결과도 정해진 뒤).
     * MagicCodex 모드(TarotProtocol 채널을 듣는 클라이언트) → 「운명의 타로」 창: 카드 세 장 중 하나를 고르면(연출일 뿐, 결과는 같음)
     * 뒤집혀 별·그림·힌트가 나온다. 창을 닫으면(확인·Esc 모두) 클라이언트가 채팅에 힌트 한 줄을 남긴다 → 고르기 전에 닫아도 힌트는 남음.
     * 모드 없음·보내기 실패·tarot-ui: false → 예전 연출: 깨달음의 빛(221)의 빛의 책 VFX (카탈로그 enlighten_hint) + 1.2초 뒤 알림 카드·채팅 한 줄.
     */
    private void showHint(Player p, LifeSpells.HintCard card) {
        if (settings.tarotUi() && p.getListeningPluginChannels().contains(TarotProtocol.CHANNEL)) {
            try {
                p.sendPluginMessage(plugin, TarotProtocol.CHANNEL, TarotProtocol.encode(new TarotProtocol.Card(card.stars(), rollIllustration(), card.line())));
                return;
            } catch (RuntimeException e) {   // 묶음 오류·채널 미등록 등 — 결과는 이미 정해졌으니 예전 방식으로라도 꼭 보여 준다
                plugin.getLogger().warning("마법 힌트권 타로 창을 보내지 못함 (" + e.getMessage() + ") — 알림·채팅으로 보여 줌");
            }
        }
        CatalogVfxLink.cast(plugin, p, "enlighten_hint");
        String stars = "★".repeat(card.stars()) + "☆".repeat(3 - card.stars());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Component.text("[마법 힌트 " + stars + "] ", NamedTextColor.YELLOW).append(Component.text(card.line(), NamedTextColor.WHITE)));
            CmdKit.notify(p, "system", "utility-hint", "마법 힌트 " + stars, card.line(), 0);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, card.stars() == 3 ? 1.6f : 1.2f);
        }, 24);
    }

    // ------------------------------------------------------------------ 재화 주머니

    private void pouch(Player p, EquipmentSlot hand) {
        var s = settings;
        var economy = economy();
        if (economy == null) { warn(p, "pouch", "지금은 재화 주머니를 열 수 없어요", "경제 기능 연결을 확인 중이에요 · 주머니는 그대로예요"); return; }
        var roll = MoneyPouchRules.roll(s.tiers(), s.pouchStep(), ThreadLocalRandom.current());
        ItemStack one = takeOne(p, hand);
        if (one == null) return;
        String why = deposit(economy, p, roll.amount());
        if (why != null && why.startsWith("?")) {
            // 결과를 알 수 없음 (경제 플러그인 오류): 돈이 들어갔을 수도 있어 주머니를 돌려주지 않는다 — 관리자가 기록을 보고 처리
            plugin.getLogger().severe("재화 주머니 지급 결과 불명: " + p.getName() + " " + p.getUniqueId() + " " + roll.amount() + " G (" + why.substring(1) + ") — /유저관리 돈 보기로 확인 필요");
            AuditLog.log(p, AuditLog.돈, "재화 주머니 지급 결과 불명 " + roll.amount() + " G", "출처", "재화 주머니", "금액", roll.amount(), "오류", why.substring(1));
            warn(p, "pouch", "재화 주머니를 확인하지 못했어요", "관리자에게 문의해 주세요");
            return;
        }
        if (why != null) {
            giveBack(p, hand, one);
            plugin.getLogger().warning("재화 주머니 지급 실패: " + p.getName() + " " + roll.amount() + " G (" + why + ") — 주머니 돌려줌");
            warn(p, "pouch", "재화 주머니를 열지 못했어요", "주머니는 돌려드렸어요");
            return;
        }
        String money = String.format("%,d G", roll.amount());
        CmdKit.notify(p, "system", "utility-pouch", "재화 주머니 +" + money, roll.tier().name(), 0);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .8f, 1.2f);
        if (roll.tier() == s.tiers().get(s.tiers().size() - 1)) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .7f, 1.4f);
        // 운영 기록: 주머니는 금액과 상관없이 전부 남긴다 (money-threshold 무시 — 아이템 사용 기록이라서)
        AuditLog.log(p, AuditLog.돈, "재화 주머니 +" + roll.amount() + " G (" + roll.tier().name() + ")", "출처", "재화 주머니", "금액", roll.amount(), "구간", roll.tier().name());
        ChacaApi.stat(p.getUniqueId(), "재화주머니", 1);
        ChacaApi.stat(p.getUniqueId(), "재화주머니금액", roll.amount());
        plugin.getLogger().info("재화 주머니: " + p.getName() + " +" + roll.amount() + " G (" + roll.tier().name() + ")");
    }

    private record Economy(Class<?> type, Object provider) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Economy economy() {
        for (Class<?> type : Bukkit.getServicesManager().getKnownServices())
            if (type.getName().equals("net.milkbowl.vault.economy.Economy")) {
                var r = Bukkit.getServicesManager().getRegistration((Class) type);
                if (r != null) return new Economy(type, r.getProvider());
            }
        return null;
    }

    /** Vault depositPlayer. null = 성공, 아니면 실패 이유 (돈은 안 들어감). "?" 로 시작하면 결과를 알 수 없음. */
    private static String deposit(Economy e, OfflinePlayer p, long amount) {
        java.lang.reflect.Method method;
        try { method = e.type().getMethod("depositPlayer", OfflinePlayer.class, double.class); }
        catch (NoSuchMethodException | RuntimeException error) { return error.getClass().getSimpleName(); }   // 부르기 전 실패: 돈 안 들어감
        try {
            Object result = method.invoke(e.provider(), p, (double) amount);
            boolean ok = (boolean) result.getClass().getMethod("transactionSuccess").invoke(result);
            if (ok) return null;
            try { return String.valueOf(result.getClass().getField("errorMessage").get(result)); } catch (ReflectiveOperationException ignored) { return "거절"; }
        } catch (ReflectiveOperationException | RuntimeException error) { return "?" + error.getClass().getSimpleName(); }
    }

    @EventHandler public void quit(PlayerQuitEvent e) { gate.remove(e.getPlayer().getUniqueId()); }
}
