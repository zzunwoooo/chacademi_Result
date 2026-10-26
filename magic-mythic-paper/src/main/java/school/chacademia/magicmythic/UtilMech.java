package school.chacademia.magicmythic;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * chautil{type=...} - item, block and player utilities.
 *
 *  forge                 - hand item -> forging table (config flame-forging.recipes); no matching recipe = fail (036)
 *  vein max=8            - mine the aimed ore and connected ores of the same kind with the held tool: tool rules,
 *                          enchantments and durability apply; bare hand = no drops (110 광맥 파쇄)
 *  age                   - aimed stone block becomes a random weathered variant (mossy / cracked), shape kept (112)
 *  mend                  - fully repair the ordinary tool in hand (144 마력 봉합)
 *  mine ticks=400        - for `ticks`, breaking a block with a pickaxe also breaks the 3x3 face around it (140 마력 각인)
 *  survey range=48       - action bar: coordinates of the aimed block and distance to it (155 별빛 측량)
 *  return                - teleport the caster alone to the village return point (config return-point) (145 동행 귀환)
 *  text range=12 ticks=100 wait=600 - the next chat line the caster types (within wait ticks) is shown as glowing
 *                          text at the aim point for `ticks` (151 빛의 필사; a proper input box is a client proposal)
 *  potion                - give one mana potion (ItemsAdder item:mana_potion, vanilla fallback); drinking it
 *                          restores config mana-potion.restore mana (159 마력 예금; Potions — 쿨 mana-potion.cooldown-seconds)
 *  exp ratio=0.5         - spend all current mana, gain mana x ratio experience points (161 경험의 환전)
 *  deepdark depth=64     - is there a Deep Dark biome below the caster? answer only by VFX event found/absent (114)
 *  star                  - point at the current story step's target (ChacademyStory arrow); recast turns it off (132 길잡이 별)
 *  reprise               - reset the cooldown of one random other equipped spell (163 별빛 재현; needs the Bridge
 *                          cooldown API and the client slot report, see handoff)
 *  core ticks=6000 speed range radius aoe - first cast gathers a stone core, the next cast fires it (131 원초의 돌)
 *  truesight r=16 ticks=300 - invisible enemies near the caster glow for the duration; MagicRevealEvent lets
 *                          puzzles show hidden clues (137 진실의 눈)
 *  taunt r=10 ticks=100  - mobs around target the caster, bosses included (156 빛의 교대)
 */
