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
    private final NamespacedKey hpKey, speedKey, absKey;
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
    /** 탑 안에서 써도 되는 명령 (그 밖은 전부 막음: 배낭·우편·상점·귀환 등 소지품이 바뀌거나 자리를 옮기는 것). 프록시 명령(파티·귓속말)은 여기까지 오지 않는다. */
    /* "마법" = 마법 시전 명령 (mana-spells.yml 의 command 464개가 전부 /마법 <id>). 빠져 있으면 탑 안에서 마법이 하나도 안 나간다 (11/12 점검).
       어떤 마법이 나가는지는 권한 덧붙임이 막는다. /교화 는 일부러 막아 둔다. */
    private static final Set<String> ALLOWED = Set.of("마법", "탑", "tower", "친구", "채팅", "대화기록", "의뢰기록", "화면", "자동시전", "서클", "도움말", "help",
            "귓속말", "w", "msg", "tell", "r", "reply", "답장", "파티", "p", "전체");

    TowerService(TowerPlugin plugin, Magic magic, Stash stash, Records records, ItemsAdderItems items) {
        this.plugin = plugin; this.magic = magic; this.stash = stash; this.records = records; this.items = items;
        this.floors = new Floors(plugin);
        this.client = new TowerClient(plugin);
        this.hpKey = new NamespacedKey(plugin, "tower_hp");
        this.speedKey = new NamespacedKey(plugin, "tower_speed");
        this.absKey = new NamespacedKey(plugin, "tower_abs");
        this.rewards = new Rewards(plugin);
    }

    void apply(Settings next) { s = next; }

    boolean inRun(Player p) { return run != null && run.phase != Run.Phase.ENDED && run.player.equals(p.getUniqueId()); }

    boolean blockedCommand(String cmd) { return !ALLOWED.contains(cmd); }

    Run current() { return run; }

    TowerPlugin plugin() { return plugin; }

    // ───────────────────── 입장 ─────────────────────

    /** /탑 입장 (또는 대기열 순서가 와서). */
    void enter(Player p) {
        if (inRun(p)) { ChacaLink.notify(p, "warning", "tower:enter", "이미 탑에 있어요", "", 1000); return; }
        if (stash.has(p.getUniqueId())) {
            restoreLeftover(p);
            // 지난번에 맡긴 것을 아직 못 돌려줬으면 들어가지 않는다 (새로 맡기면 그 기록을 덮어써 원래 물건이 영영 사라짐, 11/12 점검)
            if (stash.has(p.getUniqueId())) { ChacaLink.notify(p, "error", "tower:enter", "지금은 들어갈 수 없어요", "지난번에 맡긴 소지품을 먼저 돌려받아야 해요. 관리자에게 알려 주세요", 4000); return; }
        }
        var missing = s.missing();
        if (!missing.isEmpty()) { ChacaLink.notify(p, "error", "tower:enter", "탑이 아직 준비되지 않았어요", "관리자에게 알려 주세요", 3000); plugin.getLogger().warning("탑 설정 부족: " + missing); return; }
        if (!magic.ready()) { ChacaLink.notify(p, "error", "tower:enter", "지금은 들어갈 수 없어요", "잠시 뒤에 다시 시도해 주세요", 3000); return; }
        if (run != null && run.phase != Run.Phase.ENDED) { joinQueue(p); return; }
        if (offered != null && !offered.equals(p.getUniqueId()) && System.currentTimeMillis() < offerUntil) { joinQueue(p); return; }
        if (!queue.isEmpty() && !queue.getFirst().equals(p.getUniqueId()) && offered == null) { joinQueue(p); return; }
        // 열린 창·커서·제작칸의 물건을 먼저 가방으로 (맡긴 뒤에 닫으면 그 물건은 기록에 없어 사라짐, 11/12 점검)
        p.closeInventory();
        var cursor = p.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            p.setItemOnCursor(null);
            for (var left : p.getInventory().addItem(cursor).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
        if (!takeTicket(p)) return;
        String snapshot;
        try { snapshot = magic.snapshot(p); }
        catch (RuntimeException e) { refundTicket(p); fail(p, e); return; }
        if (!stash.store(p, snapshot)) { refundTicket(p); ChacaLink.notify(p, "error", "tower:enter", "들어가지 못했어요", "소지품을 맡기지 못했어요", 3000); return; }
        activeRunner = p.getUniqueId();   // 여기부터 판이 끝날 때까지 HuskSync 저장을 막는다 (탑 소지품이 공용 DB 에 올라가지 않게)
        queue.remove(p.getUniqueId());
        offered = null;
        // 맡긴 뒤: 소지품 비우고 마법·지능 초기 상태
        p.closeInventory();
        p.getInventory().clear();
        for (var e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setLevel(0); p.setExp(0);
        p.setAbsorptionAmount(0); p.setFireTicks(0); p.setFreezeTicks(0); p.setArrowsInBody(0); p.setFallDistance(0);
        p.setRemainingAir(p.getMaximumAir()); p.setExhaustion(0);
        try { magic.dismissPets(p); magic.rememberLearned(p); magic.enterTower(p); }
        catch (RuntimeException e) {
            activeRunner = null;
            plugin.getLogger().severe("탑 마법 초기화 실패 " + p.getName() + ": " + e);
            boolean restored = stash.restore(p);
            try { magic.restore(p, snapshot); } catch (RuntimeException ignored) {}
            if (restored) refundTicket(p);   // 못 돌려놨는데 입장권만 넣으면 다음 복구가 "진짜 소지품 있음" 으로 잘못 봄
            else plugin.getLogger().severe("입장 실패 뒤 소지품 되돌리기 실패 " + p.getName() + " — 맡김 파일 남음, 입장권 1장 수동 지급 필요");
            ChacaLink.notify(p, "error", "tower:enter", "들어가지 못했어요", "마법 상태를 바꾸지 못했어요", 3000);
            return;
        }
        run = new Run(p.getUniqueId());
        runner = p;
        run.rerolls = s.rerolls;
        // 서버가 꺼지는 순간에도 첫 도달 보상을 바로 줄 수 있게 지금 최고 기록을 미리 읽어 둔다
        Run started = run;
        records.best(p.getUniqueId()).thenAccept(b -> { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> started.bestAtStart = b.floor()); });
        var start = s.start.location();
        p.teleport(start);
        applyAttributes(p);
        pinMana(p);
        fullHeal(p);
        p.setFoodLevel(20); p.setSaturation(20);
        if (s.startPotions > 0) give(p, potion(Material.SPLASH_POTION, PotionType.HEALING, s.startPotions));
        p.showTitle(Title.title(Component.text(s.name, NamedTextColor.LIGHT_PURPLE), Component.text("시작 마법을 골라요", NamedTextColor.GRAY)));
        // 시작 카드 (기본 공격 마법)
        run.firstPick = true;
        run.startPicksLeft = s.startSpellPicks;
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
        // 진짜 입장권이므로 탑 물건 표시를 붙이지 않는다
        if (it != null) for (var left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ───────────────────── 카드 ─────────────────────

    private void offerCards(Player p, int minGrade) {
        run.offer = Cards.roll(s, magic, run, run.firstPick, minGrade);
        offerShownAt = System.currentTimeMillis();
        if (run.luck > 0 && !run.firstPick) run.luck--;
        if (run.offer.isEmpty()) { if (run.firstPick) { run.firstPick = false; run.startPicksLeft = 0; } afterPicks(p); return; }
        run.phase = Run.Phase.CARD;
        run.phaseEndsAt = System.currentTimeMillis() + s.cardSeconds * 1000L;
        currentMinGrade = minGrade;
        showCards(p);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1, 1.2f);
    }

    private int currentMinGrade = -1;
    /** 카드가 뜬 시각. 바로 뒤 0.4초 동안의 고르기는 무시한다 (앞 카드를 두 번 눌렀을 때 새 카드까지 골라지는 것 방지). */
    private long offerShownAt;

    /** 카드 화면: 모드가 있으면 모드 화면(보유 증강 목록도 같이 보냄), 없으면 상자 창. */
    private void showCards(Player p) { if (!client.cards(p, s, run)) new CardMenu(s, run).open(p); }

    // [기능:탑내증강 시작]
    /** /탑 증강: 지금까지 고른 증강을 모아 보는 창. 모드가 있으면 카드 그림 그대로, 없으면 상자 창. */
    void showBuild(Player p) {
        if (!inRun(p)) { ChacaLink.notify(p, "warning", "tower:build", "탑 안에서만 볼 수 있어요", "", 1000); return; }
        if (client.build(p, s, run)) return;
        new BuildMenu(s, run, 0, run.phase == Run.Phase.CARD && !run.offer.isEmpty()).open(p);
    }

    /** 내 증강 상자 창을 누름: 쪽 넘기기·돌아가기. */
    void buildClick(Player p, BuildMenu menu, int slot) {
        if (!inRun(p)) { p.closeInventory(); return; }
        int page = menu.pageFor(slot);
        if (page >= 0) { new BuildMenu(s, run, page, menu.fromCards).open(p); return; }
        if (slot == BuildMenu.BACK_SLOT) {
            if (run.phase == Run.Phase.CARD && !run.offer.isEmpty()) new CardMenu(s, run).open(p);
            else p.closeInventory();
        }
    }
    // [기능:탑내증강 끝]

    void menuClick(Player p, int choice) {
        if (!inRun(p) || run.phase != Run.Phase.CARD) { p.closeInventory(); client.close(p); return; }
        if (choice == -2) {
            if (run.rerolls <= 0) return;
            run.rerolls--;
            run.offer = Cards.roll(s, magic, run, run.firstPick, currentMinGrade);
            showCards(p);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_EYE_DEATH, 1, 1.4f);
            return;
        }
        if (choice == -3) { new BuildMenu(s, run, 0, true).open(p); return; }   // 상자 창의 "내 증강" 버튼
        if (choice < 0 || choice >= run.offer.size()) return;
        if (System.currentTimeMillis() - offerShownAt < 400) return;
        pick(p, run.offer.get(choice));
    }

    /** 창을 닫으면 다음 틱에 다시 연다 (고르기 전에는 진행 안 됨). */
    void menuClosed(Player p) {
        if (!inRun(p) || run.phase != Run.Phase.CARD) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inRun(p) || run.phase != Run.Phase.CARD || run.offer.isEmpty() || TowerClient.modded(p)) return;
            var holder = p.getOpenInventory().getTopInventory().getHolder();
            if (!(holder instanceof CardMenu) && !(holder instanceof BuildMenu)) new CardMenu(s, run).open(p);   // 내 증강 창을 보는 중이면 그대로 둔다 (닫으면 다시 카드)
        }, 2L);
    }

    private void pick(Player p, Cards.Card c) {
        run.offer = List.of();
        run.phase = Run.Phase.COUNTDOWN; // 닫힘 이벤트가 다시 열지 않게
        p.closeInventory();
        client.close(p);
        try { applyCard(p, c); }
        catch (RuntimeException e) { plugin.getLogger().warning("카드 적용 실패 " + c + ": " + e); }
        run.took(c.type().key(), c.grade(), c.type().values == null ? 0 : c.value());
        var g = s.grades.get(c.grade());
        ChacaLink.notify(p, "shop", "tower:card", "[" + g.name() + "] " + (c.type() == Cards.Type.SPELL || c.type() == Cards.Type.ARTIFACT ? c.spellName() : c.type().title),
                String.join(" ", Cards.describe(c)), 0);
        p.playSound(p.getLocation(), c.grade() >= 3 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1, 1.1f);
        if (run.firstPick && --run.startPicksLeft > 0) { offerCards(p, -1); return; }   // 시작 마법을 여러 개 고르는 설정
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
            case SPELL -> { if (magic.learn(p, c.spell())) { run.spells.add(c.spell()); run.spellNames.put(c.spell(), c.spellName()); run.spellGrades.put(c.spell(), g); } }
            case INTELLIGENCE -> { run.intelligence += (int) v; magic.addIntelligence(p, (int) v); }
            case HEALTH -> { run.maxHealthBonus += v; applyAttributes(p); p.setHealth(Math.min(maxHealth(p), p.getHealth() + v)); }
            case HEAL -> heal(p, maxHealth(p) * v);
            case ARMOR -> giveArmor(p, g);
            case FOOD -> giveFood(p, g);
            case POTION -> givePotions(p, g);
            case ARTIFACT -> {
                if (run.relics.add(c.spell())) { run.relicOrder.add(c.spell()); run.relicGrades.put(c.spell(), g); }
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
                run.cursedLoss += g >= 3 ? 6 : 4;
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
            // ── 11/12 새 증강 ──
            case CRIT -> run.crit = Math.min(1, run.crit + v);
            case CRITDMG -> run.critDamage += v;
            case VENGEANCE -> {
                long cd = Cards.Type.VENGEANCE_COOLDOWN[Math.clamp(g, 0, 3)] * 1000L;
                if (run.vengeMult <= 0) { run.vengeMult = v; run.vengeCooldownMs = cd; }
                else {   // 또 고르면: 더 높은 배수 +0.5, 더 짧은 대기 -5초 (15초 밑으로는 안 내려감)
                    run.vengeMult = Math.max(run.vengeMult, v) + 0.5;
                    run.vengeCooldownMs = Math.max(15_000, Math.min(run.vengeCooldownMs, cd) - 5_000);
                }
                run.vengeReadyAt = Math.min(run.vengeReadyAt, System.currentTimeMillis() + run.vengeCooldownMs);
            }
            case FIRSTSTRIKE -> run.firstStrike += v;
            case BERSERK -> run.berserk += v;
            case MOMENTUM -> run.momentum += v;
            case OVERFLOW -> run.overflow += v;
            case MANA -> { run.manaBonus += v; pinMana(p); magic.addMana(p, v); }
            case SIPHON -> run.siphon += v;
            case HASTE -> { run.haste += v; magic.setHaste(p, run.haste); }
            case RELOAD -> run.reload = Math.min(1, run.reload + v);
            case GLASS -> { run.damageBonus += v; run.glassTaken += 0.25; }
            case RETALIATE -> run.retaliate = Math.min(0.35, run.retaliate + v);
            case DODGE -> run.dodge = Math.min(0.45, run.dodge + v);
            case SECONDWIND -> run.secondWind += v;
            case BARRIER -> run.barrier += v;
            case CHAIN -> run.chain = Math.min(1, run.chain + v);
            case FINISHER -> run.finisher = Math.min(0.25, run.finisher + v);
            case HUNTER -> run.hunter += v;
            case COMBO -> run.combo += v;
            case DICE -> run.diceMax = run.diceMax <= 0 ? v : Math.max(run.diceMax, v) + 0.4;   // 또 고르면 윗값 +40%
            case PACT -> { run.damageBonus += v; run.pact = true; }
            case INTEREST -> run.interestRate += v;
            case SCHOLAR -> run.scholar += v;
            case OVERKILL -> run.overkill = Math.min(1, run.overkill + v);
            case LASTSTAND -> run.lastStand = Math.min(0.8, run.lastStand + v);
        }
    }

    /** 탑 안 마나: 시작값 + 마나 샘 증강. 초당 회복은 최대치와 같은 비율로 따라간다. */
    private void pinMana(Player p) {
        double max = s.startMana + run.manaBonus;
        magic.pinMana(p, max, s.startManaRegen * max / s.startMana);
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
        tag(item);   // 입는 칸에 바로 넣어도 탑 물건 표시 (없으면 끝날 때 "바깥 물건" 으로 우편에 감, 11/12 점검)
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
            case 0 -> give(p, new ItemStack(Material.GOLDEN_APPLE, 1));
            case 1 -> give(p, new ItemStack(Material.GOLDEN_APPLE, 2));
            case 2 -> give(p, new ItemStack(Material.GOLDEN_APPLE, 3));
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

    /** 탑이 준 물건 표시 (끝날 때 이 표시가 없는 물건은 바깥에서 들어온 것 → 가방·우편으로). */
    private final NamespacedKey towerItemKey = new NamespacedKey("chacatower", "tower_item");

    private void tag(ItemStack it) {
        it.editMeta(m -> m.getPersistentDataContainer().set(towerItemKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
    }

    boolean towerItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(towerItemKey);
    }

    private void give(Player p, ItemStack it) {
        tag(it);
        // 가방이 꽉 차면 넘치는 건 버린다 (바닥에 떨어뜨리면 탑 물건이 밖으로 샐 수 있음)
        p.getInventory().addItem(it);
    }

    // ───────────────────── 층 ─────────────────────

    private void startCountdown(Player p) {
        double shield = (run.relics.contains("AEGIS") ? 6 : 0) + run.barrier;   // 수호성의 방패 + 마력 보호막 증강
        if (shield > 0) {
            var cap = p.getAttribute(Attribute.MAX_ABSORPTION);
            if (cap != null) {
                var cur = cap.getModifier(absKey);
                if (cur == null || Math.abs(cur.getAmount() - shield) > 1e-6) {
                    if (cur != null) cap.removeModifier(absKey);
                    cap.addTransientModifier(new AttributeModifier(absKey, shield, AttributeModifier.Operation.ADD_NUMBER));   // 기본값을 바꾸면 플레이어 데이터에 남음 → 저장 안 되는 수정치로
                }
            }
            p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), shield));
        }
        run.lastDamagedAt = System.currentTimeMillis();
        run.momentumStacks = 0; run.momentumUntil = 0; run.comboTarget = null; run.comboStacks = 0; run.windUsed = 0;
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
            aggro(e, p);
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

    private void scaleHealth(LivingEntity le, double mult) { floors.setHealth(le, floors.effectiveHealth(le) * mult); }

    /**
     * 탑 몹은 바로 플레이어를 노린다. 야생 몹 55종 중 32종은 AITargetSelectors 가 hurtbytarget 뿐이라 (맞기 전엔 안 덤빔)
     * 그대로 두면 서 있기만 한다 (11/12 점검). 대상이 없어지면 tick 에서 다시 건다.
     */
    private static void aggro(Entity e, Player p) {
        if (e instanceof org.bukkit.entity.Mob m && (m.getTarget() == null || !m.getTarget().isValid())) m.setTarget(p);
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
        removeMobs(run);   // 늦게 불려 나온 졸개가 카드 고르는 동안 남아 있지 않게
        if (run.vigor > 0) heal(p, maxHealth(p) * Math.min(s.bal("vigor-cap", 0.5), run.vigor));
        if (run.interestRate > 0) run.interestBonus += run.interestRate;
        if (run.scholar > 0) { run.scholarCarry += run.scholar; int add = (int) Math.floor(run.scholarCarry + 1e-9); if (add > 0) { run.scholarCarry -= add; run.intelligence += add; magic.addIntelligence(p, add); } }
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
        if (++sweepTick >= 20) { sweepTick = 0; sweepTowerItems(); }
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
        if (run == null || run.phase == Run.Phase.ENDED) return;   // 이번 tick 에 판이 끝났으면 (마지막 층 클리어 등) 여기서 멈춤
        if (guardianBar != null && guardian != null) {
            var g = Bukkit.getEntity(guardian);
            if (g instanceof LivingEntity le && !le.isDead()) {
                var max = le.getAttribute(Attribute.MAX_HEALTH);
                guardianBar.progress((float) Math.clamp(le.getHealth() / (max == null ? 1 : max.getValue()), 0, 1));
            } else hideGuardian();
        }
        applyAttributes(p);
        magic.dismissPets(p);
        pinMana(p);
        magic.setHaste(p, run.haste);
        if (run.overflow > 0) { run.manaPrev = run.manaNow; run.manaNow = magic.manaFraction(p); }
        // 숨 고르기: 싸우는 중 3초 동안 안 맞으면 초당 1씩 회복, 층마다 증강 수치만큼까지 (몹 하나 남겨 두고 가득 채우는 걸 막음)
        if (run.secondWind > 0 && run.phase == Run.Phase.FIGHT && now - run.lastDamagedAt >= 3000 && run.windUsed < run.secondWind && p.getHealth() < maxHealth(p)) {
            double add = Math.min(0.5, Math.min(run.secondWind - run.windUsed, maxHealth(p) - p.getHealth()));
            run.windUsed += add;
            p.setHealth(p.getHealth() + add);
        }
        // 배고픔 무한 (자연 회복은 TowerListener 가 막는다)
        p.setFoodLevel(20); p.setSaturation(20); p.setExhaustion(0);
        // 방 밖 검사
        var box = s.room();
        if (box != null && !(p.getWorld().getName().equals(s.start.world()) && box.contains(p.getLocation()))) {
            if (run.outsideSince == 0) run.outsideSince = now;
            else if (now - run.outsideSince > s.leaveGraceSeconds * 1000L) { end(p, "left"); return; }
            ChacaLink.notify(p, "warning", "tower:outside", "방을 벗어났어요", "돌아오지 않으면 탑이 끝나요", 2000);
        } else run.outsideSince = 0;
    }
    private int sweepTick;

    /** 10초마다: 탑 밖에 있는 사람의 가방에 탑 표시가 붙은 물건이 있으면 지운다 (어떤 길로든 새어 나온 탑 물건이 남지 않게). */
    private void sweepTowerItems() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inRun(p)) continue;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) if (towerItem(inv.getItem(i))) { inv.setItem(i, null); plugin.getLogger().warning("탑 밖에서 탑 물건을 지움: " + p.getName()); }
        }
    }


    private void tickAffixes() {
        for (var id : run.mobs) {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity le) || le.isDead()) continue;
            if (runner != null) aggro(le, runner);
            var max = le.getAttribute(Attribute.MAX_HEALTH);
            if (max == null) continue;
            if (floors.has(le, "regen") && le.getHealth() < max.getValue()) le.setHealth(Math.min(max.getValue(), le.getHealth() + max.getValue() * 0.01));
            if (floors.has(le, "frenzy") && !le.getScoreboardTags().contains("tower_frenzied") && le.getHealth() < max.getValue() * 0.35) {
                le.addScoreboardTag("tower_frenzied");
                floors.speedUp(s, le, 1.3);
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
        if (e.getDamage() <= 0) return;   // 피해 0 = 다른 플러그인이 "때려도 되는지" 물어보는 신호 → 잔상·되갚기·가시 갑옷이 헛돌지 않게 (11/12 최종 점검)
        Run r = run;
        long now = System.currentTimeMillis();
        // 잔상: 통째로 피함
        if (r.dodge > 0 && ThreadLocalRandom.current().nextDouble() < Math.min(0.45, r.dodge)) {
            e.setCancelled(true);
            victim.getWorld().spawnParticle(Particle.CLOUD, victim.getLocation().add(0, 1, 0), 8, .3, .5, .3, .02);
            victim.playSound(victim.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.7f, 1.6f);
            victim.sendActionBar(Component.text("피했어요", NamedTextColor.AQUA));
            return;
        }
        double dmg = e.getDamage() * floors.damageMult(mob);
        if (mob.getScoreboardTags().contains("tower_frenzied")) dmg *= 1.4;
        dmg *= 1 - r.guard;
        dmg *= 1 + r.glassTaken;
        if (r.lastStand > 0 && victim.getHealth() <= maxHealth(victim) * 0.3) dmg *= 1 - Math.min(0.8, r.lastStand);
        e.setDamage(dmg);
        r.lastDamagedAt = now;
        // 되갚기: 맞으면 다음 공격이 준비됨 (재사용 대기가 끝났을 때만)
        if (r.vengeMult > 0 && !r.vengeArmed && now >= r.vengeReadyAt) {
            r.vengeArmed = true;
            victim.playSound(victim.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.8f);
            victim.sendActionBar(Component.text("되갚기 준비 — 다음 공격 " + Cards.num(r.vengeMult) + "배", NamedTextColor.GOLD));
        }
        // 가시 갑옷: 때린 몹 최대 체력의 일정 비율 (체력바 기준이라 층이 올라도 같은 몫)
        if (r.retaliate > 0 && mob instanceof LivingEntity attackerMob && r.mobs.contains(mob.getUniqueId())) {
            var mx = attackerMob.getAttribute(Attribute.MAX_HEALTH);
            double back = (mx == null ? 20 : mx.getValue()) * Math.min(0.35, r.retaliate) * (floors.kind(mob).equals("guardian") ? 1 / 3.0 : 1);
            Bukkit.getScheduler().runTask(plugin, () -> { if (attackerMob.isValid() && !attackerMob.isDead()) pierce(attackerMob, back); });
        }
        if (floors.has(mob, "vampire") && mob instanceof LivingEntity le) {
            var max = le.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) le.setHealth(Math.min(max.getValue(), le.getHealth() + dmg * 0.5));
        }
    }

    void playerHitsMob(EntityDamageByEntityEvent e, Player attacker, Entity mob) {
        if (!inRun(attacker)) return;
        if (e.getDamage() <= 0) return;   // 피해 0 신호 (마법 보호 검사) 에 되갚기·연타·피의 계약이 소모되지 않게 (11/12 최종 점검)
        // 탑 지능 기준으로: 원래 지능(마법봉·장신구 포함) P 대신 탑 지능 T 로 친 것처럼 (40 + 지능 비례, 전투 스펙 k×(40+지능) 와 같은 꼴)
        // 서버 피해 공식은 base + ratio × 마력 이고 마법 279종 평균 base/ratio ≈ 12 → 피해 ∝ (12 + 지능). 그래서 (power-offset + 탑 지능) / (power-offset + 원래 지능).
        // (예전 값 40 은 대인전 공식이라, 원래 지능이 높은 사람이 탑에서도 최대 1.6배 더 세게 나왔음 — 11/12 밸런스 점검)
        Run r = run;
        long now = System.currentTimeMillis();
        var rng = ThreadLocalRandom.current();
        double real = magic.realPower(attacker);
        double tower = s.startIntelligence + r.intelligence;
        double off = s.bal("power-offset", 12);
        double dmg = e.getDamage() * (off + tower) / (off + Math.max(0, real));
        dmg *= 1 + r.damageBonus + r.interestBonus;   // 더하기 묶음: 마력 증폭·저주받은 힘·유리 대포·피의 계약·복리
        LivingEntity le = mob instanceof LivingEntity l ? l : null;
        var max = le == null ? null : le.getAttribute(Attribute.MAX_HEALTH);
        double mobMax = max == null ? 20 : max.getValue();
        String kind = floors.kind(mob);
        // 조건부 증강: 종류가 다르면 서로 곱해진다 (여러 종류를 모을수록 크게 세짐)
        if (r.firstStrike > 0 && le != null && le.getHealth() >= mobMax - 0.01) dmg *= 1 + r.firstStrike;
        if (r.berserk > 0) dmg *= 1 + r.berserk * Math.floor((1 - attacker.getHealth() / maxHealth(attacker)) * 10 + 1e-9);
        if (r.momentum > 0 && now < r.momentumUntil) dmg *= 1 + r.momentum * r.momentumStacks;
        if (r.overflow > 0 && Math.max(magic.manaFraction(attacker), Math.max(r.manaNow, r.manaPrev)) >= 0.8) dmg *= 1 + r.overflow;   // 시전으로 마나를 쓴 뒤에 맞으므로 최근 1초 안의 가장 높은 값
        if (r.hunter > 0 && !kind.equals("normal")) dmg *= 1 + r.hunter;
        if (r.combo > 0) {
            if (mob.getUniqueId().equals(r.comboTarget)) r.comboStacks = Math.min(5, r.comboStacks + 1);
            else { r.comboTarget = mob.getUniqueId(); r.comboStacks = 0; }
            dmg *= 1 + r.combo * r.comboStacks;
        }
        if (r.diceMax > 0.5) dmg *= rng.nextDouble(0.5, r.diceMax);
        if (le != null) {
            if (r.relics.contains("SCYTHE") && le.getHealth() < mobMax * 0.3) dmg *= 1.5;
            if (r.relics.contains("FROST")) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, false));
            if (r.relics.contains("EMBER")) le.setFireTicks(Math.max(le.getFireTicks(), 60));
        }
        if (r.relics.contains("MASK") && attacker.getHealth() < maxHealth(attacker) * 0.5) dmg *= 1.35;
        // 되갚기 (맞은 뒤 첫 공격)
        if (r.vengeMult > 0 && r.vengeArmed) {
            dmg *= r.vengeMult;
            r.vengeArmed = false;
            r.vengeReadyAt = now + r.vengeCooldownMs;
            mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, mob.getLocation().add(0, 1, 0), 3, .4, .4, .4);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 0.6f);
            attacker.sendActionBar(Component.text("되갚기! " + Cards.num(r.vengeMult) + "배", NamedTextColor.GOLD));
        }
        // 치명타
        if (r.crit > 0 && rng.nextDouble() < Math.min(1, r.crit)) {
            dmg *= 2 + r.critDamage;
            mob.getWorld().spawnParticle(Particle.CRIT, mob.getLocation().add(0, mob.getHeight() * 0.6, 0), 14, .3, .4, .3, .3);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.2f);
        }
        double real2 = dmg;                 // 몹의 실제 체력 기준 피해 (단단함 나누기 전)
        dmg /= floors.tough(mob);           // 상한을 넘는 체력 몫 (Floors.setHealth)
        // 마무리: 이 공격으로 체력이 기준 아래로 내려가면 바로 처치 (수문장은 기준 절반)
        boolean executed = r.finisher > 0 && le != null && le.getHealth() + le.getAbsorptionAmount() - dmg <= mobMax * (kind.equals("guardian") ? r.finisher / 2 : r.finisher);
        e.setDamage(executed ? (le.getHealth() + le.getAbsorptionAmount() + mobMax) * 10 : dmg);   // 아래 여파·가시 계산은 부풀리기 전 피해(dmg)로 한다
        // 생명 흡수: "준 피해의 %" 그대로면 층이 오를수록 (몹 체력·마릿수가 커져) 끝없이 불어나 위층에서 안 죽게 된다 (11/12 밸런스 점검).
        // → 그 층 몹 체력 합계로 나눠, 어느 층이든 "한 층을 다 잡으면 생명 흡수 × lifesteal-floor-heal" 만큼 차게 한다. 합계 상한 lifesteal-cap.
        if (r.lifesteal > 0) {
            double dealt = le == null ? real2 : Math.min(real2, le.getHealth() * floors.tough(mob));
            heal(attacker, dealt / Math.max(1, Floors.expectedTotal(s, r.floor) * r.gambleHealth) * Math.min(s.bal("lifesteal-cap", 0.15), r.lifesteal) * s.bal("lifesteal-floor-heal", 80));
        }
        // 피의 계약: 맞힐 때마다 체력 1 (0.25초에 한 번, 최대 체력의 30% 아래로는 안 내려감 — 낮은 체력에서 세지는 증강들과 짝)
        if (r.pact && now - r.pactAt >= 250) { r.pactAt = now; double low = maxHealth(attacker) * 0.3; if (attacker.getHealth() > low) attacker.setHealth(Math.max(low, attacker.getHealth() - 1)); }
        // 연쇄 번개·여파: 가까운 다른 탑 몹에게 (공격자 없는 피해로 넣어 이 계산을 다시 타지 않게)
        if (le != null && (r.chain > 0 || r.overkill > 0)) {
            double spill = 0;
            if (r.chain > 0 && rng.nextDouble() < Math.min(1, r.chain)) spill += real2 * 0.5;
            if (r.overkill > 0 && dmg > le.getHealth()) spill += (dmg - le.getHealth()) * floors.tough(mob) * Math.min(1, r.overkill);
            if (spill > 0) {
                LivingEntity other = null; double best = 36;
                for (var near : mob.getNearbyEntities(6, 3, 6))
                    if (near instanceof LivingEntity n && !near.equals(mob) && r.mobs.contains(near.getUniqueId()) && !n.isDead()) { double d2 = near.getLocation().distanceSquared(mob.getLocation()); if (d2 < best) { best = d2; other = n; } }
                if (other != null) {
                    LivingEntity target = other; double amount = spill / floors.tough(other);
                    mob.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, other.getLocation().add(0, 1, 0), 10, .3, .5, .3, .1);
                    Bukkit.getScheduler().runTask(plugin, () -> { if (target.isValid() && !target.isDead()) pierce(target, amount); });
                }
            }
        }
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
        if (p != null && run.siphon > 0) magic.addMana(p, run.siphon);
        if (run.momentum > 0) {
            long now = System.currentTimeMillis();
            run.momentumStacks = Math.min(3, (now < run.momentumUntil ? run.momentumStacks : 0) + 1);
            run.momentumUntil = now + 5000;
        }
        if (p != null && run.reload > 0 && !run.spells.isEmpty() && ThreadLocalRandom.current().nextDouble() < run.reload) {
            String name = magic.reload(p, run.spells);
            if (name != null) { p.sendActionBar(Component.text("재장전 — " + name, NamedTextColor.AQUA)); p.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.8f, 1.4f); }
        }
        if (run.relics.contains("ECHO") && e instanceof LivingEntity dead) {
            var max = dead.getAttribute(Attribute.MAX_HEALTH);
            double total = floors.effectiveHealth(dead) * 0.15;   // 죽은 몹 실제 체력의 15%
            for (var near : e.getNearbyEntities(4, 2, 4))
                if (near instanceof LivingEntity le && run.mobs.contains(near.getUniqueId())) pierce(le, total / floors.tough(le));   // 맞는 몹의 단단함만큼 나눔 (플레이어 피해와 같은 규칙)
            e.getWorld().spawnParticle(Particle.SONIC_BOOM, e.getLocation().add(0, 1, 0), 1);
        }
        if (floors.has(e, "split") && !e.getScoreboardTags().contains("tower_split_child")) {
            for (int i = 0; i < 2 && !s.normalMobs.isEmpty(); i++) {
                var at = e.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
                var mob = s.normalMobs.get(ThreadLocalRandom.current().nextInt(s.normalMobs.size()));
                var child = floors.spawn(mob, at);
                if (child == null) continue;
                floors.prepare(s, run.floor, child, new Floors.Pick(mob, false, false), List.of());
                if (p != null) aggro(child, p);
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

    /** 공격자 없는 추가 피해 (연쇄 번개·여파·가시 갑옷·메아리). 방금 맞은 몹은 0.5초 동안 더 약한 피해를 무시하므로 잠깐 풀고 넣은 뒤 되돌린다. */
    private static void pierce(LivingEntity target, double amount) {
        if (amount <= 0) return;
        int was = target.getNoDamageTicks();
        target.setNoDamageTicks(0);
        target.damage(amount);
        target.setNoDamageTicks(was);
    }

    private double maxHealth(Player p) { var a = p.getAttribute(Attribute.MAX_HEALTH); return a == null ? 20 : a.getValue(); }

    private void heal(Player p, double amount) { if (amount > 0 && !p.isDead()) p.setHealth(Math.min(maxHealth(p), p.getHealth() + amount)); }

    private void fullHeal(Player p) { p.setHealth(maxHealth(p)); }

    /** 탑 안 최종 능력치 = 기본값 + 탑 보너스. 다른 플러그인 보정(장신구 PDC·펫 도감 체력·아티팩트)이 붙어 있어도 결과를 이 값으로 맞춘다 (tick 마다 다시). */
    private void applyAttributes(Player p) {
        pin(p.getAttribute(Attribute.MAX_HEALTH), hpKey, Math.max(4, 20 + run.maxHealthBonus));   // 저주받은 힘을 여러 번 골라도 하트 2칸 밑으로는 안 내려감
        pin(p.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, 0.1 * (1 + run.speedBonus));
        if (p.getHealth() > maxHealth(p)) p.setHealth(maxHealth(p));
    }

    /** 우리 수정자 하나로 최종값을 target 에 맞춘다 (다른 곱셈 보정이 있어도 몇 번 보정해서 수렴). */
    private static void pin(org.bukkit.attribute.AttributeInstance a, NamespacedKey key, double target) {
        if (a == null) return;
        var cur = a.getModifier(key);
        double ours = cur == null ? 0 : cur.getAmount();
        if (Math.abs(a.getValue() - target) < 1e-6 && cur != null) return;
        for (int i = 0; i < 4; i++) {
            ours += target - a.getValue();
            a.removeModifier(key);
            a.addTransientModifier(new AttributeModifier(key, ours, AttributeModifier.Operation.ADD_NUMBER));   // 저장 안 되는 수정치: 서버가 갑자기 꺼져도 플레이어 데이터(HuskSync 로 5대 공유)에 안 남음
            if (Math.abs(a.getValue() - target) < 1e-6) break;
        }
    }

    private void clearAttributes(Player p) {
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) hp.removeModifier(hpKey);
        var sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.removeModifier(speedKey);
        var ab = p.getAttribute(Attribute.MAX_ABSORPTION);
        if (ab != null && ab.getModifier(absKey) != null) { ab.removeModifier(absKey); p.setAbsorptionAmount(0); }
    }

    // ───────────────────── 끝 ─────────────────────

    /** reason: death 쓰러짐 · quit 포기 · left 방 이탈 · clear 꼭대기 · offline 접속 끊김 · admin 관리자 · shutdown 서버 꺼짐 */
    void end(Player p, String reason) {
        if (run == null || run.phase == Run.Phase.ENDED) return;
        Run r = run;
        r.phase = Run.Phase.ENDED;
        int cleared = reason.equals("clear") ? s.floors : r.floor - 1;
        // 아래 정리 단계 하나가 실패해도 소지품 돌려주기(giveBack)까지는 반드시 간다 (중간에 멈추면 저장 막기가 풀리지 않고 탑 소지품인 채로 남음)
        try {
            hideGuardian();
            removeMobs(r);
            client.close(p);
        } catch (RuntimeException e) { plugin.getLogger().warning("탑 끝 정리 실패: " + e); }
        try {
            // 탑 표시가 없는 물건 = 판 중에 바깥에서 들어온 것 (의뢰 보상 등) → 가방·우편으로. 음식·커서 물건도 포함.
            // 커서 물건은 창을 닫기 전에 먼저 챙긴다 (닫으면 가방이 찼을 때 바닥에 떨어져 방 청소에 같이 지워짐).
            var outside = new ArrayList<ItemStack>();
            var cursor = p.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) { if (!towerItem(cursor)) outside.add(cursor.clone()); p.setItemOnCursor(null); }
            p.closeInventory();
            for (var it : p.getInventory().getContents())
                if (it != null && !it.getType().isAir() && !towerItem(it)) outside.add(it.clone());
            if (!outside.isEmpty()) rewards.forward(r.player, outside, String.valueOf(r.runId));
        } catch (RuntimeException e) { plugin.getLogger().severe("탑 안에서 받은 바깥 물건 전달 실패 " + p.getName() + ": " + e); }
        try { magic.unlearnNew(p); }
        catch (RuntimeException e) { plugin.getLogger().warning("탑 안 영구 습득 되돌리기 실패 " + p.getName() + ": " + e); }
        try { giveBack(p); }
        finally { lastEnded = r.player; lastEndedAt = System.currentTimeMillis(); activeRunner = null; }   // 원래 소지품으로 돌아왔으니 이제 HuskSync 저장을 막지 않는다 (직전에 찍힌 것만 3초 더)
        var exit = s.exit != null ? s.exit.location() : s.portal != null ? s.portal.location() : null;
        if (exit != null) p.teleport(exit);
        clearDrops();
        String head = switch (reason) {
            case "clear" -> "탑의 꼭대기에 올랐어요";
            case "death" -> "쓰러졌어요";
            case "left" -> "방을 벗어나 탑이 끝났어요";
            case "quit" -> "탑을 포기했어요";
            default -> "탑이 끝났어요";
        };
        long ms = r.elapsedMs();
        if (!plugin.isEnabled()) {
            // 서버가 꺼지는 중: 나중에 돌릴 작업을 예약할 수 없으므로 보상을 지금 바로 준다 (예전엔 조용히 빠짐, 11/12 점검).
            // 첫 도달 보상은 들어올 때 읽어 둔 최고 기록 기준 (못 읽었으면 이번엔 건너뜀 — 두 번 주는 것보다 안전).
            records.finish(r, cleared, reason, cardsText(r));
            rewards.finish(r.player, r, cleared, r.bestAtStart >= 0 ? r.bestAtStart : Integer.MAX_VALUE);
            rewards.guardianAfter(r.player, null, r);
            run = null;
            runner = null;
            return;
        }
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
        activeRunner = null;
        hideGuardian();
        removeMobs(r);
        clearDrops();
        records.finish(r, r.floor - 1, "offline", cardsText(r));
        run = null;
        runner = null;
    }

    /**
     * 떨어진 아이템 치우기: 방 안의 모든 아이템 + 그 월드 어디에 있든 탑 표시가 붙은 아이템
     * (방 밖으로 던지거나 방을 벗어난 5초 사이에 떨어뜨린 탑 물건을 나중에 줍지 못하게, 11/12 점검).
     */
    private void clearDrops() {
        var box = s.room();
        var start = s.start == null ? null : s.start.location();
        if (box == null || start == null) return;
        for (var e : start.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class))
            if (box.contains(e.getLocation()) || towerItem(e.getItemStack())) e.remove();
    }

    private void removeMobs(Run r) {
        for (var id : r.mobs) { var e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        r.mobs.clear();
        // 분열·몹이 스스로 부른 졸개(표시 없음)까지: 방 안의 모든 몹 (탑 방은 탑만 쓴다). 표시가 붙은 건 방 밖에 있어도.
        var box = s.room();
        var start = s.start == null ? null : s.start.location();
        if (box != null && start != null)
            for (var e : start.getWorld().getEntities()) {
                if (e instanceof Player) continue;
                boolean inside = box.contains(e.getLocation());
                if (e.getScoreboardTags().contains("chaca_tower") || (inside && (e instanceof org.bukkit.entity.Mob || e instanceof org.bukkit.entity.Projectile))) e.remove();
            }
    }

    /** 판 중에 방 안에서 새로 생긴 몹 (탑 몹이 부른 졸개 등): 탑 몹으로 표시하고 이번 층에 넣는다 (전리품 없음, 끝날 때 같이 사라짐). */
    void spawnedInRoom(Entity e) {
        if (run == null || run.phase == Run.Phase.ENDED || !(e instanceof org.bukkit.entity.Mob) || e.getScoreboardTags().contains("chaca_tower")) return;
        // 플레이어 쪽 소환물(마법 소환수·펫)·무적 표식은 탑 몹이 아니다 (넣으면 사라질 때까지 층이 안 끝남)
        var tags = e.getScoreboardTags();
        if (tags.contains("chacademia_summon") || tags.contains("chacademia_pet") || tags.contains("magiccodex_npc") || e.isInvulnerable()) return;
        var box = s.room();
        if (box == null || !e.getWorld().getName().equals(s.start.world()) || !box.contains(e.getLocation())) return;
        e.addScoreboardTag("chaca_tower");
        e.setPersistent(false);
        run.mobs.add(e.getUniqueId());
        if (runner != null) aggro(e, runner);
    }

    /**
     * 맡긴 것 돌려주기 + 탑 능력 지우기.
     * 마법 쪽(장신구·아티팩트 PDC)을 못 돌려놨으면 그 글자를 따로 남겨 두고 다음 접속·주기적으로 다시 시도한다 (맡김 파일을 지워 버리면 장신구가 사라짐).
     */
    private void giveBack(Player p) {
        clearAttributes(p);
        p.closeInventory();
        String magicState = stash.magicState(p.getUniqueId());
        boolean magicOk = false;
        try { magicOk = magic.restore(p, magicState); }
        catch (RuntimeException e) { plugin.getLogger().severe("마법 되돌리기 실패 " + p.getName() + " (맡긴 파일 확인): " + e); }
        if (!magicOk) { stash.keepMagic(p.getUniqueId(), magicState); plugin.getLogger().severe("장신구·아티팩트 되돌리기 미룸 (다음 접속 때 다시): " + p.getName()); }
        if (!stash.restore(p)) plugin.getLogger().severe("소지품 되돌리기 실패 " + p.getName());
        if (p.getUniqueId().equals(activeRunner)) activeRunner = null;   // 어떤 길로 돌려줬든 저장 막기는 여기서 푼다 (안 풀리면 이 사람 저장이 계속 취소됨)
    }

    /** 미뤄 둔 장신구·아티팩트 되돌리기 다시 시도. */
    private void retryMagic(Player p) {
        String state = stash.keptMagic(p.getUniqueId());
        if (state == null || inRun(p)) return;
        try { if (magic.restore(p, state)) { stash.dropMagic(p.getUniqueId()); plugin.getLogger().info("미뤄 둔 장신구·아티팩트 되돌림: " + p.getName()); } }
        catch (RuntimeException e) { plugin.getLogger().warning("장신구·아티팩트 다시 되돌리기 실패 " + p.getName() + ": " + e); }
    }

    /**
     * 지난번에 못 돌려준 것 (서버가 갑자기 꺼졌을 때만 남는다 — 보통 끝·접속 끊김·정상 종료는 그 자리에서 돌려줌).
     *
     * <p>지금 가방에 탑 표시 없는 물건이 있으면 = HuskSync 가 공용 DB 의 진짜 소지품을 이미 불러온 것 (판 중 저장은 막아 두므로 들어가기 전 상태,
     * 또는 그 뒤 다른 서버에서 지낸 상태). 그때 맡긴 것으로 덮으면 그 사이 얻은 건 사라지고 쓴 건 되살아난다(복사) → 가방은 그대로 두고
     * 맡김 파일은 conflict 폴더에 보관만 한다. 장신구·아티팩트(이 서버에만 있는 기록)는 언제나 돌려놓는다.
     * 가방이 비었거나 탑 물건뿐이면 = 탑 상태가 남은 것 → 맡긴 것으로 돌려놓는다.
     */
    private void restoreLeftover(Player p) {
        if (inRun(p) || !stash.has(p.getUniqueId())) return;
        boolean real = false;
        for (var it : p.getInventory().getContents()) if (it != null && !it.getType().isAir() && !towerItem(it)) { real = true; break; }
        if (!real) {
            giveBack(p);
            ChacaLink.notify(p, "system", "tower:restore", "탑에 맡겼던 소지품을 돌려받았어요", "", 0);
            return;
        }
        clearAttributes(p);
        String magicState = stash.magicState(p.getUniqueId());
        boolean magicOk = false;
        try { magicOk = magic.restore(p, magicState); }
        catch (RuntimeException e) { plugin.getLogger().severe("마법 되돌리기 실패 " + p.getName() + ": " + e); }
        if (!magicOk) stash.keepMagic(p.getUniqueId(), magicState);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (towerItem(inv.getItem(i))) inv.setItem(i, null);
        stash.conflict(p.getUniqueId());
        if (p.getUniqueId().equals(activeRunner)) activeRunner = null;
        plugin.getLogger().severe("탑 맡김이 남아 있었지만 " + p.getName() + " 의 가방에 이미 진짜 소지품이 있어 그대로 둠 (맡김 파일은 stash/conflict 에 보관 — 물건이 비면 여기서 꺼내 확인)");
    }

    void quit(Player p) {
        queue.remove(p.getUniqueId());
        if (p.getUniqueId().equals(offered)) offered = null;
        pendingRestore.remove(p.getUniqueId());
        if (inRun(p)) end(p, "offline");
    }

    /** 남은 맡김이 있어 복구를 기다리는 사람 (그동안 가방을 만지면 복사가 될 수 있어 잠근다). */
    private final Set<UUID> pendingRestore = new java.util.HashSet<>();
    private final Map<UUID, Integer> joinSeq = new HashMap<>();
    /** HuskSync 저장 이벤트(다른 스레드일 수 있음)가 읽는 "지금 판 중인 사람". */
    private volatile UUID activeRunner;
    boolean huskHooked;

    /**
     * HuskSync 는 저장할 내용을 먼저 찍고 저장 이벤트는 한 틱 뒤에 낸다 → 판이 끝나기 직전에 찍힌 탑 상태가 판이 끝난 뒤 이벤트로 올 수 있어
     * 끝난 뒤 3초 동안도 그 사람의 (나가기가 아닌) 저장을 막는다. 나가기 저장은 HuskHook 이 언제나 통과시킨다.
     */
    boolean savingBlocked(UUID id) {
        if (id == null) return false;
        return id.equals(activeRunner) || (id.equals(lastEnded) && System.currentTimeMillis() - lastEndedAt < 3000);
    }
    private volatile UUID lastEnded;
    private volatile long lastEndedAt;

    boolean inTowerWorld(org.bukkit.World w) { return s != null && s.start != null && w.getName().equals(s.start.world()); }

    boolean waitingRestore(Player p) { return pendingRestore.contains(p.getUniqueId()); }

    void join(Player p) {
        clearAttributes(p);
        magic.clearMana(p);
        retryMagic(p);
        if (!stash.has(p.getUniqueId()) || inRun(p)) return;
        pendingRestore.add(p.getUniqueId());
        // HuskSync 가 있으면 "불러오기 끝" 알림(synced)에 맞춰 복구한다. 알림이 안 오는 경우를 대비해 30초 뒤에도 한 번.
        long wait = huskHooked ? 600L : 60L;
        int seq = joinSeq.merge(p.getUniqueId(), 1, Integer::sum);   // 나갔다 바로 들어오면 앞 접속의 예약이 뒤 접속에서 터지지 않게
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (joinSeq.getOrDefault(p.getUniqueId(), 0) == seq) synced(p.getUniqueId()); }, wait);
    }

    /** HuskSync 가 이 사람 데이터를 다 불러온 뒤 (또는 대기 시간이 지난 뒤). */
    void synced(UUID id) {
        if (!pendingRestore.remove(id)) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) restoreLeftover(p);
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
