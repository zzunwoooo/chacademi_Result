package school.chacademia.magicmythic;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 체력 포션 · 마나 포션 (2026-10-26 오너 결정, 예전 UtilMech.State 의 마나 포션 처리를 옮겨 옴).
 * <ul>
 *   <li>체력 포션 {@code item:health_potion}: 체력 5 회복, 쿨 10초.</li>
 *   <li>마나 포션 {@code item:mana_potion} (159/236 마력 예금이 만드는 그 포션 포함): 마나 100 회복(예전 50), 쿨 15초.</li>
 *   <li>쿨은 체력·마나 <b>따로</b> ({@link PotionCooldowns}) — 서로 막지 않는다. 회복량·쿨·아이템 id 는 config.yml health-potion / mana-potion.</li>
 * </ul>
 * 마시는 방식은 예전 마나 포션 그대로: 바닐라 물약 재질(POTION)이면 마셔서(섭취 이벤트), 아니면 우클릭으로 1개 사용.
 * 물약봉인이면 못 씀(StatusEffects 가 섭취를 막고, 우클릭은 여기서 막음). 회복봉인·체력 가득이면 아이템을 쓰지 않고 거절.
 * 쿨 중이면 아이템을 쓰지 않고 "N초 뒤" 알림. 매 틱 작업 없음 (사용할 때만 계산, 정리는 State 의 128틱 청소에 얹지 않고 사용 때 함께).
 */
final class Potions implements Listener {
    private static Potions instance;
    private final MagicMythicPlugin plugin;
    private final PotionCooldowns cooldowns = new PotionCooldowns();
    private long lastPrune;

    Potions(MagicMythicPlugin plugin) { this.plugin = plugin; instance = this; }

    // ------------------------------------------------------------------ 설정 (사용할 때마다 config 에서 읽음: /새로고침 바로 반영)

    private ConfigurationSection section(PotionCooldowns.Kind kind) {
        String key = kind == PotionCooldowns.Kind.HEALTH ? "health-potion" : "mana-potion";
        var s = plugin.getConfig().getConfigurationSection(key);
        return s != null ? s : plugin.getConfig().createSection(key);
    }
    private double restore(PotionCooldowns.Kind kind) {
        double v = section(kind).getDouble("restore", kind == PotionCooldowns.Kind.HEALTH ? 5 : 100);
        return Double.isFinite(v) ? Math.clamp(v, 0, 10000) : 0;
    }
    // [기능:alchemy-overhaul 시작] 상급 포션 (2026-10-31, 연금술 개편): 같은 쿨을 쓰고 회복량만 big-restore
    private static final String BIG_HEALTH = "item:health_potion_big", BIG_MANA = "item:mana_potion_big";
    private static String bigId(PotionCooldowns.Kind kind) {
        var p = instance;
        boolean health = kind == PotionCooldowns.Kind.HEALTH;
        String def = health ? BIG_HEALTH : BIG_MANA;
        String id = p == null ? null : p.plugin.getConfig().getString((health ? "health-potion" : "mana-potion") + ".big-item", def);
        return id == null || id.isBlank() ? def : id.strip();
    }
    private static boolean isBig(ItemStack it, PotionCooldowns.Kind kind) {
        return it != null && !it.getType().isAir() && bigId(kind).equals(Links.customId(it));
    }
    private double restore(PotionCooldowns.Kind kind, ItemStack item) {
        if (!isBig(item, kind)) return restore(kind);
        double v = section(kind).getDouble("big-restore", kind == PotionCooldowns.Kind.HEALTH ? 12 : 250);
        return Double.isFinite(v) ? Math.clamp(v, 0, 10000) : 0;
    }
    // [기능:alchemy-overhaul 끝]
    private long cooldownMillis(PotionCooldowns.Kind kind) {
        double v = section(kind).getDouble("cooldown-seconds", kind == PotionCooldowns.Kind.HEALTH ? 10 : 15);
        return Double.isFinite(v) ? Math.round(Math.clamp(v, 0, 3600) * 1000) : 0;
    }
    private static String name(PotionCooldowns.Kind kind) { return kind == PotionCooldowns.Kind.HEALTH ? "체력 포션" : "마나 포션"; }

    /** 체력 포션 ItemsAdder id (config health-potion.item). */
    static String healthId() {
        var p = instance;
        String id = p == null ? null : p.plugin.getConfig().getString("health-potion.item", "item:health_potion");
        return id == null || id.isBlank() ? "item:health_potion" : id.strip();
    }

    static boolean isHealthPotion(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        return healthId().equals(Links.customId(it))
                || isBig(it, PotionCooldowns.Kind.HEALTH);   // [기능:alchemy-overhaul] 상급 체력 포션
    }

