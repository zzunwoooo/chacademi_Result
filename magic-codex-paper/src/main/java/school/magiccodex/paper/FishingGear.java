package school.magiccodex.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.Piece;

/**
 * Fishing gear (owner 2026-10-20): the items are ItemsAdder items (fishing-gear.yml itemsadder: item:fishing_rod_1,
 * item:lure_1, item:fishing_line_1, item:bait_1), and their state lives in the database, not in the item
 * ("내구도 같은건 nbt가 아니라 내부 데이터 값으로, db에 저장"): a gear item carries only its row id (PDC fishing_uid)
 * and FishingGearStore keeps durability and what is fitted to a rod. Baits are plain stackable items until fitted.
 *
 * A vanilla fishing rod is the default set (fishing-gear.yml default: true) and never wears out; it gets a row only
 * once something is fitted to it. Items from the earlier build (PDC fishing_kind / fishing_id) are still recognized.
 * Lore shows the current numbers (display only; the database is the truth).
 */
final class FishingGear {
    static final int[] GRADE_RGB = {0xDCE3EA, 0x71E5F2, 0xD2A8FF, 0xFFD582};
    private final NamespacedKey kind, id, dur, uid, fFloat, fFloatDur, fLine, fLineDur, fBait, fBaitCount, fish, size, tankId, caught, itemToken;
    private FishingConfig config;
    private final ItemsAdderItems itemsAdder;
    private final FishingGearStore store;
    private final java.util.logging.Logger log;
    private boolean warnedNoItemsAdder;

    /** One fitted piece: gear + durability (-1 = endless) or bait count (-1 = endless default). fallback = not a real item. */
    record Part(FishingConfig.Gear gear, int value, boolean fallback) {}
    /** What the held rod carries. */
    record Loadout(Part rod, Part flt, Part line, Part bait) {
        Part part(int k) { return switch (k) { case 0 -> rod; case 1 -> flt; case 2 -> line; default -> bait; }; }
    }

    FishingGear(Plugin plugin, FishingConfig config, ItemsAdderItems itemsAdder, FishingGearStore store) {
        this.config = config; this.itemsAdder = itemsAdder; this.store = store; this.log = plugin.getLogger();
        kind = new NamespacedKey(plugin, "fishing_kind"); id = new NamespacedKey(plugin, "fishing_id"); dur = new NamespacedKey(plugin, "fishing_dur");
        uid = new NamespacedKey(plugin, "fishing_uid");
        fFloat = new NamespacedKey(plugin, "fishing_float"); fFloatDur = new NamespacedKey(plugin, "fishing_float_dur");
        fLine = new NamespacedKey(plugin, "fishing_line"); fLineDur = new NamespacedKey(plugin, "fishing_line_dur");
        fBait = new NamespacedKey(plugin, "fishing_bait"); fBaitCount = new NamespacedKey(plugin, "fishing_bait_count");
        fish = new NamespacedKey(plugin, "fishing_fish"); size = new NamespacedKey(plugin, "fishing_size");
        tankId = new NamespacedKey(plugin, "fishing_tank_id"); caught = new NamespacedKey(plugin, "fishing_caught"); itemToken = new NamespacedKey(plugin, "fishing_item_token");
    }
    void config(FishingConfig c) { config = c; }
    FishingGearStore store() { return store; }

    // ------------------------------------------------------------------ reading

    static boolean isRod(ItemStack item) { return item != null && item.getType() == Material.FISHING_ROD; }