final class UtilMech extends Mech {
    UtilMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false;
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "forge" -> forge(pl);
            case "vein" -> vein(pl, c.at());
            case "age" -> age(pl, c.at());
            case "mend" -> mend(pl);
            case "mine" -> State.get().mine(pl, Math.clamp(p.i("ticks", 400), 1, 20 * 600), p.s("spell", ""));
            case "survey" -> survey(pl);
            case "return" -> back(pl);
            case "text" -> State.get().awaitText(plugin, pl, aimPoint(pl, c.at(), p.d("range", 12)), Math.clamp(p.i("ticks", 100), 1, 20 * 60),
                Math.clamp(p.i("wait", 600), 20, 20 * 120), p.s("spell", ""));
            case "potion" -> potion(pl);
            case "exp" -> exp(pl);
            case "deepdark" -> deepDark(pl);
            case "star" -> star(pl);
            case "reprise" -> reprise(pl);
            case "core" -> core(pl, c);
            case "truesight" -> trueSight(pl);
            case "taunt" -> taunt(pl);
            default -> false;
        };
    }

    private void vfx(Player pl, String event, Location at, UUID target) {
        String spell = p.s("spell", "");
        if (!spell.isBlank()) Links.vfx(pl, spell, event, at, target);
    }
    /** 할 수 없는 행동 → 경고 알림 (같은 문구는 2초 안에 다시 안 보냄). */
    private static void bar(Player pl, String text) { ChacaLink.notify(pl, "warning", "magic-mythic:" + text, text, "", 2000); }
    /** 결과 안내 → 정보 알림. */
    private static void info(Player pl, String text) { ChacaLink.notify(pl, "system", "magic-mythic:" + text, text, "", 1500); }
    /** 계속 바뀌는 실시간 표시(길잡이 별 방향·거리)만 액션바. */
    private static void live(Player pl, String text) { pl.sendActionBar(Component.text(text, NamedTextColor.GOLD)); }

    private static Block aimedSolid(Player pl, Location at) {
        Block b = at.getBlock();
        if (!b.getType().isAir()) return b;
        Vector dir = at.toVector().subtract(pl.getEyeLocation().toVector());
        if (dir.lengthSquared() < 1e-4) return b;
        return at.clone().add(dir.normalize().multiply(0.35)).getBlock();
    }
    /** The Bridge passes the caster position for SELF/BLOCK routes: trace the look ourselves when needed. */
    static Location aimPoint(Player pl, Location given, double range) {
        if (given != null && given.getWorld() != null && given.distanceSquared(pl.getLocation()) > 0.25) return given;
        var hit = pl.rayTraceBlocks(range, FluidCollisionMode.NEVER);
        return hit != null ? hit.getHitPosition().toLocation(pl.getWorld()) : pl.getEyeLocation().add(pl.getEyeLocation().getDirection().multiply(range));
    }
    static Block aimBlock(Player pl, double range) {
        var hit = pl.rayTraceBlocks(range, FluidCollisionMode.NEVER);
        return hit == null ? null : hit.getHitBlock();
    }

    // ------------------------------------------------------------------ 036 flame forging
    private boolean forge(Player pl) {
        var table = plugin.getConfig().getConfigurationSection("flame-forging.recipes");
        ItemStack hand = pl.getInventory().getItemInMainHand();
        if (table == null || table.getKeys(false).isEmpty()) { bar(pl, "단조 재료표가 아직 없습니다."); return false; }
        String raw = table.getString(hand.getType().name());
        if (raw == null) { bar(pl, "이 재료로는 단조할 수 없습니다."); vfx(pl, "external:fail", pl.getLocation(), null); return false; }
        // "result[:amount[:need]]" - result may itself contain ':' (ItemsAdder id), so numbers are read from the right
        var parts = new ArrayList<>(List.of(raw.trim().split(":")));
        var nums = new ArrayList<Integer>();
        while (parts.size() > 1 && nums.size() < 2 && parts.getLast().matches("\\d{1,4}")) nums.addFirst(Integer.parseInt(parts.removeLast()));
        String id = String.join(":", parts);
        int amount = nums.isEmpty() ? 1 : nums.getFirst(), need = nums.size() > 1 ? nums.get(1) : 1;
        if (amount <= 0 || need <= 0) return false;
        ItemStack out = Links.customItem(id);
        if (out == null) { Material m = Material.matchMaterial(id); if (m == null) return false; out = new ItemStack(m); }
        if (hand.getAmount() < need) return false;
        out.setAmount(amount);
        hand.setAmount(hand.getAmount() - need);
        pl.getInventory().addItem(out).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        vfx(pl, "external:success", pl.getLocation(), null);
        return true;
    }

    // ------------------------------------------------------------------ 110 vein
    static boolean ore(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }

    private boolean vein(Player pl, Location at) {
        Block start = aimedSolid(pl, at);
        if (!ore(start.getType())) start = Optional.ofNullable(aimBlock(pl, 6)).orElse(start);
        Material kind = start.getType();
        if (!ore(kind)) return false;
        ItemStack tool = pl.getInventory().getItemInMainHand();
        boolean bare = tool.getType().isAir();
        int max = Math.clamp(p.i("max", 8), 1, 64), done = 0;
        var queue = new ArrayDeque<Block>(List.of(start));
        var seen = new HashSet<Block>();
        while (!queue.isEmpty() && done < max) {
            Block b = queue.poll();
            if (!seen.add(b) || b.getType() != kind) continue;
            if (!Targets.canBreak(pl, b)) continue;
            if (bare) b.setType(Material.AIR); else b.breakNaturally(tool, true);
            vfx(pl, "external:fracture", b.getLocation().add(0.5, 0.5, 0.5), null);
            done++;
            if (!bare && pl.getGameMode() != GameMode.CREATIVE) {
                pl.damageItemStack(EquipmentSlot.HAND, 1);
                tool = pl.getInventory().getItemInMainHand();
                if (tool.getType().isAir()) break; // broke
            }
            for (BlockFace f : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
                queue.add(b.getRelative(f));
        }
        return done > 0;
    }

    // ------------------------------------------------------------------ 112 weathering
    static final Map<String, List<String>> WEATHER = Map.ofEntries(
        Map.entry("STONE_BRICKS", List.of("MOSSY_STONE_BRICKS", "CRACKED_STONE_BRICKS")),
        Map.entry("STONE_BRICK_STAIRS", List.of("MOSSY_STONE_BRICK_STAIRS")),
        Map.entry("STONE_BRICK_SLAB", List.of("MOSSY_STONE_BRICK_SLAB")),
        Map.entry("STONE_BRICK_WALL", List.of("MOSSY_STONE_BRICK_WALL")),
        Map.entry("COBBLESTONE", List.of("MOSSY_COBBLESTONE")),
        Map.entry("COBBLESTONE_STAIRS", List.of("MOSSY_COBBLESTONE_STAIRS")),
        Map.entry("COBBLESTONE_SLAB", List.of("MOSSY_COBBLESTONE_SLAB")),
        Map.entry("COBBLESTONE_WALL", List.of("MOSSY_COBBLESTONE_WALL")),
        Map.entry("INFESTED_STONE_BRICKS", List.of("INFESTED_MOSSY_STONE_BRICKS", "INFESTED_CRACKED_STONE_BRICKS")),
        Map.entry("DEEPSLATE_BRICKS", List.of("CRACKED_DEEPSLATE_BRICKS")),
        Map.entry("DEEPSLATE_TILES", List.of("CRACKED_DEEPSLATE_TILES")),
        Map.entry("POLISHED_BLACKSTONE_BRICKS", List.of("CRACKED_POLISHED_BLACKSTONE_BRICKS")),
        Map.entry("NETHER_BRICKS", List.of("CRACKED_NETHER_BRICKS")));

    private boolean age(Player pl, Location at) {
        Block b = aimedSolid(pl, at);
        var options = WEATHER.get(b.getType().name());
        if (options == null) return false;
        if (!Targets.canBreak(pl, b)) return false;
        String next = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        Material m = Material.matchMaterial(next);
        if (m == null) return false;
        // keep stair facing, slab half, wall sides...: rewrite the block data string with the new material name
        String data = b.getBlockData().getAsString().replace("minecraft:" + b.getType().getKey().getKey(), "minecraft:" + m.getKey().getKey());
        BlockData nd;
        try { nd = Bukkit.createBlockData(data); } catch (IllegalArgumentException e) { nd = m.createBlockData(); }
        b.setBlockData(nd);
        vfx(pl, next.startsWith("MOSSY") || next.contains("_MOSSY_") ? "external:weatherMoss" : "external:weatherCrack", b.getLocation().add(0.5, 0.5, 0.5), null);
        return true;
    }

    // ------------------------------------------------------------------ 144 mend
    static boolean tool(Material m) {
        return Tag.ITEMS_PICKAXES.isTagged(m) || Tag.ITEMS_AXES.isTagged(m) || Tag.ITEMS_SHOVELS.isTagged(m) || Tag.ITEMS_HOES.isTagged(m)
            || m == Material.SHEARS || m == Material.FISHING_ROD || m == Material.FLINT_AND_STEEL || m == Material.BRUSH;
    }
    private boolean mend(Player pl) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        if (!tool(hand.getType()) || !(hand.getItemMeta() instanceof Damageable meta) || meta.getDamage() <= 0) return false;
        meta.setDamage(0);
        hand.setItemMeta(meta);
        vfx(pl, "external:arcane_mending_complete", pl.getLocation(), null);
        return true;
    }

    // ------------------------------------------------------------------ 155 survey
    private boolean survey(Player pl) {
        Block b = aimBlock(pl, Math.clamp(p.d("range", 48), 1, 128));
        if (b == null) return false;
        double dist = pl.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5));
        info(pl, String.format("X %d  Y %d  Z %d  ·  거리 %.1f블록", b.getX(), b.getY(), b.getZ(), dist));
        vfx(pl, "external:survey", b.getLocation().add(0.5, 1, 0.5), null);
        return true;
    }

    // ------------------------------------------------------------------ 145 return
    static Location returnPoint(MagicMythicPlugin plugin, World fallback) {
        var s = plugin.getConfig().getConfigurationSection("return-point");
        if (s != null && s.getString("world") != null) {
            World w = Bukkit.getWorld(s.getString("world"));
            if (w != null) return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            return null;
        }
        World main = Bukkit.getWorlds().isEmpty() ? fallback : Bukkit.getWorlds().getFirst();
        return main.getSpawnLocation();
    }
    private boolean back(Player pl) {
        Location to = returnPoint(plugin, pl.getWorld());
        if (to == null || !plugin.protection().at(to)) { bar(pl, "귀환 지점에 갈 수 없습니다."); return false; }
        if (BroomLink.riding(pl)) BroomLink.stop(pl);
        Location from = pl.getLocation();
        vfx(pl, "external:depart", from, null);
        boolean ok = pl.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (ok) { pl.setFallDistance(0); vfx(pl, "external:arrive", to, null); }
        return ok;
    }

    // ------------------------------------------------------------------ 159 mana potion
    static final String POTION_ID = "item:mana_potion";
    static NamespacedKey potionKey() { return new NamespacedKey("chacademiamagicmythic", "mana_potion"); }

    static ItemStack manaPotion() {
        ItemStack it = Links.customItem(POTION_ID);
        if (it == null) {
            it = new ItemStack(Material.POTION);
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta) {
                meta.setColor(Color.fromRGB(0x3B7BFF));
                meta.displayName(Component.text("마나 포션", NamedTextColor.AQUA).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                it.setItemMeta(meta);
            }
        }
        var meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(potionKey(), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }
    static boolean isManaPotion(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (POTION_ID.equals(Links.customId(it))) return true;
        return it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(potionKey(), PersistentDataType.BYTE);
    }
    private boolean potion(Player pl) {
        pl.getInventory().addItem(manaPotion()).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        vfx(pl, "external:complete", pl.getLocation(), null);
        return true;
    }

    // ------------------------------------------------------------------ 161 experience exchange
    private boolean exp(Player pl) {
        Double mana = Links.mana(pl);
        if (mana == null || mana < 1) return false;
        if (!Links.consume(pl, mana)) return false;
        int xp = (int) Math.floor(mana * Math.max(0, p.d("ratio", 0.5)));
        if (xp > 0) pl.giveExp(xp, false);
        vfx(pl, "external:exchange_absorb", pl.getLocation(), null);
        return true;
    }

    // ------------------------------------------------------------------ 114 deep dark
    private boolean deepDark(Player pl) {
        Location at = pl.getLocation();
        World w = at.getWorld();
        int depth = Math.clamp(p.i("depth", 64), 1, 512), bottom = Math.max(w.getMinHeight(), at.getBlockY() - depth);
        boolean found = false;
        for (int y = at.getBlockY(); y >= bottom && !found; y -= 4) found = w.getBiome(at.getBlockX(), y, at.getBlockZ()) == org.bukkit.block.Biome.DEEP_DARK;
        vfx(pl, found ? "external:found" : "external:absent", at, null);
        return true; // the answer is the effect itself; no text by design
    }

    // ------------------------------------------------------------------ 132 guiding star
    static NamespacedKey starKey() { return new NamespacedKey("chacademiamagicmythic", "guiding_star"); }
    /**
     * 132 길잡이 별 (오너 2026-10-20·10-23): 진행 중인 스토리 단계의 목표(포탈·NPC)를 화살표로 가리킨다 (스토리 모드가 그림,
     * 목표 10블록 안에서 사라짐). 웅크림 위치 저장은 삭제, 다시 시전하면 끈다. 쿨타임은 mana-spells.yml (0).
     */
    private boolean star(Player pl) {
        switch (StoryLink.guide(pl)) {
            case StoryLink.OK -> { vfx(pl, "external:form", pl.getLocation().add(0, 1.2, 0), null); info(pl, "길잡이 별이 퀘스트 목표를 가리킵니다."); return true; }
            case StoryLink.OFF -> { info(pl, "길잡이 별을 껐습니다."); return true; }
            case StoryLink.NEAR -> { bar(pl, "길잡이 별: 퀘스트 목표가 바로 근처에 있어요."); return false; }
            case StoryLink.OTHER_WORLD -> { bar(pl, "길잡이 별: 퀘스트 목표가 다른 곳에 있어요."); return false; }
            default -> { bar(pl, "길잡이 별: 지금은 가리킬 퀘스트 목표가 없어요."); return false; }
        }
    }
    /** Arrow relative to where the player faces. */
    static String arrow(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double target = Math.toDegrees(Math.atan2(-dx, dz)), rel = ((target - from.getYaw()) % 360 + 540) % 360 - 180;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return arrows[(int) Math.floorMod(Math.round(rel / 45.0), 8)];
    }

    // ------------------------------------------------------------------ 163 starlight reprise
    private boolean reprise(Player pl) {
        String self = p.s("spell", "starlight_reprise");
        var slots = plugin.slots();
        var pool = new ArrayList<String>();
        for (String id : slots.list(pl)) if (!id.equals(self)) pool.add(id);
        if (pool.isEmpty()) { bar(pl, slots.known(pl) ? "초기화할 다른 장착 마법이 없습니다." : "장착한 마법 정보를 아직 받지 못했어요."); vfx(pl, "external:fizzle", pl.getLocation(), null); return false; }
        String pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        // The Bridge sets this spell's own cooldown after we return; clearing the other one is independent.
        Boolean done = Links.clearCooldown(pl, pick);
        if (done == null) { plugin.getLogger().warning("별빛 재현: MagicCodexBridge 쿨타임 API(clearCooldown)가 없습니다. 서버 마나 플러그인 업데이트 필요"); bar(pl, "지금은 별빛 재현을 쓸 수 없어요."); vfx(pl, "external:fizzle", pl.getLocation(), null); return false; }
        vfx(pl, done ? "external:absorb" : "external:fizzle", pl.getLocation(), null);
        if (done) info(pl, "별빛 재현: " + plugin.spellNames().label(pick) + " 재사용 가능");
        return done;
    }

    // ------------------------------------------------------------------ 131 primordial stone
    private boolean core(Player pl, Ctx c) {
        String spell = p.s("spell", "primordial_stone");
        var st = State.get();
        Long until = st.cores.get(pl.getUniqueId());
        long now = st.now;
        if (until != null && until >= now) { // second use: fire
            st.cores.remove(pl.getUniqueId());
            fireCore(pl, c);
            if (Links.hasCooldownApi()) {
                // the Bridge charged mana for this use too - the shot itself is free; its cooldown starts now
                double cost = p.d("mana", 0);
                if (cost > 0) Bukkit.getScheduler().runTask(plugin, () -> Links.add(pl, cost));
            }
            return true;
        }
        int ticks = Math.clamp(p.i("ticks", 6000), 20, 20 * 600);
        vfx(pl, "external:gather", pl.getLocation(), null);
        if (!Links.hasCooldownApi()) {
            // Fallback while the Bridge has no cooldown API: the core is fired automatically after gathering.
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (pl.isOnline()) fireCore(pl, c); }, Math.clamp(p.i("auto", 27), 1, 200));
            return true;
        }
        st.cores.put(pl.getUniqueId(), now + ticks);
        // let the player press the key again: clear the cooldown the Bridge sets right after this cast
        Bukkit.getScheduler().runTask(plugin, () -> Links.clearCooldown(pl, spell));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long u = st.cores.get(pl.getUniqueId());
            if (u != null && u <= st.now) { st.cores.remove(pl.getUniqueId()); vfx(pl, "external:dissolve", pl.getLocation(), null); }
        }, ticks + 1);
        return true;
    }
    private void fireCore(Player pl, Ctx c) {
        vfx(pl, "external:launch", pl.getLocation(), null);
        Location aim = pl.getEyeLocation().add(pl.getEyeLocation().getDirection().multiply(p.d("range", 22)));
        var params = new HashMap<String, String>();
        for (String k : List.of("speed", "range", "radius", "aoe", "aoemult", "mult", "knock", "up", "element", "spell")) {
            String v = p.s(k, null);
            if (v != null) params.put(k, v);
        }
        new BoltMech(plugin, new Params(params)).run(new Ctx(c.data(), pl, null, aim, c.damage()));
    }

    // ------------------------------------------------------------------ 137 true sight
    private boolean trueSight(Player pl) {
        double r = p.d("r", 16);
        int ticks = Math.clamp(p.i("ticks", 300), 20, 20 * 120);
        Bukkit.getPluginManager().callEvent(new MagicRevealEvent(pl, pl.getLocation(), r, ticks));
        var shown = new HashSet<UUID>();
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (!pl.isOnline() || age++ >= ticks || !plugin.protection().at(pl.getLocation())) return false;
                if (age % 10 != 1) return true;
                for (LivingEntity e : plugin.targets().enemiesNear(pl, pl.getLocation(), r, 32)) {
                    if (!e.isInvisible() && !e.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) continue;
                    e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 25, 0, false, false, false));
                    if (shown.add(e.getUniqueId())) vfx(pl, "external:true_sight_enemy_reveal", e.getLocation(), e.getUniqueId());
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 156 taunt
    private boolean taunt(Player pl) {
        double r = p.d("r", 10);
        int ticks = Math.clamp(p.i("ticks", 100), 1, 20 * 60);
        var mobs = new ArrayList<Mob>();
        for (LivingEntity e : plugin.targets().enemiesNear(pl, pl.getLocation(), r, 64))
            if (e instanceof Mob m) { m.setTarget(pl); mobs.add(m); vfx(pl, "external:notice", m.getLocation(), m.getUniqueId()); }
        if (mobs.isEmpty()) return false;
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (!pl.isOnline() || pl.isDead() || age++ >= ticks) return false;
                if (age % 10 == 0) for (Mob m : mobs) if (m.isValid() && !pl.equals(m.getTarget()) && plugin.targets().enemy(pl, m)) m.setTarget(pl);
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ shared state + listeners (area mining, chat text, potions)
    static final class State implements org.bukkit.event.Listener {
        private static State instance;
        static State get() { return instance; }
        private final MagicMythicPlugin plugin;
        final Map<UUID, Long> cores = new HashMap<>();
        private final Map<UUID, long[]> mining = new HashMap<>(); // until
        private final Map<UUID, String> miningSpell = new HashMap<>();
        private final Map<UUID, Object[]> texts = new java.util.concurrent.ConcurrentHashMap<>(); // {Location, ticks, until, spell}
        private final List<Entity> shown = new ArrayList<>();
        private boolean breaking = false;
        long now = 0;

        State(MagicMythicPlugin plugin) {
            this.plugin = plugin; instance = this;
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                now++;
                ChannelMech.tickClock();
                if ((now & 127) == 0) {
                    mining.values().removeIf(v -> v[0] < now);
                    texts.values().removeIf(v -> (long) v[2] < now);
                    cores.values().removeIf(v -> v < now);
                    shown.removeIf(e -> !e.isValid());
                }
            }, 1, 1);
        }

        boolean mine(Player pl, int ticks, String spell) {
            mining.put(pl.getUniqueId(), new long[]{now + ticks});
            miningSpell.put(pl.getUniqueId(), spell);
            return true;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void broke(org.bukkit.event.block.BlockBreakEvent e) {
            if (breaking || Targets.probing || !e.isDropItems()) return;
            Player pl = e.getPlayer();
            long[] until = mining.get(pl.getUniqueId());
            if (until == null || until[0] < now) return;
            ItemStack tool = pl.getInventory().getItemInMainHand();
            if (!Tag.ITEMS_PICKAXES.isTagged(tool.getType())) return;
            Block center = e.getBlock();
            var face = pl.getTargetBlockFace(6);
            if (face == null) face = BlockFace.UP;
            var around = plane(center, face);
            breaking = true;
            try {
                for (Block b : around) {
                    if (b.getType().isAir() || b.getType().getHardness() < 0 || !b.isPreferredTool(tool)) continue;
                    if (!plugin.protection().pair(pl, b.getLocation()) || !Targets.canBreak(pl, b)) continue;
                    b.breakNaturally(tool, true);
                    if (pl.getGameMode() != GameMode.CREATIVE) {
                        pl.damageItemStack(EquipmentSlot.HAND, 1);
                        tool = pl.getInventory().getItemInMainHand();
                        if (!Tag.ITEMS_PICKAXES.isTagged(tool.getType())) break;
                    }
                }
            } finally { breaking = false; }
            String spell = miningSpell.getOrDefault(pl.getUniqueId(), "");
            if (!spell.isBlank()) Links.vfx(pl, spell, "external:mine_face", center.getLocation().add(0.5, 0.5, 0.5), null);
        }
        /** The 8 blocks around `center` in the plane facing `face`. */
        static List<Block> plane(Block center, BlockFace face) {
            var out = new ArrayList<Block>();
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                out.add(switch (face) {
                    case UP, DOWN -> center.getRelative(a, 0, b);
                    case NORTH, SOUTH -> center.getRelative(a, b, 0);
                    default -> center.getRelative(0, a, b);
                });
            }
            return out;
        }

        boolean awaitText(MagicMythicPlugin plugin, Player pl, Location at, int ticks, int wait, String spell) {
            if (!plugin.protection().pair(pl, at)) return false;
            texts.put(pl.getUniqueId(), new Object[]{at.clone(), ticks, now + wait, spell});
            ChacaLink.notify(pl, "system", "magic-mythic:light-script", "빛의 필사", "채팅으로 쓸 글씨를 입력하세요.", 1500);
            if (!spell.isBlank()) Links.vfx(pl, spell, "external:gather_begin", pl.getLocation(), null);
            return true;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = true)
        public void chat(io.papermc.paper.event.player.AsyncChatEvent e) {
            Player pl = e.getPlayer();
            Object[] t = texts.remove(pl.getUniqueId());
            if (t == null || (long) t[2] < now) return;
            e.setCancelled(true);
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
            if (text.length() > 48) text = text.substring(0, 48);
            final String line = text;
            Bukkit.getScheduler().runTask(plugin, () -> place(pl, (Location) t[0], (int) t[1], (String) t[3], line, (long) t[2]));
        }
        private void place(Player pl, Location at, int ticks, String spell, String text, long until) {
            if (!pl.isOnline() || text.isBlank() || until < now || !plugin.protection().pair(pl, at)) return;
            TextDisplay d = at.getWorld().spawn(at.clone().add(0, 0.4, 0), TextDisplay.class, x -> {
                x.text(Component.text(text, net.kyori.adventure.text.format.TextColor.color(0xFFE38A)));
                x.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                x.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                x.setShadowed(true);
                x.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                x.setPersistent(false);
            });
            shown.add(d);
            if (!spell.isBlank()) Links.vfx(pl, spell, "external:transfer", at, null);
            Bukkit.getScheduler().runTaskLater(plugin, d::remove, ticks);
        }

        // 마나 포션 마시기·우클릭은 Potions 로 옮김 (2026-10-26: 체력 포션 추가, 체력·마나 따로 쿨, 회복 100)

        @org.bukkit.event.EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            mining.remove(id); miningSpell.remove(id); texts.remove(id); cores.remove(id);
        }

        void closeAll() { shown.forEach(Entity::remove); shown.clear(); }
    }
}
