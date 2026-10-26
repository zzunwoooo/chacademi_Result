package school.magiccodex.paper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import school.magiccodex.protocol.FishingProtocol;

/**
 * 생활 마법 374~386 중 Bridge 시스템에 붙는 9종 (2026-10-25, 기획 claude/magic-325-387-plan.md 생활 표)
 * + 추가 생활·편의 12종 중 2종 (비늘 감정 scale_appraisal, 깨달음의 빛 enlighten_hint; 나머지 10종은 ChAcademiaMagicMythic NovaLife3).
 * SpellUtility 가 부른다: {@link #refusal} (SpellRuntime.prepare, 마나 차감 전: 쓸 수 없으면 이유 문구, 마나·쿨 없음)
 * → {@link #apply} (마나 차감 뒤, 시각 효과가 이미 시작된 같은 틱).
 *
 * <p>버프(다음 연금 1회 등)는 메모리에만 둔다: 플레이어 → 버프 id → (끝나는 시각, 남은 횟수). 나가면(서버 이동 포함) 지운다.
 * 시스템 쪽 훅(AlchemyBridge·FishingBridge)은 {@link #active} / {@link #take} 로 읽는다. 메인 스레드 전용.
 *
 * <p>시각 효과: 시전 단계(cast)는 카탈로그가 시작하고, 물 표면(377)·NPC(383)에 붙는 시스템은 여기서
 * PortableVFX emitSpellVisualEvent 로 external:&lt;system&gt; 을 보낸다 (Links.vfx 와 같은 방식).
 */
final class LifeSpells implements Listener, AutoCloseable {
    static final Set<String> IDS = Set.of("alchemy_preheat", "water_glimpse", "calm_ripple", "treasure_scent", "season_sense",
            "pack_sort", "heart_read", "insight_flash", "blessed_ribbon", "scale_appraisal", "enlighten_hint");
    static final long PREHEAT_MS = 10 * 60_000L, SCENT_MS = 10 * 60_000L;
    private static final double LOOK_RANGE = 24, NPC_RANGE = 10;
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");

    private record Perk(long until, int uses) {}
    /** player → perk id → perk. uses < 0 = 시간 동안 무제한. */
    private static final Map<UUID, Map<String, Perk>> PERKS = new HashMap<>();
    /** 383: player → NPC 캐릭터 id → 마지막으로 읽은 날 (Asia/Seoul). 판정용 캐시 — 진짜 기록은 공유 DB
     *  (codex_affinity_daily, source {@link #HEART_READ_SOURCE}) 라 서버 이동·재시작으로 초기화되지 않는다 (메모 #90, 2026-10-26). */
    static final String HEART_READ_SOURCE = "heart_read";
    private static final Map<UUID, Map<String, LocalDate>> READ = new HashMap<>();
    private static ItemsAdderItems itemsAdder;

    private final MagicCodexBridge plugin;
    private final NamespacedKey ribbonKey;

    LifeSpells(MagicCodexBridge plugin) {
        this.plugin = plugin;
        ribbonKey = new NamespacedKey(plugin, RIBBON_KEY);
        itemsAdder = new ItemsAdderItems(plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        current = this;
    }

    /** 지금 켜진 생활 마법 (마법 힌트권 UtilityItemsBridge 가 힌트 고르기를 같이 씀). MythicMobs 가 없어 마법 실행이 꺼지면 null. */
    private static LifeSpells current;
    static LifeSpells current() { return current; }

    /** 386 리본 표식 (PDC BYTE 1). ChacaNPC GiftService 가 "magiccodexbridge:gift_ribbon" 으로 읽는다. */
    static final String RIBBON_KEY = "gift_ribbon";

    @EventHandler public void quit(PlayerQuitEvent e) { PERKS.remove(e.getPlayer().getUniqueId()); READ.remove(e.getPlayer().getUniqueId()); }
    /** 접속 때 오늘 마음 읽기 기록을 공유 DB 에서 캐시로 (다른 서버에서 읽은 것도 오늘은 막힘). */
    @EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e) {
        var social = plugin.npcSocial(); if (social == null) return;
        UUID id = e.getPlayer().getUniqueId(); String day = social.today();
        social.dailyRows(id, day).thenAccept(rows -> {
            LocalDate today = LocalDate.parse(day);
            for (var r : rows) if (r.source().equals(HEART_READ_SOURCE)) READ.computeIfAbsent(id, k -> new HashMap<>()).put(r.npc(), today);
        });
    }
    /** 관리자가 오늘 마음 읽기 제한을 지웠을 때 (DailyLimits). */
    static void forgetReads(UUID id) { READ.remove(id); }
    /** 마음 읽기 하루 기준 = 호감도 일일 제한과 같은 날짜 (NpcSocialService.today, 없으면 Asia/Seoul). */
    private LocalDate readDay() { var social = plugin.npcSocial(); return social != null ? LocalDate.parse(social.today()) : LocalDate.now(DAY_ZONE); }

