package school.chacademia.tower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/**
 * 탑 진행. 방이 하나라 한 번에 한 판 (나머지는 대기열).
 * 입장 → 맡기기 → 시작 카드(기본 공격 마법) → 층 (카운트다운 → 무리 → 다 잡으면 카드) → … → 쓰러지거나 포기하거나 꼭대기.
 * 끝나면 맡긴 것을 돌려주고 탑에서 얻은 건 모두 사라진다.
 */
final class TowerService {
    private final TowerPlugin plugin;
    final Floors floors;
    private final Magic magic;
    private final Stash stash;
    private final Records records;
    private final ItemsAdderItems items;
    private final NamespacedKey hpKey, speedKey;
    final Rewards rewards;
    final TowerClient client;

    Settings s;
    private Run run;
    private Player runner;
    private BossBar guardianBar;
    private UUID guardian;
    private final List<UUID> queue = new ArrayList<>();
    private UUID offered;
    private long offerUntil;
    private final Map<UUID, Long> portalNotice = new HashMap<>();
    private static final Set<String> BLOCKED = Set.of("spawn", "스폰", "home", "집", "warp", "워프", "tpa", "tpahere", "tpaccept", "back", "rtp",
            "server", "서버", "이동", "귀환", "마을", "hub", "lobby", "로비", "던전", "큐디치");

    TowerService(TowerPlugin plugin, Magic magic, Stash stash, Records records, ItemsAdderItems items) {
        this.plugin = plugin; this.magic = magic; this.stash = stash; this.records = records; this.items = items;
        this.floors = new Floors(plugin);
        this.client = new TowerClient(plugin);
        this.hpKey = new NamespacedKey(plugin, "tower_hp");
        this.speedKey = new NamespacedKey(plugin, "tower_speed");
        this.rewards = new Rewards(plugin);
    }

    void apply(Settings next) { s = next; }

    boolean inRun(Player p) { return run != null && run.phase != Run.Phase.ENDED && run.player.equals(p.getUniqueId()); }

    boolean blockedCommand(String cmd) { return BLOCKED.contains(cmd); }

    Run current() { return run; }

    TowerPlugin plugin() { return plugin; }

    // ───────────────────── 입장 ─────────────────────

    /** /탑 입장 (또는 대기열 순서가 와서). */
    void enter(Player p) {
        if (inRun(p)) { ChacaLink.notify(p, "warning", "tower:enter", "이미 탑에 있어요", "", 1000); return; }
        if (stash.has(p.getUniqueId())) { restoreLeftover(p); }
        var missing = s.missing();
        if (!missing.isEmpty()) { ChacaLink.notify(p, "error", "tower:enter", "탑이 아직 준비되지 않았어요", "관리자에게 알려 주세요", 3000); plugin.getLogger().warning("탑 설정 부족: " + missing); return; }
        if (!magic.ready()) { ChacaLink.notify(p, "error", "tower:enter", "지금은 들어갈 수 없어요", "잠시 뒤에 다시 시도해 주세요", 3000); return; }
        if (run != null && run.phase != Run.Phase.ENDED) { joinQueue(p); return; }
        if (offered != null && !offered.equals(p.getUniqueId()) && System.currentTimeMillis() < offerUntil) { joinQueue(p); return; }
        if (!queue.isEmpty() && !queue.getFirst().equals(p.getUniqueId()) && offered == null) { joinQueue(p); return; }
        if (!takeTicket(p)) return;
        String snapshot;
        try { snapshot = magic.snapshot(p); }
        catch (RuntimeException e) { refundTicket(p); fail(p, e); return; }
        if (!stash.store(p, snapshot)) { refundTicket(p); ChacaLink.notify(p, "error", "tower:enter", "들어가지 못했어요", "소지품을 맡기지 못했어요", 3000); return; }
        queue.remove(p.getUniqueId());
        offered = null;
        // 맡긴 뒤: 소지품 비우고 마법·지능 초기 상태
        p.closeInventory();
        p.getInventory().clear();
        for (var e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setLevel(0); p.setExp(0);
        try { magic.enterTower(p); }
        catch (RuntimeException e) {
            plugin.getLogger().severe("탑 마법 초기화 실패 " + p.getName() + ": " + e);
            stash.restore(p);
            try { magic.restore(p, snapshot); } catch (RuntimeException ignored) {}
            refundTicket(p);
            ChacaLink.notify(p, "error", "tower:enter", "들어가지 못했어요", "마법 상태를 바꾸지 못했어요", 3000);
            return;
        }
        run = new Run(p.getUniqueId());
        runner = p;
        run.rerolls = s.rerolls;
        var start = s.start.location();
        p.teleport(start);
        fullHeal(p);
        p.setFoodLevel(20); p.setSaturation(10);
        p.getInventory().addItem(new ItemStack(Material.BREAD, 6));
        p.showTitle(Title.title(Component.text(s.name, NamedTextColor.LIGHT_PURPLE), Component.text("시작 마법을 골라요", NamedTextColor.GRAY)));
        // 시작 카드 (기본 공격 마법)
        run.firstPick = true;
        offerCards(p, -1);
    }

    private void joinQueue(Player p) {
        if (!queue.contains(p.getUniqueId())) queue.add(p.getUniqueId());
        int n = queue.indexOf(p.getUniqueId()) + 1;
        ChacaLink.notify(p, "system", "tower:queue", "대기열에 들어갔어요", n + "번째 · 자리가 나면 알려 드려요 (/탑 대기취소)", 1000);
    }

    void leaveQueue(Player p) {
        boolean was = queue.remove(p.getUniqueId());
        if (p.getUniqueId().equals(offered)) offered = null;
        ChacaLink.notify(p, "system", "tower:queue", was ? "대기를 취소했어요" : "대기 중이 아니에요", "", 1000);
    }

    private boolean takeTicket(Player p) {
        if (s.ticketAmount <= 0) return true;
        int have = 0;
        for (var it : p.getInventory().getContents()) if (it != null && s.ticketItem.equals(items.idOf(it))) have += it.getAmount();
        if (have < s.ticketAmount) {
            ChacaLink.notify(p, "warning", "tower:ticket", "던전 입장권이 필요해요", "입장권 " + s.ticketAmount + "장 (가진 것 " + have + "장)", 2000);
            return false;
        }
        int need = s.ticketAmount;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && need > 0; i++) {
            var it = inv.getItem(i);
            if (it == null || !s.ticketItem.equals(items.idOf(it))) continue;
            int take = Math.min(need, it.getAmount());
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
            need -= take;
        }
        return true;
    }