    /** 체력 / 마나 / null(포션 아님). */
    static PotionCooldowns.Kind kind(ItemStack it) {
        if (UtilMech.isManaPotion(it)) return PotionCooldowns.Kind.MANA;
        if (isBig(it, PotionCooldowns.Kind.MANA)) return PotionCooldowns.Kind.MANA;   // [기능:alchemy-overhaul] 상급 마나 포션
        if (isHealthPotion(it)) return PotionCooldowns.Kind.HEALTH;
        return null;
    }

    // ------------------------------------------------------------------ 공통 판정·적용

    /** null = 쓸 수 있음, 아니면 알림 문구 (아이템 그대로). */
    private String refusal(Player p, PotionCooldowns.Kind kind) {
        long now = System.currentTimeMillis();
        long left = cooldowns.remaining(p.getUniqueId(), kind, now);
        if (left > 0) return name(kind) + "은 " + PotionCooldowns.seconds(left) + "초 뒤에 쓸 수 있어요";
        if (kind == PotionCooldowns.Kind.HEALTH) {
            if (Targets.sealed != null && Targets.sealed.test(p)) return "회복봉인 상태에서는 체력을 회복할 수 없어요";
            if (p.getHealth() >= Targets.maxHealth(p)) return "체력이 이미 가득 차 있어요";
        } else if (Links.manaSealed != null && Links.manaSealed.test(p)) return "회복봉인 상태에서는 마나를 회복할 수 없어요";
        return null;
    }

    /** 회복 + 쿨 시작. 회복이 안 됐으면 false (쿨도 시작 안 함). */
    private boolean apply(Player p, PotionCooldowns.Kind kind, ItemStack item) {
        double amount = restore(kind, item);   // [기능:alchemy-overhaul] 상급 포션이면 big-restore
        boolean ok = kind == PotionCooldowns.Kind.HEALTH ? Targets.heal(p, amount) > 0 : Links.add(p, amount);
        if (!ok) return false;
        long now = System.currentTimeMillis(), millis = cooldownMillis(kind);
        cooldowns.start(p.getUniqueId(), kind, now, millis);
        overlay(p, item, millis);
        if (now - lastPrune > 60_000L) { lastPrune = now; cooldowns.prune(now); }
        return true;
    }

    /**
     * 클라 남은 쿨 표시: 바닐라 아이템 쿨(핫바·배낭 칸의 흰 덮개, GalaxyBagScreen 도 그대로 그림). 바닐라 쿨은 <b>재질 단위</b>라
     * 같은 재질의 다른 아이템(예: 같은 PAPER 로 만든 귀환권)까지 잠시 못 쓰게 되므로 기본은 꺼 둠 — config cooldown-overlay: true 로 켬.
     * 체력·마나 포션이 같은 재질이면 서로 막히므로, 켜기 전에 ItemsAdder 두 포션의 재질이 다르고 다른 사용 아이템과 겹치지 않는지 확인.
     * (서버 쪽 쿨은 이 표시와 상관없이 항상 따로 돈다.)
     */
    private void overlay(Player p, ItemStack item, long millis) {
        if (millis <= 0 || item == null || !plugin.getConfig().getBoolean("potion-cooldown-overlay", false)) return;
        p.setCooldown(item.getType(), (int) Math.min(Integer.MAX_VALUE, millis / 50));
    }

    private void refuse(Player p, String why) {
        ChacaLink.notify(p, "warning", "magic-mythic:potion", why, "", 1500);
    }

    // ------------------------------------------------------------------ 마시기 (바닐라 물약 재질)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drinkCheck(PlayerItemConsumeEvent e) {
        var kind = kind(e.getItem());
        if (kind == null) return;
        String why = refusal(e.getPlayer(), kind);
        if (why == null) return;
        e.setCancelled(true);
        refuse(e.getPlayer(), why);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drink(PlayerItemConsumeEvent e) {
        var kind = kind(e.getItem());
        if (kind == null) return;
        apply(e.getPlayer(), kind, e.getItem());   // 물약봉인: StatusEffects(LOW)가 이미 취소함
    }

    // ------------------------------------------------------------------ 우클릭 (물약 재질이 아닌 ItemsAdder 아이템)

    @EventHandler(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() == Material.POTION) return;
        var kind = kind(it);
        if (kind == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (plugin.status().potionSealed(p)) { ChacaLink.notify(p, "warning", "magic-mythic:potion-seal", "물약봉인 상태에서는 물약을 쓸 수 없어요", "", 2000); return; }
        String why = refusal(p, kind);
        if (why != null) { refuse(p, why); return; }
        if (!apply(p, kind, it)) return;
        if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, .6f, 1.1f);
    }

    @EventHandler public void quit(PlayerQuitEvent e) { cooldowns.forget(e.getPlayer().getUniqueId()); }

    void close() { if (instance == this) instance = null; }

    /** 테스트·명령용: 남은 쿨 (ms). */
    long remaining(UUID id, PotionCooldowns.Kind kind) { return cooldowns.remaining(id, kind, System.currentTimeMillis()); }
}