    @Override public void close() { HandlerList.unregisterAll(this); PERKS.clear(); READ.clear(); if (current == this) current = null; }

    // ------------------------------------------------------------------ perk store (system hooks)

    private static void grant(UUID id, String perk, long millis, int uses) {
        PERKS.computeIfAbsent(id, k -> new HashMap<>()).put(perk, new Perk(millis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + millis, uses));
    }

    /** 버프가 살아 있는지 (끝나지 않았고 횟수가 남음). */
    static boolean active(UUID id, String perk) {
        var map = PERKS.get(id);
        var p = map == null ? null : map.get(perk);
        if (p == null) return false;
        if (System.currentTimeMillis() > p.until() || p.uses() == 0) { map.remove(perk); if (map.isEmpty()) PERKS.remove(id); return false; }
        return true;
    }

    /** 살아 있으면 1회 쓰고 true. 무제한(시간형) 버프는 쓰지 않고 true. */
    static boolean take(UUID id, String perk) {
        if (!active(id, perk)) return false;
        var map = PERKS.get(id);
        var p = map.get(perk);
        if (p.uses() > 0) {
            if (p.uses() == 1) { map.remove(perk); if (map.isEmpty()) PERKS.remove(id); }
            else map.put(perk, new Perk(p.until(), p.uses() - 1));
        }
        return true;
    }

    /** 374: 예열된 연금 경험치 (30% 감소, 반올림). */
    static int preheated(int exp) { return exp - (int) Math.round(exp * .3); }

    /** 384: 연금 시도 결과 (숫자만). correct = 넣은 재료 종류 중 가장 가까운 레시피에 들어 있는 종류 수. */
    static void insight(Player p, int kinds, int correct) {
        CmdKit.notify(p, "system", "life-insight", "영감의 섬광: 맞는 재료 " + correct + "개", "넣은 재료 " + kinds + "가지 중 " + correct + "가지가 맞아요", 0);
    }

    // ------------------------------------------------------------------ refusal (before mana) / apply (after mana)

    /** null = 쓸 수 있음. 아니면 이유 (마나·쿨 없이 거절). 세상은 바꾸지 않는다. */
    String refusal(Player p, String id) {
        UUID uid = p.getUniqueId();
        return switch (id) {
            case "alchemy_preheat" -> active(uid, id) ? "이미 연금로가 예열돼 있어요" : null;
            case "calm_ripple" -> active(uid, id) ? "이미 잔잔한 물결이 걸려 있어요" : null;
            case "treasure_scent" -> active(uid, id) ? "이미 보물 냄새를 맡고 있어요" : null;
            case "insight_flash" -> active(uid, id) ? "이미 영감이 떠올라 있어요" : null;
            case "water_glimpse" -> plugin.fishingBridge() == null ? "지금은 물을 엿볼 수 없어요" : lookedWater(p) == null ? "물을 바라봐 주세요" : null;
            case "season_sense" -> plugin.climateService() == null || plugin.climateService().season() == null ? "계절 정보를 읽지 못했어요" : null;
            case "pack_sort" -> plugin.bagPagesBridge() == null ? "지금은 아공간 배낭을 쓸 수 없어요" : plugin.bagPagesBridge().sortPages(p, true);
            case "heart_read" -> heartRefusal(p);
            case "blessed_ribbon" -> ribbonRefusal(p);
            case "scale_appraisal" -> appraisalRefusal(p);
            case "enlighten_hint" -> pickHint(p, 2) == null ? "지금은 떠오르는 마법 이야기가 없어요" : null;
            default -> null;
        };
    }

    boolean apply(Player p, String id) {
        if (refusal(p, id) != null) return false;
        UUID uid = p.getUniqueId();
        switch (id) {
            case "alchemy_preheat" -> { grant(uid, id, PREHEAT_MS, 1); CmdKit.notify(p, "system", "life-" + id, "연금로가 예열됐어요", "10분 안의 다음 연금 제작 1회, 경험치가 30% 덜 들어요", 0); }
            case "calm_ripple" -> { grant(uid, id, 0, 1); CmdKit.notify(p, "system", "life-" + id, "물결이 잔잔해졌어요", "다음 낚시 1회, 줄 장력이 천천히 올라요", 0); }
            case "treasure_scent" -> { grant(uid, id, SCENT_MS, -1); CmdKit.notify(p, "system", "life-" + id, "보물 냄새가 나요", "10분 동안 보물상자가 낚일 확률이 2배예요", 0); }
            case "insight_flash" -> { grant(uid, id, 0, 1); CmdKit.notify(p, "system", "life-" + id, "영감이 떠올랐어요", "다음 연금 시도 1회, 맞는 재료 수를 알려 줘요", 0); }
            case "water_glimpse" -> { return glimpse(p); }
            case "season_sense" -> { season(p); }
            case "pack_sort" -> {
                String refused = plugin.bagPagesBridge().sortPages(p, false);
                if (refused != null) { CmdKit.notify(p, "warning", "life-" + id, "배낭을 정리하지 못했어요", refused, 0); return false; }
            }
            case "heart_read" -> { return heartRead(p); }
            case "blessed_ribbon" -> { return ribbon(p); }
            case "scale_appraisal" -> { return appraise(p); }
            case "enlighten_hint" -> { return enlighten(p); }
            default -> { return false; }
        }
        return true;
    }