    /** The gear definition of an item: ItemsAdder id first, then the old PDC id; a plain rod = the default rod; else null. */
    FishingConfig.Gear gearOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String ia = itemsAdder.idOf(item);
        if (ia != null) { var g = config.gearByItem.get(ia); if (g != null) return g; }
        var meta = item.getItemMeta();
        if (meta != null) {
            var pdc = meta.getPersistentDataContainer();
            Integer k = pdc.get(kind, PersistentDataType.INTEGER);
            String i = pdc.get(id, PersistentDataType.STRING);
            if (k != null && k >= 0 && k <= 3 && i != null) { var g = config.gear(k, i); if (g != null) return g; }
        }
        return isRod(item) ? config.fallback[0] : null;
    }
    /** Gear kind of an item (0..3), -1 = not fishing gear. A plain fishing rod counts as a rod. */
    int kindOf(ItemStack item) { var g = gearOf(item); return g == null ? -1 : g.kind(); }
    /** A vanilla rod (no gear item of its own): the default set. */
    boolean plainRod(ItemStack item) {
        if (!isRod(item)) return false;
        if (itemsAdder.idOf(item) != null && config.gearByItem.containsKey(itemsAdder.idOf(item))) return false;
        var meta = item.getItemMeta();
        return meta == null || !meta.getPersistentDataContainer().has(id, PersistentDataType.STRING);
    }
    String uidOf(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        return item.getItemMeta().getPersistentDataContainer().get(uid, PersistentDataType.STRING);
    }
    /** The item's database state is known (no row id yet, or loaded): gear decisions may use it. */
    boolean ready(ItemStack item) { return store.known(uidOf(item)); }
    /** Loads the rows of these items (gear only), then runs done. */
    void load(Collection<ItemStack> items, Runnable done) {
        var uids = new ArrayList<String>();
        for (ItemStack it : items) { String u = uidOf(it); if (u != null) uids.add(u); }
        store.load(uids, done);
    }
    /** Durability of a gear item (-1 = none/endless). Row first; a fresh item is full; old items keep their PDC value. */
    int durabilityOf(ItemStack item) {
        var g = gearOf(item);
        if (g == null || g.kind() == FishingProtocol.BAIT || plainRod(item)) return -1;
        var row = store.row(uidOf(item));
        if (row != null) return row.durability();
        Integer d = item.getItemMeta() == null ? null : item.getItemMeta().getPersistentDataContainer().get(dur, PersistentDataType.INTEGER);
        return d != null ? d : g.durability();
    }

    Loadout read(ItemStack rod) {
        var fb = config.fallback;
        if (!isRod(rod)) return new Loadout(new Part(fb[0], -1, true), new Part(fb[1], -1, true), new Part(fb[2], -1, true), new Part(fb[3], -1, true));
        var row = store.row(uidOf(rod));
        Part r;
        var rg = gearOf(rod);
        if (plainRod(rod) || rg == null) r = new Part(fb[0], -1, true);
        else r = new Part(rg, Math.max(0, durabilityOf(rod)), false);
        if (row == null) {
            // Legacy rods keep their fitted pieces until the first DB write migrates this complete loadout.
            // Retain the old PDC as a migration fallback; never replace it with empty/default parts.
            var pdc = rod.getItemMeta().getPersistentDataContainer();
            return new Loadout(r, legacyPart(pdc, 1, fFloat, fFloatDur), legacyPart(pdc, 2, fLine, fLineDur), legacyPart(pdc, 3, fBait, fBaitCount));
        }
        return new Loadout(r, part(1, row.floatId(), row.floatDur()), part(2, row.lineId(), row.lineDur()), part(3, row.baitId(), row.baitCount()));
    }
    private Part legacyPart(PersistentDataContainer pdc, int k, NamespacedKey idKey, NamespacedKey valueKey) {
        var g = config.gear(k, pdc.get(idKey, PersistentDataType.STRING));
        if (g == null) return new Part(config.fallback[k], -1, true);
        Integer stored = pdc.get(valueKey, PersistentDataType.INTEGER);
        int value = stored == null ? (k == 3 ? 0 : g.durability()) : stored;
        if (k == 3 && value <= 0) return new Part(config.fallback[k], -1, true);
        return new Part(g, Math.max(0, value), false);
    }
    private Part part(int k, String gearId, int value) {
        var g = config.gear(k, gearId == null || gearId.isEmpty() ? null : gearId);
        if (g == null || (k == 3 && value <= 0)) return new Part(config.fallback[k], -1, true);
        return new Part(g, Math.max(0, value), false);
    }

    // ------------------------------------------------------------------ writing

    private String ensureUid(ItemStack item) {
        String u = uidOf(item);
        if (u != null) return u;
        u = UUID.randomUUID().toString();
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(uid, PersistentDataType.STRING, u);
        item.setItemMeta(meta);
        return u;
    }

    /** Stores the loadout of this rod in the database (a plain rod gets a row id now) and refreshes its lore. */
    void write(ItemStack rod, Loadout l) {
        String u = ensureUid(rod);
        store.put(new FishingGearStore.Row(u, 0, l.rod().gear().id(), l.rod().fallback() ? -1 : l.rod().value(),
                id(l.flt()), value(l.flt()), id(l.line()), value(l.line()), id(l.bait()), value(l.bait())));
        decorate(rod);
    }
    private static String id(Part p) { return p.fallback() ? "" : p.gear().id(); }
    private static int value(Part p) { return p.fallback() ? 0 : p.value(); }

    /** Sets the durability of a single gear item (float / line in the inventory, or a rod's own). */
    void setDurability(ItemStack item, int durability) {
        var g = gearOf(item);
        if (g == null || g.kind() == FishingProtocol.BAIT || plainRod(item)) return;
        String u = ensureUid(item);
        var row = store.row(u);
        if (row == null && g.kind() == FishingProtocol.ROD && isRod(item)) {
            var l = read(item);
            row = new FishingGearStore.Row(u, g.kind(), g.id(), durability, id(l.flt()), value(l.flt()), id(l.line()), value(l.line()), id(l.bait()), value(l.bait()));
        }
        store.put(row == null ? new FishingGearStore.Row(u, g.kind(), g.id(), durability, "", 0, "", 0, "", 0) : row.withDurability(durability));
        decorate(item);
    }

    /**
     * A new gear item: the ItemsAdder item (fishing-gear.yml itemsadder). durability -1 = full. Rods, floats and lines
     * get their own database row; baits stack (amount). Without ItemsAdder an old-style item is made instead.
     */
    ItemStack create(FishingConfig.Gear g, int durability, int amount) {
        ItemStack item = g.itemsadder().isEmpty() ? null : itemsAdder.create(g.itemsadder(), g.kind() == 3 ? Math.clamp(amount, 1, 64) : 1);
        if (item == null) {
            if (!warnedNoItemsAdder) { warnedNoItemsAdder = true; log.warning("낚시 장비 ItemsAdder 아이템을 만들 수 없어 기본 아이템으로 대신합니다: " + g.itemsadder()); }
            Material base = switch (g.kind()) { case 0 -> Material.FISHING_ROD; case 3 -> Material.SLIME_BALL; default -> Material.PAPER; };
            item = new ItemStack(base, g.kind() == 3 ? Math.clamp(amount, 1, 64) : 1);
            var meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(kind, PersistentDataType.INTEGER, g.kind());
            meta.getPersistentDataContainer().set(id, PersistentDataType.STRING, g.id());
            meta.displayName(plain(g.name(), GRADE_RGB[g.grade()]));
            item.setItemMeta(meta);
        }
        if (g.kind() != 3) {
            String u = ensureUid(item);
            store.put(new FishingGearStore.Row(u, g.kind(), g.id(), durability < 0 ? g.durability() : durability, "", 0, "", 0, "", 0));
        }
        decorate(item);
        return item;
    }

    /** Lore with the current numbers, unbreakable rod, single floats/lines. Safe to call again any time. */
    void decorate(ItemStack item) {
        var g = gearOf(item);
        if (g == null) return;
        boolean rod = isRod(item);
        var meta = item.getItemMeta();
        var lore = new ArrayList<Component>();
        if (!plainRod(item)) {
            lore.add(plain(FishingProtocol.GRADE_LABELS[g.grade()] + " " + FishingProtocol.KIND_LABELS[g.kind()], GRADE_RGB[g.grade()]));
            lore.add(plain(stats(g), 0xAFC2D6));
            if (g.kind() != 3) {
                int d = durabilityOf(item);
                lore.add(plain(d <= 0 ? "고장 (수리 필요)" : "내구도 " + d + " / " + g.durability(), d <= 0 ? 0xFF9E9E : 0xAFC2D6));
            }
            if (!g.specialText().isBlank()) lore.add(plain(g.specialText(), 0xF2D9A0));
            if (!g.description().isBlank()) lore.add(plain(g.description(), 0x8D9AAD));
        }
        if (rod) {
            // 오너 2026-10-25: 낚싯대 설명은 낚싯대 자체 성능·설명만. 찌·낚싯줄·미끼는 낚시 장비 창에서 본다.
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        } else if (g.kind() != 3) {
            meta.setMaxStackSize(1);
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    static String stats(FishingConfig.Gear g) {
        return switch (g.kind()) {
            case 0 -> "감는 힘 " + num(g.a()) + " · 챔질 " + num(g.b()) + "초";
            case 1 -> "가라앉는 속도 " + num(g.a()) + "m/초 · 감도 " + num(g.b()) + "초";
            case 2 -> "최대 길이 " + num(g.a()) + "m · 최대 장력 " + num(g.b());
            default -> "희귀 보정 +" + num(g.a()) + "% · 입질 대기 -" + num(g.b()) + "%";
        };
    }
    static String num(float v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 10) / 10f); }

    static Component plain(String text, int rgb) {
        return Component.text(text).color(TextColor.color(rgb)).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ fish items

    /**
     * A fish item for one fish (taken out of the tank, or an admin gift): the ItemsAdder fish (fishing-fish.yml
     * itemsadder, item:fish_1..200). It carries only the species and its row id (PDC); size and caught date live in the
     * database (FishTank codex_fish_item) and are shown in the lore. Putting it back moves the row back into the tank,
     * so a duplicated item finds no row and is refused.
     */
    /** Compatibility helper; callers that issue a new item must register its row before delivery. */
    ItemStack fish(FishingConfig.Fish f, float cm) { return fish(f, cm, UUID.randomUUID().toString(), System.currentTimeMillis()); }
    ItemStack fish(FishingConfig.Fish f, float cm, String tank, long caughtAt) { return fish(f, cm, tank, caughtAt, ""); }
    ItemStack fish(FishingConfig.Fish f, float cm, String tank, long caughtAt, String token) {
        ItemStack item = f.itemsadder().isEmpty() ? null : itemsAdder.create(f.itemsadder(), 1);
        if (item == null) {
            Material base = Material.matchMaterial(f.item());
            if (base == null || !base.isItem()) base = Material.COD;
            item = new ItemStack(base);
            var meta = item.getItemMeta();
            meta.displayName(plain(f.name(), GRADE_RGB[f.grade()]));
            item.setItemMeta(meta);
        }
        var meta = item.getItemMeta();
        meta.lore(List.of(plain(FishingProtocol.GRADE_LABELS[f.grade()] + " 물고기", GRADE_RGB[f.grade()]),
                plain("크기 " + String.format(Locale.ROOT, "%.1f", cm) + "cm", 0xAFC2D6),
                plain("잡은 날짜 " + new java.text.SimpleDateFormat("yyyy.MM.dd").format(new Date(caughtAt)), 0xAFC2D6),
                plain(f.description(), 0x8D9AAD),
                plain("아공간 수조에 다시 넣을 수 있어요 (판매소에서는 팔 수 없어요)", 0x7E8BA0)));
        var pdc = meta.getPersistentDataContainer();
        pdc.set(fish, PersistentDataType.STRING, f.id());
        pdc.set(tankId, PersistentDataType.STRING, tank);
        if (token != null && !token.isEmpty()) pdc.set(itemToken, PersistentDataType.STRING, token);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }
    /** The fish id of a caught fish item, or null (for shops / the housing aquarium later). */
    String fishOf(ItemStack item) {
        if (item == null || item.getAmount() < 1 || item.getType().isAir()) return null;
        var meta = item.getItemMeta();
        String species = meta == null ? null : meta.getPersistentDataContainer().get(fish, PersistentDataType.STRING);
        if (species != null) return species;
        String ia = itemsAdder.idOf(item);
        var def = ia == null ? null : config.fishByItem.get(ia);
        return def == null ? null : def.id();
    }
    /**
     * Fish item data: species, tank row id ("" for old items), and for items from before the database (no row id) the
     * size / caught date they carried (0 = unknown). Null when not a fish item.
     */
    record HeldFish(String fish, float size, String tankId, long caughtAt, String token) {
        HeldFish(String fish, float size, String tankId, long caughtAt) { this(fish, size, tankId, caughtAt, ""); }
    }
    HeldFish heldFish(ItemStack item) {
        if (item == null || item.getAmount() < 1 || item.getType().isAir()) return null;
        var meta = item.getItemMeta();
        var pdc = meta == null ? null : meta.getPersistentDataContainer();
        String f = fishOf(item);
        if (f == null) return null;
        Float cm = pdc == null ? null : pdc.get(size, PersistentDataType.FLOAT);
        String rowId = pdc == null ? null : pdc.get(tankId, PersistentDataType.STRING);
        Long at = pdc == null ? null : pdc.get(caught, PersistentDataType.LONG);
        String token = pdc == null ? "" : pdc.getOrDefault(itemToken, PersistentDataType.STRING, "");
        // New row-backed fish have no size/date in PDC: the DB remains authoritative.
        // Legacy fish without a row id still require a finite positive size before migration.
        if (cm != null && (!Float.isFinite(cm) || cm <= 0)) return null;
        if (cm == null && (rowId == null || rowId.isEmpty())) return null;
        return new HeldFish(f, cm == null ? 0 : cm, rowId == null ? "" : rowId, at == null ? 0 : at, token);
    }

    // ------------------------------------------------------------------ protocol

    Piece piece(int k, Part p) {
        var g = p.gear();
        int max = k == 3 || p.fallback() ? -1 : g.durability();
        int d = k == 3 || p.fallback() ? -1 : p.value();
        int count = k == 3 ? (p.fallback() ? -1 : p.value()) : 0;
        String special = g.special().length() > FishingProtocol.MAX_SPECIAL ? g.special().substring(0, FishingProtocol.MAX_SPECIAL) : g.special();
        return new Piece(k, g.id(), cut(g.name()), g.grade(), d, max, count, p.fallback(), special);
    }
    FishingProtocol.Gear protocol(Loadout l) {
        var pieces = new ArrayList<Piece>();
        for (int k = 0; k < 4; k++) pieces.add(piece(k, l.part(k)));
        return new FishingProtocol.Gear(pieces, l.rod().gear().a(), l.rod().gear().b(), l.flt().gear().a(), l.flt().gear().b(),
                l.line().gear().a(), l.line().gear().b());
    }
    static String cut(String s) { return s.length() > FishingProtocol.MAX_NAME ? s.substring(0, FishingProtocol.MAX_NAME) : s; }
}