    private void refundTicket(Player p) {
        if (s.ticketAmount <= 0) return;
        var it = items.create(s.ticketItem, s.ticketAmount);
        if (it != null) give(p, it);
    }

    // ───────────────────── 카드 ─────────────────────

    private void offerCards(Player p, int minGrade) {
        run.offer = Cards.roll(s, magic, run, run.firstPick, minGrade);
        if (run.luck > 0 && !run.firstPick) run.luck--;
        if (run.offer.isEmpty()) { afterPicks(p); return; }
        run.phase = Run.Phase.CARD;
        run.phaseEndsAt = System.currentTimeMillis() + s.cardSeconds * 1000L;
        currentMinGrade = minGrade;
        if (!client.cards(p, s, run)) new CardMenu(s, run).open(p);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1, 1.2f);
    }

    private int currentMinGrade = -1;

    void menuClick(Player p, int choice) {
        if (!inRun(p) || run.phase != Run.Phase.CARD) { p.closeInventory(); client.close(p); return; }
        if (choice == -2) {
            if (run.rerolls <= 0) return;
            run.rerolls--;
            run.offer = Cards.roll(s, magic, run, run.firstPick, currentMinGrade);
            if (!client.cards(p, s, run)) new CardMenu(s, run).open(p);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_EYE_DEATH, 1, 1.4f);
            return;
        }
        if (choice < 0 || choice >= run.offer.size()) return;
        pick(p, run.offer.get(choice));
    }

    /** 창을 닫으면 다음 틱에 다시 연다 (고르기 전에는 진행 안 됨). */
    void menuClosed(Player p) {
        if (!inRun(p) || run.phase != Run.Phase.CARD) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (inRun(p) && run.phase == Run.Phase.CARD && !(p.getOpenInventory().getTopInventory().getHolder() instanceof CardMenu))
                new CardMenu(s, run).open(p);
        }, 2L);
    }

    private void pick(Player p, Cards.Card c) {
        run.offer = List.of();
        run.phase = Run.Phase.COUNTDOWN; // 닫힘 이벤트가 다시 열지 않게
        p.closeInventory();
        client.close(p);
        try { applyCard(p, c); }
        catch (RuntimeException e) { plugin.getLogger().warning("카드 적용 실패 " + c + ": " + e); }
        run.took(c.type().key());
        var g = s.grades.get(c.grade());
        ChacaLink.notify(p, "shop", "tower:card", "[" + g.name() + "] " + (c.type() == Cards.Type.SPELL || c.type() == Cards.Type.ARTIFACT ? c.spellName() : c.type().title),
                String.join(" ", Cards.describe(c)), 0);
        p.playSound(p.getLocation(), c.grade() >= 3 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1, 1.1f);
        run.firstPick = false;
        afterPicks(p);
    }

    /** 남은 고르기가 있으면 다음 카드, 없으면 다음 층. */
    private void afterPicks(Player p) {
        if (!run.picks.isEmpty()) { offerCards(p, run.picks.poll()); return; }
        startCountdown(p);
    }

    private void applyCard(Player p, Cards.Card c) {
        double v = c.value();
        int g = c.grade();
        switch (c.type()) {
            case SPELL -> { if (magic.learn(p, c.spell())) run.spells.add(c.spell()); }
            case INTELLIGENCE -> { run.intelligence += (int) v; magic.addIntelligence(p, (int) v); }
            case HEALTH -> { run.maxHealthBonus += v; applyAttributes(p); p.setHealth(Math.min(maxHealth(p), p.getHealth() + v)); }
            case HEAL -> heal(p, maxHealth(p) * v);
            case ARMOR -> giveArmor(p, g);
            case FOOD -> giveFood(p, g);
            case POTION -> givePotions(p, g);
            case ARTIFACT -> {
                run.relics.add(c.spell());
                switch (Cards.Relic.valueOf(c.spell())) {
                    case FANG -> run.lifesteal += 0.06;
                    case HEART -> { run.maxHealthBonus += 8; run.guard = Math.min(0.6, run.guard + 0.05); applyAttributes(p); heal(p, 8); }
                    default -> {}
                }
            }
            case POWER -> run.damageBonus += v;
            case GUARD -> run.guard = Math.min(0.6, run.guard + v);
            case LIFESTEAL -> run.lifesteal += v;
            case SWIFT -> { run.speedBonus += v; applyAttributes(p); }
            case VIGOR -> run.vigor += v;
            case HARVEST -> run.harvest += v;
            case FEATHER -> run.feathers++;
            case CURSED -> {
                run.damageBonus += v;
                run.maxHealthBonus -= g >= 3 ? 6 : 4;
                applyAttributes(p);
                if (p.getHealth() > maxHealth(p)) p.setHealth(maxHealth(p));
            }
            case LUCK -> run.luck += (int) v;
            case REROLL -> run.rerolls += (int) v;
            case TRIAL -> {
                run.trialLeft = 3;
                run.trialGrades.clear();
                switch (g) {
                    case 1 -> { run.trialGrades.add(2); run.trialGrades.add(2); }
                    case 2 -> { run.trialGrades.add(3); run.trialGrades.add(2); }
                    default -> { run.trialGrades.add(3); run.trialGrades.add(3); }
                }
            }
            case GAMBLE -> {
                run.gambleArmed = true;
                run.gambleHealth = g >= 2 ? 1.8 : 1.5;
                run.gamblePicks = g >= 3 ? 3 : 2;
            }
        }
    }

    private void giveArmor(Player p, int g) {
        var slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        var inv = p.getInventory();
        EquipmentSlot slot = null;
        for (var sl : slots) if (inv.getItem(sl).getType().isAir()) { slot = sl; break; }
        if (slot == null) {
            // 모두 있으면 가장 약한 부위
            int worst = 99;
            for (var sl : slots) { int t = armorTier(inv.getItem(sl).getType()); if (t < worst) { worst = t; slot = sl; } }
        }
        String mat = switch (g) { case 0 -> "IRON"; case 1, 2 -> "DIAMOND"; default -> "NETHERITE"; };
        String part = switch (slot) { case HEAD -> "_HELMET"; case CHEST -> "_CHESTPLATE"; case LEGS -> "_LEGGINGS"; default -> "_BOOTS"; };
        var item = new ItemStack(Material.valueOf(mat + part));
        if (g >= 2) item.addUnsafeEnchantment(Enchantment.PROTECTION, g >= 3 ? 3 : 2);
        item.editMeta(m -> m.setUnbreakable(true));
        var old = inv.getItem(slot);
        if (old.getType().isAir() || armorTier(old.getType()) <= armorTier(item.getType())) {
            inv.setItem(slot, item);
            if (!old.getType().isAir()) give(p, old);
        } else give(p, item);
    }

    private static int armorTier(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 5;
        if (n.startsWith("DIAMOND")) return 4;
        if (n.startsWith("IRON")) return 3;
        if (n.startsWith("CHAINMAIL") || n.startsWith("GOLDEN")) return 2;
        if (n.startsWith("LEATHER")) return 1;
        return 0;
    }

    private void giveFood(Player p, int g) {
        switch (g) {
            case 0 -> give(p, new ItemStack(Material.BREAD, 8));
            case 1 -> give(p, new ItemStack(Material.COOKED_BEEF, 12));
            case 2 -> give(p, new ItemStack(Material.GOLDEN_CARROT, 16));
            default -> { give(p, new ItemStack(Material.GOLDEN_APPLE, 4)); give(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1)); }
        }
    }

    private void givePotions(Player p, int g) {
        switch (g) {
            case 0 -> give(p, potion(Material.SPLASH_POTION, PotionType.HEALING, 2));
            case 1 -> { give(p, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 2)); give(p, potion(Material.POTION, PotionType.REGENERATION, 1)); }
            case 2 -> {
                give(p, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 3));
                give(p, potion(Material.POTION, PotionType.LONG_STRENGTH, 1));
                give(p, resistance(0));
            }
            default -> {
                give(p, potion(Material.SPLASH_POTION, PotionType.STRONG_HEALING, 4));
                give(p, potion(Material.POTION, PotionType.STRONG_STRENGTH, 1));
                give(p, resistance(1));
                give(p, potion(Material.POTION, PotionType.STRONG_REGENERATION, 1));
            }
        }
    }

    private static ItemStack potion(Material m, PotionType type, int amount) {
        var it = new ItemStack(m, amount);
        it.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
        return it;
    }

    private static ItemStack resistance(int amp) {
        var it = new ItemStack(Material.POTION);
        it.editMeta(PotionMeta.class, meta -> {
            meta.addCustomEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 90, amp), true);
            meta.setColor(org.bukkit.Color.fromRGB(0x9AA6B8));
            meta.displayName(Component.text("저항의 물약" + (amp > 0 ? " II" : ""), NamedTextColor.WHITE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        });
        return it;
    }

    private void give(Player p, ItemStack it) {
        for (var left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ───────────────────── 층 ─────────────────────

    private void startCountdown(Player p) {
        if (run.relics.contains("AEGIS")) {
            var cap = p.getAttribute(Attribute.MAX_ABSORPTION);
            if (cap != null && cap.getBaseValue() < 6) cap.setBaseValue(6);
            p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), 6));
        }
        run.phase = Run.Phase.COUNTDOWN;
        run.phaseEndsAt = System.currentTimeMillis() + s.floorStartSeconds * 1000L;
        run.waves.clear();
        run.waves.addAll(Floors.plan(s, run.floor));
        if (run.gambleArmed) { run.gambleArmed = false; run.gambleActive = true; }
        boolean guard = Floors.guardianFloor(s, run.floor);
        var sub = guard ? Component.text("수문장이 기다리고 있어요", NamedTextColor.RED)
                : run.gambleActive ? Component.text("도박사의 내기 — 몹이 더 단단해요", NamedTextColor.GOLD)
                : run.trialLeft > 0 ? Component.text("시련의 서약 " + (4 - run.trialLeft) + "/3", NamedTextColor.LIGHT_PURPLE)
                : Component.text(s.name, NamedTextColor.GRAY);
        p.showTitle(Title.title(Component.text(run.floor + "층", guard ? NamedTextColor.RED : NamedTextColor.WHITE), sub));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.3f);
    }

    private void spawnWave(Player p) {
        if (run.waves.isEmpty()) return;
        var wave = run.waves.removeFirst();
        var box = s.room();
        var w = s.start.location().getWorld();
        var spots = Floors.safeSpots(w, box, wave.size(), p.getLocation());
        int i = 0;
        for (var pick : wave) {
            if (i >= spots.size()) break;
            var at = spots.get(i++);
            Entity e = floors.spawn(pick.mob(), at);
            if (e == null) continue;
            var affixes = Floors.rollAffixes(s, run.floor, pick.guardian());
            floors.prepare(s, run.floor, e, pick, affixes);
            if (run.gambleActive && e instanceof LivingEntity le) scaleHealth(le, run.gambleHealth);
            if (run.relics.contains("HOURGLASS") && e instanceof LivingEntity hl) hl.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 6, false, false));
            run.mobs.add(e.getUniqueId());
            if (pick.guardian()) showGuardian(p, e);
            String tag = floors.affixText(s, e);
            if (!tag.isEmpty() && e instanceof LivingEntity le) {
                le.customName(Component.text("[" + tag + "] ", NamedTextColor.RED).append(le.customName() == null ? Component.text(pick.mob()) : le.customName()));
            }
            w.spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 30, .4, .8, .4, .3);
        }
        if (spots.size() < wave.size()) plugin.getLogger().warning("탑 " + run.floor + "층: 안전한 땅이 모자라 " + spots.size() + "/" + wave.size() + "마리만 소환");
        run.phase = Run.Phase.FIGHT;
        if (run.mobs.isEmpty()) floorCleared(p);
    }

    private static void scaleHealth(LivingEntity le, double mult) {
        var a = le.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        a.setBaseValue(Math.min(2048, a.getBaseValue() * mult));
        le.setHealth(a.getValue());
    }

    private void showGuardian(Player p, Entity e) {
        hideGuardian();
        guardian = e.getUniqueId();
        guardianBar = BossBar.bossBar(Component.text(run.floor + "층 수문장", NamedTextColor.RED), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        p.showBossBar(guardianBar);
    }

    private void hideGuardian() {
        if (guardianBar != null && runner != null) runner.hideBossBar(guardianBar);
        guardianBar = null;
        guardian = null;
    }

    private void floorCleared(Player p) {
        hideGuardian();
        if (run.vigor > 0) heal(p, maxHealth(p) * run.vigor);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        if (run.floor >= s.floors) { end(p, "clear"); return; }
        boolean gamble = run.gambleActive;
        run.gambleActive = false;
        run.gambleHealth = 1;
        run.floor++;
        run.picks.clear();
        if (run.trialLeft > 0) {
            run.trialLeft--;
            if (run.trialLeft > 0) {
                ChacaLink.notify(p, "system", "tower:trial", "시련의 서약 " + (3 - run.trialLeft) + "/3", "이번 층은 카드가 없어요", 0);
                startLater(p);
                return;
            }
            ChacaLink.notify(p, "shop", "tower:trial", "시련을 이겨 냈어요", Cards.trialText(run.trialGrades.getFirst() >= 3 && run.trialGrades.getLast() >= 3 ? 3 : run.trialGrades.getFirst()) + "를 골라요", 0);
            run.picks.addAll(run.trialGrades);
            run.trialGrades.clear();
        } else if (gamble) {
            ChacaLink.notify(p, "shop", "tower:gamble", "내기에서 이겼어요", "카드를 " + run.gamblePicks + "번 골라요", 0);
            for (int i = 0; i < run.gamblePicks; i++) run.picks.add(-1);
        } else run.picks.add(-1);
        int first = run.picks.poll();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inRun(p)) return;
            offerCards(p, first);
        }, Math.max(1, s.cardDelaySeconds * 20L));
        run.phase = Run.Phase.CARD;
        run.phaseEndsAt = System.currentTimeMillis() + (s.cardDelaySeconds + s.cardSeconds) * 1000L;
    }

    private void startLater(Player p) {
        run.phase = Run.Phase.CARD; // 잠깐 쉬는 동안 몹 검사 안 함
        run.phaseEndsAt = Long.MAX_VALUE;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (inRun(p)) startCountdown(p); }, 40L);
    }

    // ───────────────────── 매 0.5초 ─────────────────────

    void tick() {
        tickQueue();
        if (run == null || run.phase == Run.Phase.ENDED) return;
        Player p = Bukkit.getPlayer(run.player);
        if (p == null) { endOffline(); return; }
        runner = p;
        long now = System.currentTimeMillis();
        switch (run.phase) {
            case COUNTDOWN -> { if (now >= run.phaseEndsAt) spawnWave(p); }
            case FIGHT -> {
                run.mobs.removeIf(id -> { var e = Bukkit.getEntity(id); return e == null || !e.isValid() || (e instanceof LivingEntity le && le.isDead()); });
                tickAffixes();
                if (run.mobs.isEmpty()) {
                    if (!run.waves.isEmpty()) {
                        run.phase = Run.Phase.COUNTDOWN;
                        run.phaseEndsAt = now + 1500;
                        p.sendActionBar(Component.text("다음 무리가 와요", NamedTextColor.YELLOW));
                    } else floorCleared(p);
                    return;
                }
                p.sendActionBar(Component.text(run.floor + "층 · 남은 몹 " + run.mobs.size() + (run.waves.isEmpty() ? "" : " · 남은 무리 " + run.waves.size()), NamedTextColor.WHITE));
            }
            case CARD -> {
                if (now >= run.phaseEndsAt && !run.offer.isEmpty()) pick(p, run.offer.getFirst());
            }
            default -> {}
        }
        if (guardianBar != null && guardian != null) {
            var g = Bukkit.getEntity(guardian);
            if (g instanceof LivingEntity le && !le.isDead()) {
                var max = le.getAttribute(Attribute.MAX_HEALTH);
                guardianBar.progress((float) Math.clamp(le.getHealth() / (max == null ? 1 : max.getValue()), 0, 1));
            } else hideGuardian();
        }
        // 방 밖 검사
        var box = s.room();
        if (box != null && !(p.getWorld().getName().equals(s.start.world()) && box.contains(p.getLocation()))) {
            if (run.outsideSince == 0) run.outsideSince = now;
            else if (now - run.outsideSince > s.leaveGraceSeconds * 1000L) { end(p, "left"); return; }
            ChacaLink.notify(p, "warning", "tower:outside", "방을 벗어났어요", "돌아오지 않으면 탑이 끝나요", 2000);
        } else run.outsideSince = 0;
    }

    private void tickAffixes() {
        for (var id : run.mobs) {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity le) || le.isDead()) continue;
            var max = le.getAttribute(Attribute.MAX_HEALTH);
            if (max == null) continue;
            if (floors.has(le, "regen") && le.getHealth() < max.getValue()) le.setHealth(Math.min(max.getValue(), le.getHealth() + max.getValue() * 0.01));
            if (floors.has(le, "frenzy") && !le.getScoreboardTags().contains("tower_frenzied") && le.getHealth() < max.getValue() * 0.35) {
                le.addScoreboardTag("tower_frenzied");
                var sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
                if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.3);
                le.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, le.getLocation().add(0, le.getHeight(), 0), 6, .4, .3, .4);
            }
        }
    }

    private void tickQueue() {
        long now = System.currentTimeMillis();
        if (offered != null && now >= offerUntil) {
            var p = Bukkit.getPlayer(offered);
            queue.remove(offered);
            if (p != null) ChacaLink.notify(p, "warning", "tower:queue", "입장 시간이 지나 대기가 취소됐어요", "", 0);
            offered = null;
        }
        queue.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (offered == null && (run == null || run.phase == Run.Phase.ENDED) && !queue.isEmpty()) {
            offered = queue.getFirst();
            offerUntil = now + s.queueOfferSeconds * 1000L;
            var p = Bukkit.getPlayer(offered);
            if (p != null) {
                ChacaLink.notify(p, "system", "tower:queue", "탑에 자리가 났어요", s.queueOfferSeconds + "초 안에 /탑 입장", 0);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1, 1.5f);
            }
        }
    }

    // ───────────────────── 전투 ─────────────────────

    void mobHitsPlayer(EntityDamageByEntityEvent e, Player victim, Entity mob) {
        if (!inRun(victim)) return;
        double dmg = e.getDamage() * floors.damageMult(mob);
        if (mob.getScoreboardTags().contains("tower_frenzied")) dmg *= 1.4;
        dmg *= 1 - run.guard;
        e.setDamage(dmg);
        if (floors.has(mob, "vampire") && mob instanceof LivingEntity le) {
            var max = le.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) le.setHealth(Math.min(max.getValue(), le.getHealth() + dmg * 0.5));
        }
    }

    void playerHitsMob(EntityDamageByEntityEvent e, Player attacker, Entity mob) {
        if (!inRun(attacker)) return;
        // 탑 지능 기준으로: 원래 지능(마법봉·장신구 포함) P 대신 탑 지능 T 로 친 것처럼 (40 + 지능 비례, 전투 스펙 k×(40+지능) 와 같은 꼴)
        double real = magic.realPower(attacker);
        double tower = s.startIntelligence + run.intelligence;
        double dmg = e.getDamage() * (40 + tower) / (40 + Math.max(0, real)) * (1 + run.damageBonus);
        if (mob instanceof LivingEntity le) {
            var max = le.getAttribute(Attribute.MAX_HEALTH);
            if (run.relics.contains("SCYTHE") && max != null && le.getHealth() < max.getValue() * 0.3) dmg *= 1.5;
            if (run.relics.contains("FROST")) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, false));
            if (run.relics.contains("EMBER")) le.setFireTicks(Math.max(le.getFireTicks(), 60));
        }
        if (run.relics.contains("MASK") && attacker.getHealth() < maxHealth(attacker) * 0.5) dmg *= 1.35;
        e.setDamage(dmg);
        if (run.lifesteal > 0) heal(attacker, dmg * run.lifesteal);
        if (floors.has(mob, "thorns")) {
            double back = dmg * 0.12;
            Bukkit.getScheduler().runTask(plugin, () -> { if (inRun(attacker) && attacker.getHealth() > back + 0.5) attacker.setHealth(attacker.getHealth() - back); });
        }
    }

    void fatal(EntityDamageEvent e, Player p) {
        if (run.feathers > 0) {
            run.feathers--;
            e.setCancelled(true);
            p.setHealth(Math.max(1, maxHealth(p) * 0.5));
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, .5, .8, .5, .4);
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1, 1);
            ChacaLink.notify(p, "system", "tower:feather", "불사조의 깃털이 타올랐어요", "남은 깃털 " + run.feathers + "개", 0);
            return;
        }
        e.setCancelled(true);
        end(p, "death");
    }

    void mobDied(Entity e) {
        if (run == null || !run.mobs.remove(e.getUniqueId())) return;
        if (floors.kind(e).equals("guardian")) rewards.guardian(run, run.floor);
        Player p = runner;
        if (p != null && run.harvest > 0) heal(p, run.harvest);
        if (run.relics.contains("ECHO") && e instanceof LivingEntity dead) {
            var max = dead.getAttribute(Attribute.MAX_HEALTH);
            double blow = (max == null ? 20 : max.getValue()) * 0.15;
            for (var near : e.getNearbyEntities(4, 2, 4))
                if (near instanceof LivingEntity le && run.mobs.contains(near.getUniqueId())) le.damage(blow);
            e.getWorld().spawnParticle(Particle.SONIC_BOOM, e.getLocation().add(0, 1, 0), 1);
        }
        if (floors.has(e, "split") && !e.getScoreboardTags().contains("tower_split_child")) {
            for (int i = 0; i < 2 && !s.normalMobs.isEmpty(); i++) {
                var at = e.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
                var mob = s.normalMobs.get(ThreadLocalRandom.current().nextInt(s.normalMobs.size()));
                var child = floors.spawn(mob, at);
                if (child == null) continue;
                floors.prepare(s, run.floor, child, new Floors.Pick(mob, false, false), List.of());
                child.addScoreboardTag("tower_split_child");
                if (child instanceof LivingEntity le) scaleHealth(le, 0.4);
                run.mobs.add(child.getUniqueId());
            }
        }
        if (floors.has(e, "blast")) {
            var at = e.getLocation().clone();
            double dmg = s.bal("normal-damage", 4) * Floors.damageMult(s, run.floor) * 1.5;
            at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, .5, 0), 40, 1.5, .2, 1.5, .02);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1, 1);
                if (p != null && inRun(p) && p.getWorld().equals(at.getWorld()) && p.getLocation().distance(at) < 3.5) p.damage(dmg * (1 - run.guard));
            }, 30L);
        }
    }

    // ───────────────────── 체력·속도 ─────────────────────

    private double maxHealth(Player p) { var a = p.getAttribute(Attribute.MAX_HEALTH); return a == null ? 20 : a.getValue(); }

    private void heal(Player p, double amount) { if (amount > 0 && !p.isDead()) p.setHealth(Math.min(maxHealth(p), p.getHealth() + amount)); }

    private void fullHeal(Player p) { p.setHealth(maxHealth(p)); }

    private void applyAttributes(Player p) {
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.removeModifier(hpKey);
            double base = hp.getValue();
            double bonus = Math.max(run.maxHealthBonus, 2 - base);
            if (Math.abs(bonus) > 1e-6) hp.addModifier(new AttributeModifier(hpKey, bonus, AttributeModifier.Operation.ADD_NUMBER));
        }
        var sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) {
            sp.removeModifier(speedKey);
            if (run.speedBonus > 0) sp.addModifier(new AttributeModifier(speedKey, run.speedBonus, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    private void clearAttributes(Player p) {
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.removeModifier(hpKey);
        var sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.removeModifier(speedKey);
        var ab = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (ab != null && ab.getBaseValue() == 6) { ab.setBaseValue(0); p.setAbsorptionAmount(0); }
    }

    // ───────────────────── 끝 ─────────────────────

    /** reason: death 쓰러짐 · quit 포기 · left 방 이탈 · clear 꼭대기 · offline 접속 끊김 · admin 관리자 · shutdown 서버 꺼짐 */
    void end(Player p, String reason) {
        if (run == null || run.phase == Run.Phase.ENDED) return;
        Run r = run;
        r.phase = Run.Phase.ENDED;
        hideGuardian();
        removeMobs(r);
        client.close(p);
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof CardMenu) p.closeInventory();
        int cleared = reason.equals("clear") ? s.floors : r.floor - 1;
        giveBack(p);
        var exit = s.exit != null ? s.exit.location() : s.portal != null ? s.portal.location() : null;
        if (exit != null && !reason.equals("shutdown")) p.teleport(exit);
        String head = switch (reason) {
            case "clear" -> "탑의 꼭대기에 올랐어요";
            case "death" -> "쓰러졌어요";
            case "left" -> "방을 벗어나 탑이 끝났어요";
            case "quit" -> "탑을 포기했어요";
            default -> "탑이 끝났어요";
        };
        long ms = r.elapsedMs();
        records.finish(r, cleared, reason, cardsText(r)).thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
            bestCache.remove(r.player);
            topAt = 0;
            var online = Bukkit.getPlayer(r.player);
            rewards.finish(r.player, r, cleared, res.before().floor());
            rewards.guardianAfter(r.player, online, r);
            if (online == null) return;
            String body = cleared + "층까지 깼어요 · " + time(ms) + (res.newBest() ? " · 새 최고 기록" : res.before().floor() > 0 ? " · 최고 " + res.before().floor() + "층" : "");
            ChacaLink.notify(online, reason.equals("clear") ? "shop" : "system", "tower:end", head, body, 0);
            online.showTitle(Title.title(Component.text(head, reason.equals("clear") ? NamedTextColor.GOLD : NamedTextColor.WHITE), Component.text(body, NamedTextColor.GRAY)));
        }));
        run = null;
        runner = null;
    }

    private void endOffline() {
        Run r = run;
        r.phase = Run.Phase.ENDED;
        hideGuardian();
        removeMobs(r);
        records.finish(r, r.floor - 1, "offline", cardsText(r));
        run = null;
        runner = null;
    }

    private void removeMobs(Run r) {
        for (var id : r.mobs) { var e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        r.mobs.clear();
        // 분열 등으로 놓친 것까지: 방 안의 탑 몹
        var box = s.room();
        var start = s.start == null ? null : s.start.location();
        if (box != null && start != null)
            for (var e : start.getWorld().getEntities())
                if (e.getScoreboardTags().contains("chaca_tower") && box.contains(e.getLocation())) e.remove();
    }

    /** 맡긴 것 돌려주기 + 탑 능력 지우기. */
    private void giveBack(Player p) {
        clearAttributes(p);
        p.closeInventory();
        String magicState = stash.magicState(p.getUniqueId());
        try { magic.restore(p, magicState); }
        catch (RuntimeException e) { plugin.getLogger().severe("마법 되돌리기 실패 " + p.getName() + " (맡긴 파일 확인): " + e); }
        if (!stash.restore(p)) plugin.getLogger().severe("소지품 되돌리기 실패 " + p.getName());
        fullHeal(p);
    }

    /** 지난번에 못 돌려준 것 (서버가 꺼졌거나 접속이 끊김). */
    private void restoreLeftover(Player p) {
        if (inRun(p)) return;
        giveBack(p);
        ChacaLink.notify(p, "system", "tower:restore", "탑에 맡겼던 소지품을 돌려받았어요", "", 0);
    }

    void quit(Player p) {
        queue.remove(p.getUniqueId());
        if (p.getUniqueId().equals(offered)) offered = null;
        if (inRun(p)) end(p, "offline");
    }

    void join(Player p) {
        clearAttributes(p);
        if (stash.has(p.getUniqueId()) && !inRun(p)) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) restoreLeftover(p); }, 40L);
    }

    void changedWorld(Player p) {
        if (inRun(p) && !p.getWorld().getName().equals(s.start.world())) end(p, "left");
    }

    void teleported(Player p, Location to) {
        if (!inRun(p) || to == null) return;
        var box = s.room();
        if (box != null && !(to.getWorld().getName().equals(s.start.world()) && box.contains(to)))
            Bukkit.getScheduler().runTask(plugin, () -> { if (inRun(p)) end(p, "left"); });
    }

    /** 입구에 다가가면 안내 (자동 입장은 안 함 — 입장권을 잘못 쓰지 않게). */
    void moved(Player p, Location to) {
        if (s == null || s.portal == null || inRun(p)) return;
        if (!to.getWorld().getName().equals(s.portal.world())) return;
        var portal = s.portal.location();
        if (portal == null || to.distanceSquared(portal) > s.portalRadius * s.portalRadius) return;
        long now = System.currentTimeMillis();
        Long last = portalNotice.get(p.getUniqueId());
        if (last != null && now - last < 8000) return;
        portalNotice.put(p.getUniqueId(), now);
        records.best(p.getUniqueId()).thenAccept(b -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            ChacaLink.notify(p, "system", "tower:portal", s.name + " — /탑 입장",
                    "입장권 " + s.ticketAmount + "장 · 마법과 소지품은 맡기고 들어가요" + (b.floor() > 0 ? " · 내 최고 " + b.floor() + "층" : ""), 0);
        }));
    }

    void shutdown() {
        if (run != null && runner != null && runner.isOnline()) end(runner, "shutdown");
    }

    private static String cardsText(Run r) {
        var sb = new StringBuilder();
        r.taken.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append(k).append('x').append(v));
        if (!r.spells.isEmpty()) sb.append(";spells=").append(String.join(",", r.spells));
        return sb.toString();
    }

    static String time(long ms) {
        long sec = ms / 1000;
        return sec >= 3600 ? (sec / 3600) + "시간 " + (sec % 3600 / 60) + "분" : (sec / 60) + "분 " + (sec % 60) + "초";
    }

    private void fail(Player p, RuntimeException e) {
        plugin.getLogger().warning("탑 입장 실패 " + p.getName() + ": " + e);
        ChacaLink.notify(p, "error", "tower:enter", "들어가지 못했어요", "잠시 뒤에 다시 시도해 주세요", 3000);
    }

    // ───────────────────── 던전 입장창 (TowerApi) ─────────────────────

    private final Map<UUID, Records.Best> bestCache = new HashMap<>();
    private List<Records.Rank> topCache = List.of();
    private long topAt;

    Object[] entryRewards(Player p) {
        var best = bestCache.getOrDefault(p.getUniqueId(), Records.Best.NONE).floor();
        return new Object[]{bytes(rewards.tierPreview()), bytes(rewards.firstPreview(best)), bytes(rewards.guardianPreview()), (int) Math.round(rewards.guardianChance)};
    }

    private static List<byte[]> bytes(List<ItemStack> items) { return items.stream().map(ItemStack::serializeAsBytes).toList(); }

    /** 버튼 번호는 ChacaDungeon DungeonProtocol: 0 입장 · 1 대기 등록 · 2 대기 중 · 3 잠김 · 4 진행 중 · 5 입장 준비. */
    Object[] entryInfo(Player p) {
        UUID id = p.getUniqueId();
        int button; String lock = "";
        boolean busy = run != null && run.phase != Run.Phase.ENDED;
        if (inRun(p)) button = 4;
        else if (!s.missing().isEmpty() || !magic.ready()) { button = 3; lock = "탑을 준비하고 있어요"; }
        else if (id.equals(offered)) button = 5;
        else if (queue.contains(id)) button = 2;
        else if (busy || (offered != null) || !queue.isEmpty()) button = 1;
        else button = 0;
        if (!bestCache.containsKey(id)) { bestCache.put(id, Records.Best.NONE); records.best(id).thenAccept(b -> Bukkit.getScheduler().runTask(plugin, () -> bestCache.put(id, b))); }
        long now = System.currentTimeMillis();
        if (now - topAt > 60_000) { topAt = now; records.top(1).thenAccept(t -> Bukkit.getScheduler().runTask(plugin, () -> topCache = t)); }
        var mine = bestCache.get(id);
        var line = new StringBuilder("내 최고 ").append(mine.floor() > 0 ? mine.floor() + "층" : "기록 없음");
        if (!topCache.isEmpty()) line.append(" · 서버 최고 ").append(topCache.getFirst().floor()).append("층 ").append(ChacaLink.name(topCache.getFirst().player()));
        if (queue.contains(id)) line.append(" · 대기 ").append(queue.indexOf(id) + 1).append("번째");
        return new Object[]{button, lock, line.toString(), busy ? 1 : 0, 1};
    }

    // ───────────────────── 관리 ─────────────────────

    /** /탑관리 층 <n>: 지금 판을 그 층으로 (시험용). */
    void jumpFloor(Player p, int floor) {
        if (!inRun(p)) return;
        removeMobs(run);
        run.floor = Math.clamp(floor, 1, s.floors);
        run.waves.clear();
        run.picks.clear();
        p.closeInventory();
        client.close(p);
        startCountdown(p);
    }

    String status() {
        if (run == null) return "비어 있음 · 대기 " + queue.size() + "명";
        return ChacaLink.name(run.player) + " " + run.floor + "층 (" + run.phase + ") · 몹 " + run.mobs.size() + " · 대기 " + queue.size() + "명";
    }

    Set<String> knownMobs() { return new LinkedHashSet<>(s.normalMobs); }
}