    private void later(Player p, long ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) r.run(); }, ticks); }

    /** external:&lt;system&gt; to this spell's running catalog cast (PortableVFX optional). */
    static void vfx(Player caster, String spell, String system, Location at, UUID target) {
        var pv = Bukkit.getPluginManager().getPlugin("PortableVFX");
        if (pv == null || !pv.isEnabled()) return;
        try {
            pv.getClass().getMethod("emitSpellVisualEvent", Player.class, String.class, String.class, Location.class, UUID.class)
                    .invoke(pv, caster, spell, "external:" + system, at, target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    // ------------------------------------------------------------------ 377 물밑 엿보기

    private Block lookedWater(Player p) {
        var eye = p.getEyeLocation();
        var hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), LOOK_RANGE, FluidCollisionMode.ALWAYS, true);
        Block b = hit == null ? null : hit.getHitBlock();
        return b != null && FishingWater.water(b) ? b : null;
    }

    private boolean glimpse(Player p) {
        Block b = lookedWater(p);
        var fishing = plugin.fishingBridge();
        FishingBridge.Glimpse info = b == null || fishing == null ? null : fishing.glimpse(p, b.getLocation().add(.5, .5, .5));
        if (info == null) return false;
        Location surface = new Location(b.getWorld(), b.getX() + .5, info.surfaceY() + .9, b.getZ() + .5, p.getLocation().getYaw(), 0);
        vfx(p, "water_glimpse", "water_glimpse", surface, null);
        // NOTES: the panel at the rim flash (static +0.86 s)
        later(p, 17, () -> {
            CmdKit.notify(p, "system", "life-water_glimpse", "물밑 엿보기", info.summary(), 0);
            CmdKit.lines(p, info.lines());
        });
        return true;
    }

    // ------------------------------------------------------------------ 381 계절 예보

    private void season(Player p) {
        var climate = plugin.climateService();
        var now = climate.season();
        var next = ClimateService.Season.values()[(now.ordinal() + 1) % 4];
        Duration left = climate.untilNextSeason();
        String until = left == null ? "계절이 고정돼 있어요" : next.label + "까지 " + span(left) + (climate.seasonByGameTime() ? " (게임 시간 기준)" : "");
        String tornado = tornado(now, next);
        var lines = List.of("── 계절 예보 ──", " 지금 계절: " + now.label, " " + until, " 회오리: " + tornado);
        // NOTES: show the info at the disperse (cast +2.22 s)
        later(p, 44, () -> {
            CmdKit.notify(p, "system", "life-season_sense", "지금은 " + now.label, until, 0);
            CmdKit.lines(p, lines);
        });
    }

    private static String span(Duration d) {
        long m = Math.max(1, (d.toSeconds() + 59) / 60), days = m / 1440, hours = m % 1440 / 60, mins = m % 60;
        if (days > 0) return days + "일" + (hours > 0 ? " " + hours + "시간" : "");
        if (hours > 0) return hours + "시간" + (mins > 0 ? " " + mins + "분" : "");
        return mins + "분";
    }

    /** ChAcademiaTornado.seasonForecast() (있으면), 없으면 계절만 보고 안내. */
    private static String tornado(ClimateService.Season now, ClimateService.Season next) {
        String text = null;
        var t = Bukkit.getPluginManager().getPlugin("ChAcademiaTornado");
        if (t != null && t.isEnabled()) {
            try { Object r = t.getClass().getMethod("seasonForecast").invoke(t); if (r instanceof String s && !s.isBlank()) text = s; }
            catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }
        if (text == null) text = now == ClimateService.Season.AUTUMN ? "이 서버에는 회오리가 나타나지 않아요 (가을 야생)" : "회오리는 가을에 야생에서 나타나요";
        if (now != ClimateService.Season.AUTUMN && next == ClimateService.Season.AUTUMN) text += " · 다음 계절이 가을이에요";
        return text;
    }

    // ------------------------------------------------------------------ 383 마음 읽기

    private record Npc(Entity entity, String id, String name, List<String> liked) {}

    private static Object npcApi(String method, Class<?>[] types, Object... args) {
        var dep = Bukkit.getPluginManager().getPlugin("ChacaNPC");
        if (dep == null || !dep.isEnabled()) return null;
        try {
            Class<?> api = Class.forName("kr.chacademy.npc.api.ChacaNpcApi", true, dep.getClass().getClassLoader());
            return api.getMethod(method, types).invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

    private Npc lookedNpc(Player p) {
        var eye = p.getEyeLocation();
        var hit = p.getWorld().rayTrace(eye, eye.getDirection(), NPC_RANGE, FluidCollisionMode.NEVER, true, .4,
                e -> !e.equals(p) && e.hasMetadata("NPC"));
        Entity e = hit == null ? null : hit.getHitEntity();
        if (e == null) return null;
        Object id = npcApi("characterOf", new Class<?>[]{Entity.class}, e);
        if (!(id instanceof String s) || s.isBlank()) return null;
        Object name = npcApi("characterName", new Class<?>[]{String.class}, s);
        Object liked = npcApi("likedGifts", new Class<?>[]{String.class}, s);
        var list = new ArrayList<String>();
        if (liked instanceof List<?> l) for (Object o : l) if (o != null && !o.toString().isBlank()) list.add(o.toString());
        return new Npc(e, s, name instanceof String n && !n.isBlank() ? n : s, list);
    }

    private String heartRefusal(Player p) {
        var dep = Bukkit.getPluginManager().getPlugin("ChacaNPC");
        if (dep == null || !dep.isEnabled()) return "지금은 마음을 읽을 수 없어요";
        var npc = lookedNpc(p);
        if (npc == null) return "NPC를 바라봐 주세요";
        if (readDay().equals(READ.getOrDefault(p.getUniqueId(), Map.of()).get(npc.id()))) return "오늘은 이미 " + npc.name() + "의 마음을 읽었어요";
        if (npc.liked().isEmpty()) return npc.name() + "의 마음은 읽히지 않아요";
        return null;
    }

    private boolean heartRead(Player p) {
        var npc = lookedNpc(p);
        if (npc == null || npc.liked().isEmpty()) return false;
        READ.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(npc.id(), readDay());
        var social = plugin.npcSocial();   // 공유 DB 에도 기록 (서버 이동·재시작 뒤에도 오늘은 1번)
        if (social != null) social.markOnce(p.getUniqueId(), npc.id(), HEART_READ_SOURCE);
        String key = npc.liked().get(new Random().nextInt(npc.liked().size()));
        Entity e = npc.entity();
        double h = e instanceof LivingEntity le ? le.getHeight() : 1.8;
        vfx(p, "heart_read", "read", e.getLocation().add(0, h * .67, 0), e.getUniqueId());
        vfx(p, "heart_read", "glimmer", e.getLocation(), e.getUniqueId());
        Component gift = giftName(key);
        // NOTES: the hint at the heart pulse (+1.25 s)
        later(p, 25, () -> {
            p.sendMessage(Component.text("[마음 읽기] ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(npc.name() + "은(는) ", NamedTextColor.WHITE))
                    .append(gift.color(NamedTextColor.GOLD))
                    .append(Component.text("을(를) 좋아하는 것 같아요.", NamedTextColor.WHITE)));
            CmdKit.notify(p, "system", "life-heart_read", npc.name() + "의 마음이 보여요", "좋아하는 선물: " + PlainTextComponentSerializer.plainText().serialize(gift), 0);
        });
        return true;
    }

    /** gift_likes 키 → 이름: ItemsAdder "namespace:id" 는 아이템 이름, 마크 아이템은 번역 이름(클라 언어). */
    private static Component giftName(String key) {
        String k = key.strip().toLowerCase(Locale.ROOT);
        if (k.contains(":") && !k.startsWith("minecraft:")) {
            ItemStack it = itemsAdder == null ? null : itemsAdder.create(k, 1);
            return Component.text(it == null ? k : AlchemyBridge.displayName(it));
        }
        Material m = Material.matchMaterial(k);
        return m == null ? Component.text(k) : Component.translatable(m.translationKey());
    }

    // ------------------------------------------------------------------ 386 축복의 리본

    /** 선물 아이템: lore 첫 줄 "선물", 둘째 줄 등급 (ChacaNPC GiftService.gradeOf 와 같은 규칙). */
    private static boolean gift(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        var lore = item.getItemMeta().lore();
        if (lore == null || lore.size() < 2) return false;
        String first = PlainTextComponentSerializer.plainText().serialize(lore.getFirst()).replaceAll("(?i)[&§][0-9a-fk-orx]", "").strip();
        String second = PlainTextComponentSerializer.plainText().serialize(lore.get(1)).replaceAll("(?i)[&§][0-9a-fk-orx]", "").strip();
        return first.equals("선물") && List.of("일반", "고급", "유일", "전설").contains(second);
    }

    private boolean ribboned(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ribbonKey, PersistentDataType.BYTE);
    }

    private String ribbonRefusal(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!gift(hand)) return "선물 아이템을 손에 들어 주세요";
        if (ribboned(hand)) return "이미 리본이 묶여 있어요";
        if (hand.getAmount() > 1 && p.getInventory().firstEmpty() < 0) return "나머지 선물을 둘 빈칸이 필요해요";
        return null;
    }

    private boolean ribbon(Player p) {
        var inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        int slot = inv.getHeldItemSlot();
        ItemStack one = hand.clone(); one.setAmount(1);
        var meta = one.getItemMeta();
        meta.getPersistentDataContainer().set(ribbonKey, PersistentDataType.BYTE, (byte) 1);
        var lore = new ArrayList<>(meta.lore() == null ? List.<Component>of() : meta.lore());
        lore.add(Component.text("축복의 리본: 호감도 +20%", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        one.setItemMeta(meta);
        ItemStack rest = null;
        if (hand.getAmount() > 1) { rest = hand.clone(); rest.setAmount(hand.getAmount() - 1); }
        inv.setItem(slot, one);
        if (rest != null) {
            int free = inv.firstEmpty();
            if (free >= 0) inv.setItem(free, rest);
            else for (ItemStack left : inv.addItem(rest).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        p.updateInventory();
        CmdKit.notify(p, "system", "life-blessed_ribbon", "선물에 리본을 묶었어요", "이 선물은 호감도가 20% 더 올라요", 0);
        return true;
    }

    // ------------------------------------------------------------------ 비늘 감정 (scale_appraisal)

    private static final Pattern LORE_SIZE = Pattern.compile("크기 ([0-9]+(?:\\.[0-9]+)?)cm");

    private String appraisalRefusal(Player p) {
        var fishing = plugin.fishingBridge();
        if (fishing == null) return "지금은 물고기를 감정할 수 없어요";
        var held = fishing.gear().heldFish(p.getInventory().getItemInMainHand());
        if (held == null || fishing.config().fishById.get(held.fish()) == null) return "물고기를 손에 들어 주세요";
        return null;
    }

    private boolean appraise(Player p) {
        var fishing = plugin.fishingBridge();
        ItemStack hand = p.getInventory().getItemInMainHand();
        FishingGear.HeldFish held = fishing == null ? null : fishing.gear().heldFish(hand);
        FishingConfig.Fish def = held == null ? null : fishing.config().fishById.get(held.fish());
        if (def == null) return false;
        float loreSize = 0;
        List<Component> lore = hand.getItemMeta() == null ? null : hand.getItemMeta().lore();
        if (lore != null) for (Component line : lore) {
            Matcher m = LORE_SIZE.matcher(PlainTextComponentSerializer.plainText().serialize(line));
            if (m.find()) { try { loreSize = Float.parseFloat(m.group(1)); } catch (NumberFormatException ignored) {} break; }
        }
        float fallback = held.size() > 0 ? held.size() : loreSize;
        // NOTES: the lens flash at ~1.0 s; row-backed fish keep their size in the tank database (the item lore is a copy)
        later(p, 20, () -> {
            if (held.size() <= 0 && !held.tankId().isEmpty()) {
                fishing.tank().itemInfo(List.of(held.tankId()), rows -> {
                    var row = rows.get(held.tankId());
                    if (p.isOnline()) appraisal(p, def, row != null && row.size() > 0 ? (float) row.size() : fallback);
                });
            } else appraisal(p, def, fallback);
        });
        return true;
    }

    private void appraisal(Player p, FishingConfig.Fish def, float cm) {
        var fishing = plugin.fishingBridge();
        if (fishing == null) return;
        String grade = FishingProtocol.GRADE_LABELS[Math.clamp(def.grade(), 0, FishingProtocol.GRADE_LABELS.length - 1)];
        var lines = new ArrayList<String>();
        lines.add("── 비늘 감정: " + def.name() + " ──");
        lines.add(" 등급: " + grade);
        String size = cm > 0 ? String.format(Locale.ROOT, "%.1fcm", cm) : "알 수 없음";
        String place = "";
        if (cm > 0 && def.maxSize() > def.minSize()) {
            int pct = (int) Math.round(Math.clamp((cm - def.minSize()) / (def.maxSize() - def.minSize()), 0, 1) * 100);
            place = " (이 종 " + def.minSize() + "~" + def.maxSize() + "cm 중 " + pct + "% 지점)";
        }
        lines.add(" 크기: " + size + place);
        var book = fishing.store().book(p.getUniqueId());
        var rec = book.loaded ? book.rows.get(def.id()) : null;
        String record;
        if (rec == null || rec.best() <= 0) record = book.loaded ? "아직 기록 없음" : "기록을 불러오는 중";
        else if (cm > 0 && cm >= rec.best() - 1e-3) record = String.format(Locale.ROOT, "내 기록 %.1fcm — 이 물고기가 최고 기록이에요", rec.best());
        else record = String.format(Locale.ROOT, "내 기록 %.1fcm%s", rec.best(), cm > 0 ? String.format(Locale.ROOT, " (%.1fcm 모자람)", rec.best() - cm) : "");
        lines.add(" 기록: " + record);
        var shop = plugin.fishShopBridge();
        String price = shop == null || cm <= 0 ? "알 수 없음" : String.format("%,d G", shop.price(new FishTank.Fish("", def.id(), cm, 0)));
        lines.add(" 판매가: " + price + " (아공간 수조에 넣고 물고기 판매소에서 팔 때)");
        CmdKit.notify(p, "system", "life-scale_appraisal", def.name() + " · " + grade + " · " + size, "판매가 " + price, 0);
        CmdKit.lines(p, lines);
    }

    // ------------------------------------------------------------------ 깨달음의 빛 (enlighten_hint)

    /**
     * 힌트로 고르지 않는 마법: 궁극(tools/spell_order.py 가중치 10), MagicDiscovery 의
     * 미구현 조건(Definitions.PENDING). 번호를 다시 매겨도 id 는 그대로라 여기 목록은 가중치 10 이 바뀔 때만 고친다.
     */
    static final Set<String> NO_HINT = Set.of(
            "abyss_gate", "aurora_veil", "crimson_breath", "crossfire", "eclipse_eye", "flame_emperor", "heaven_cyclone", "leviathan_rise", "titan_stride");
    // 2026-10-26 발견 조건 v2: 홍련의 심장 판정 구현(PENDING 비움), 300종 밖 옛 마법(feather_step·wind_vault 등)은 discoveries.yml 에서 삭제

    private static final Random RANDOM = new Random();

    /** 공통 힌트 파일·조건표 (MagicCodexBridge 가 켬; 없으면 null → 자동 문장만). */
    private SpellHints spellHints() { return plugin.spellHints(); }

    /** ChacaNPC hints.yml 목격담 재료 (spell → materials), 없으면 null. */
    private static String npcMaterials(String spell) {
        Object m = npcApi("hintMaterials", new Class<?>[0]);
        return m instanceof Map<?, ?> map && map.get(spell) instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * 2성 문장 (마법 힌트권 2성 · 깨달음의 빛 · NPC 힌트 대체 재료가 같이 씀):
     * spell-hints.yml 의 2 → ChacaNPC 목격담 재료로 만든 목격담 → 발견 조건 문구를 흐린 자동 목격담. 만들 수 없으면 null.
     */
    static String second(SpellHints hints, SpellHints.Condition c, String materials) {
        String line = hints == null ? null : hints.line(c.spell(), 2);
        if (line != null) return line;
        return witness(c.text(), materials);
    }

    /**
     * 힌트 후보: 아직 안 배운 마법 · 궁극(NO_HINT) 제외 · 발견 조건 문구가 있는 것 (discoveries.yml + manual-conditions.yml).
     * 2성은 2성 문장을 만들 수 있는 마법만 (파일 문장이 있거나 자동 목격담이 되는 것).
     */
    private SpellHints.Condition pickHint(Player p, int stars) {
        var hints = spellHints();
        if (hints == null) return null;
        var open = new ArrayList<SpellHints.Condition>();
        Map<?, ?> materials = stars == 2 && npcApi("hintMaterials", new Class<?>[0]) instanceof Map<?, ?> m ? m : Map.of();
        for (var c : hints.conditions()) {
            if (NO_HINT.contains(c.spell()) || p.hasPermission(c.permission())) continue;
            if (stars == 2 && hints.line(c.spell(), 2) == null && witness(c.text(), null) == null
                    && !(materials.get(c.spell()) instanceof String s && !s.isBlank())) continue;
            open.add(c);
        }
        return open.isEmpty() ? null : open.get(RANDOM.nextInt(open.size()));
    }

    private boolean enlighten(Player p) {
        var h = pickHint(p, 2);
        if (h == null) return false;
        String line = second(spellHints(), h, npcMaterials(h.spell()));
        if (line == null) return false;
        // NOTES: the light book is fully open at ~1.2 s
        later(p, 24, () -> {
            p.sendMessage(Component.text("[깨달음의 빛] ", NamedTextColor.YELLOW).append(Component.text(line, NamedTextColor.WHITE)));
            CmdKit.notify(p, "system", "life-enlighten_hint", "깨달음의 빛", line, 0);
        });
        return true;
    }

    // ------------------------------------------------------------------ 마법 힌트권 (item:spell_hint, 2026-10-26)

    /** 힌트 카드 한 장: 고른 마법 id, 별 수(1~3), 보여 줄 문장. */
    record HintCard(String spell, int stars, String line) {}
    /** 고른 마법과 별 수 (문장은 {@link #hintCard(Player, HintPick, java.util.function.Consumer)} 가 만든다). */
    record HintPick(SpellHints.Condition condition, int stars) {}

    private static final Map<String, String> ELEMENT_NAMES = Map.of("fire", "화염", "water", "물", "wind", "바람", "earth", "대지", "light", "빛", "dark", "어둠");
    private Map<String, String> elements;

    /** spell-elements.yml (jar 안, 도감 분류) 마법 id → 원소. 한 번만 읽는다. */
    private String elementOf(String spell) {
        if (elements == null) {
            var map = new HashMap<String, String>();
            try (var in = plugin.getResource("spell-elements.yml")) {
                if (in != null) {
                    var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    var sec = y.getConfigurationSection("spells");
                    if (sec != null) for (String id : sec.getKeys(false)) { String e = sec.getString(id); if (e != null) map.put(id, e); }
                }
            } catch (java.io.IOException | RuntimeException e) { plugin.getLogger().warning("마법 힌트권: spell-elements.yml 을 읽지 못했어요 (" + e.getMessage() + ")"); }
            elements = Map.copyOf(map);
        }
        return ELEMENT_NAMES.get(elements.get(spell));
    }

    /** 마법 힌트권: 깨달음의 빛과 같은 후보에서 1개 고르기. null = 남은 힌트 없음 (아이템을 쓰지 않음). 세상은 바꾸지 않는다. */
    HintPick pickCard(Player p, int stars) {
        int s = Math.clamp(stars, 1, 3);
        var h = pickHint(p, s);
        return h == null ? null : new HintPick(h, s);
    }

    /** 1성 문장: spell-hints.yml 의 1 → 원소만 막연하게. */
    private String first(SpellHints.Condition c) {
        var hints = spellHints();
        String line = hints == null ? null : hints.line(c.spell(), 1);
        if (line != null) return line;
        String element = elementOf(c.spell());
        return element == null ? "아직 깨치지 못한 마법 하나가 어딘가에서 당신을 기다리고 있는 것 같아요…"
                : "아직 깨치지 못한 " + element + " 마법 하나가 어딘가에서 당신을 기다리고 있는 것 같아요…";
    }

    /** 3성 진행도를 기다리는 최대 시간 (DB 가 느리면 진행도 없이 보여 줌). */
    private static final long PROGRESS_WAIT_TICKS = 60;

    /**
     * 카드 문장 만들기 (타로 기획 #65 · 2026-10-26 spell-hints.yml): 1성 = 파일 1, 2성 = 파일 2(없으면 목격담), 3성 = 발견 조건 + 지금 진행도.
     * done 은 메인 스레드에서 정확히 한 번. 3성 진행도는 MagicDiscovery 에서 읽는다 (접속 중이면 바로, 아니면 DB 비동기, 3초 넘으면 진행도 없이).
     */
    void hintCard(Player p, HintPick pick, java.util.function.Consumer<HintCard> done) {
        var c = pick.condition();
        if (pick.stars() == 1) { done.accept(new HintCard(c.spell(), 1, first(c))); return; }
        if (pick.stars() == 2) {
            String line = second(spellHints(), c, npcMaterials(c.spell()));
            done.accept(new HintCard(c.spell(), 2, line != null ? line : first(c)));
            return;
        }
        String nick = plugin.names() != null ? plugin.names().name(p) : p.getName();
        if (c.manual()) { done.accept(new HintCard(c.spell(), 3, SpellHints.third(c, nick, null))); return; }
        boolean[] sent = {false};
        java.util.function.Consumer<Map<String, Double>> finish = values -> {
            if (sent[0]) return;
            sent[0] = true;
            done.accept(new HintCard(c.spell(), 3, SpellHints.third(c, nick, values)));
        };
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish.accept(null), PROGRESS_WAIT_TICKS);
        DiscoveryLink.hintProgress(p.getUniqueId(), c.spell(), finish);
    }

    private static final Pattern LESSON = Pattern.compile("^(\\S+) 마법 (\\S+) 수업을 통해.*습득$");
    private static final Pattern COUNT = Pattern.compile("(\\d[\\d,]*)\\s*(개|번|회|마리|명|블록|바퀴|종|칸)");
    /** 끝이 "~하다" 로 바뀌는 낱말 (조건 문구 "~ 사용", "~ 달성" 같은 명사 끝). */
    private static final Set<String> HADA = Set.of("사용", "달성", "획득", "처치", "소화", "제작", "설치", "파괴", "이동", "회전", "경험", "배정",
            "착용", "수확", "채굴", "번식", "낚시", "비행", "사냥", "완료", "방문", "구매", "판매", "승리", "연구", "수집", "발견", "탐험", "제거");

    /**
     * ChacaNPC 마법 힌트처럼 마법 이름 없이 목격담으로 돌려 말한다 (PromptBuilder: "그러고 보니 예전에 어떤 마법사가 … ~해서 ~하더라").
     * materials (ChacaNPC hints.yml 목격담 재료) 가 있으면 그것을, 없으면 발견 조건 문구를 숫자를 흐려 "~하더니" 꼴로 바꾼다.
     * 바꿀 수 없는 문구면 null.
     */
    static String witness(String condition, String materials) {
        if (materials != null) return "그러고 보니 예전에 어떤 마법사가 마법을 쓰는 걸 봤어… " + materials + ", 그런 게 떠오르는 장면이었지.";
        String t = condition.strip().replaceAll("[.。!]+$", "").strip();
        Matcher lesson = LESSON.matcher(t);
        if (lesson.matches()) return "그러고 보니 예전에 어떤 마법사가 " + lesson.group(1) + " 마법 " + lesson.group(2) + " 수업 시간에 선생님 어깨너머로 그 마법을 익혔다던데…";
        t = t.replace("누적 ", "");
        Matcher c = COUNT.matcher(t);
        var sb = new StringBuilder();
        while (c.find()) {
            long n;
            try { n = Long.parseLong(c.group(1).replace(",", "")); } catch (NumberFormatException e) { n = 2; }
            String unit = c.group(2).equals("회") ? "번" : c.group(2);
            boolean times = unit.equals("번") || unit.equals("바퀴");
            String vague = n <= 1 ? "한 " + unit : n < 10 ? "몇 " + unit : times ? (n >= 100 ? "수없이" : "여러 " + unit) : (n >= 100 ? "잔뜩" : "여러 " + unit);
            c.appendReplacement(sb, Matcher.quoteReplacement(vague));
        }
        c.appendTail(sb);
        t = sb.toString();
        String verb = deoni(t);
        return verb == null ? null : "그러고 보니 예전에 어떤 마법사가 " + verb + " 새 마법을 깨쳤다던데…";
    }

    /** "~하기" / "~합니다" / "~습니다" / "~ 사용" → "~하더니". 모르는 끝이면 null. */
    private static String deoni(String t) {
        if (t.endsWith("기") && t.length() > 1) return t.substring(0, t.length() - 1) + "더니";
        if (t.endsWith("습니다")) return t.substring(0, t.length() - 3) + "더니";
        if (t.endsWith("니다") && t.length() > 2) {
            char last = t.charAt(t.length() - 3);
            if (last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 == 17)   // 받침 ㅂ (합니다, 찔립니다)
                return t.substring(0, t.length() - 3) + (char) (last - 17) + "더니";
            return null;
        }
        int sp = t.lastIndexOf(' ');
        String word = sp < 0 ? t : t.substring(sp + 1);
        return HADA.contains(word) ? t + "하더니" : null;
    }

    // ------------------------------------------------------------------ 382 배낭 정리 (pure part, BagHuskCoordinator.sortPages)

    /**
     * 모든 쪽의 아이템을 합치고(같은 아이템, 최대 묶음까지) 종류별로 정렬해 1쪽부터 채운다. pages[i] = 27칸 (null = 빈칸).
     * null = 이미 정리돼 있음. 정리 전후 아이템 총량이 정확히 같지 않으면 IllegalStateException (아무것도 바꾸지 않음).
     */
    static ItemStack[][] arrange(ItemStack[][] pages) {
        var all = new ArrayList<ItemStack>();
        for (var page : pages) for (var it : page) if (it != null && !it.getType().isAir() && it.getAmount() > 0) all.add(it.clone());
        var merged = new ArrayList<ItemStack>();
        for (var it : all) {
            int left = it.getAmount();
            for (var m : merged) {
                if (left <= 0) break;
                int max = m.getMaxStackSize();
                if (m.getAmount() < max && m.isSimilar(it)) { int add = Math.min(left, max - m.getAmount()); m.setAmount(m.getAmount() + add); left -= add; }
            }
            while (left > 0) { var c = it.clone(); int n = Math.min(left, Math.max(1, c.getMaxStackSize())); c.setAmount(n); merged.add(c); left -= n; }
        }
        merged.sort(Comparator.comparingInt(LifeSpells::group).thenComparing(LifeSpells::kind)
                .thenComparing(AlchemyBridge::displayName).thenComparing(Comparator.comparingInt(ItemStack::getAmount).reversed()));
        int size = pages.length * BagPageCodec.SLOTS;
        if (merged.size() > size) throw new IllegalStateException("bag sort overflow");
        var out = new ItemStack[pages.length][BagPageCodec.SLOTS];
        for (int i = 0; i < merged.size(); i++) out[i / BagPageCodec.SLOTS][i % BagPageCodec.SLOTS] = merged.get(i);
        if (!totals(pages).equals(totals(out))) throw new IllegalStateException("bag sort changed the item totals");
        boolean same = true;
        for (int i = 0; i < pages.length && same; i++) for (int s = 0; s < BagPageCodec.SLOTS; s++) {
            ItemStack a = s < pages[i].length ? pages[i][s] : null, b = out[i][s];
            boolean ea = a == null || a.getType().isAir() || a.getAmount() <= 0, eb = b == null;
            if (ea != eb || (!ea && (a.getAmount() != b.getAmount() || !a.isSimilar(b)))) { same = false; break; }
        }
        return same ? null : out;
    }

    /** 장비(1개 묶음) → 재료 → 블록 → 음식. */
    private static int group(ItemStack it) {
        Material m = it.getType();
        if (it.getMaxStackSize() == 1) return 0;
        if (m.isEdible()) return 3;
        if (m.isBlock()) return 2;
        return 1;
    }

    private static String kind(ItemStack it) {
        String ia = itemsAdder == null ? null : itemsAdder.idOf(it);
        return ia != null ? ia : it.getType().getKey().toString();
    }

    /** exact multiset: serialized single item → total amount. */
    private static Map<String, Long> totals(ItemStack[][] pages) {
        var out = new HashMap<String, Long>();
        for (var page : pages) for (var it : page) {
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            var one = it.clone(); one.setAmount(1);
            out.merge(Base64.getEncoder().encodeToString(one.serializeAsBytes()), (long) it.getAmount(), Long::sum);
        }
        return out;
    }
}
