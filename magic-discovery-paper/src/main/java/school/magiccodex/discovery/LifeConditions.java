package school.magiccodex.discovery;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/**
 * 마법 발견 조건 v2 (2026-10-26 오너 결정, docs/plans/discovery-conditions-v2.md) 의 바닐라 행동 카운터.
 *
 * <p>렉 방지: 모든 카운터는 이벤트로만 센다. 무거운 확인(바이옴·주변·다음 틱 확인)은 그 키를 쓰는 마법이 있고
 * ({@link MagicDiscovery#counting}) 그 플레이어가 아직 그 마법을 배우지 않았을 때({@link MagicDiscovery#needed})만 한다.
 * 주기 확인은 1초(바이옴 두 개)와 1분(잠 안 잔 시간)뿐이며, 그 마법이 필요한 접속자만 본다.</p>
 */
final class LifeConditions implements Listener, AutoCloseable {
    private final MagicDiscovery plugin;
    private int tick;
    private final Map<UUID, double[]> ice = new HashMap<>(), leash = new HashMap<>(), sneak = new HashMap<>();
    private final Map<UUID, Set<UUID>> leashed = new HashMap<>();
    private final Map<UUID, Integer> quenched = new HashMap<>(), spyglass = new HashMap<>(), leashChecked = new HashMap<>(), clicked = new HashMap<>();
    private final Set<UUID> leashing = new HashSet<>();
    private final ConditionRules.Replant replant = new ConditionRules.Replant();

    private static final Set<Material> ICE = EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);
    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART);

    LifeConditions(MagicDiscovery plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 1);
    }

    private void add(Player p, String key, double amount) { if (p != null && amount > 0) plugin.signal(p.getUniqueId(), key, amount); }
    private boolean counting(String key) { return plugin.counting(key); }
    private static String biome(Location l) {
        try { return l.getBlock().getBiome().getKey().getKey(); } catch (RuntimeException | LinkageError e) { return ""; }
    }
    private static boolean night(World w) { return w.getEnvironment() == World.Environment.NORMAL && ConditionRules.night(w.getTime()); }
    private static boolean underSky(Player p) {
        Location l = p.getLocation();
        return p.getWorld().getEnvironment() == World.Environment.NORMAL && ConditionRules.underSky(l.getBlockY(), p.getWorld().getHighestBlockYAt(l));
    }

    // ------------------------------------------------------------------ 블록 놓기·캐기
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Player p = e.getPlayer(); Block b = e.getBlockPlaced(); Material m = b.getType();
        if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) add(p, "place.campfire", 1);
        if (b.getY() >= 150 && b.getWorld().getEnvironment() == World.Environment.NORMAL) add(p, "place.sky", 1);
        if (CROPS.contains(m)) {
            if (b.getRelative(BlockFace.DOWN).getBlockData() instanceof Farmland f && f.getMoisture() == 0) add(p, "plant.dry_farmland", 1);
            if (replant.replanted(p.getUniqueId(), ConditionRules.Replant.key(b.getX(), b.getY(), b.getZ()), tick)) add(p, "plant.replant", 1);
        }
        if (Tag.CONCRETE_POWDER.isTagged(m) && counting("place.concrete_cured") && plugin.needed(p, "concrete_curing"))
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && b.getType().name().endsWith("_CONCRETE")) add(p, "place.concrete_cured", 1); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broke(BlockBreakEvent e) {
        if (!e.isDropItems()) return; // 마법의 보호 확인(드롭 없음)은 진짜 캐기가 아님
        Player p = e.getPlayer(); Block b = e.getBlock(); Material m = b.getType();
        Material hand = p.getInventory().getItemInMainHand().getType();
        if ((m == Material.STONE || m == Material.DEEPSLATE) && Tag.ITEMS_PICKAXES.isTagged(hand)) add(p, "mine.stone", 1);
        if (m == Material.COAL_ORE || m == Material.DEEPSLATE_COAL_ORE) add(p, "mine.coal_ore", 1);
        if (m == Material.IRON_ORE || m == Material.DEEPSLATE_IRON_ORE) add(p, "mine.iron_ore", 1);
        if ((m == Material.PUMPKIN || m == Material.MELON) && fromStem(b)) add(p, "harvest.melon_pumpkin", 1);
        if (CROPS.contains(m) && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() && counting("plant.replant"))
            replant.harvested(p.getUniqueId(), ConditionRules.Replant.key(b.getX(), b.getY(), b.getZ()), tick);
    }
    /** 줄기에 열린 호박·수박만 (직접 놓은 호박을 다시 캐는 것은 안 셈). */
    private static boolean fromStem(Block b) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block n = b.getRelative(face);
            if ((n.getType() == Material.ATTACHED_PUMPKIN_STEM || n.getType() == Material.ATTACHED_MELON_STEM)
                && n.getBlockData() instanceof Directional d && d.getFacing() == face.getOppositeFace()) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void berries(PlayerHarvestBlockEvent e) {
        if (e.getHarvestedBlock().getType() == Material.SWEET_BERRY_BUSH) add(e.getPlayer(), "harvest.sweet_berries", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void grow(StructureGrowEvent e) {
        if (!e.isFromBonemeal() || e.getPlayer() == null) return;
        String species = e.getSpecies().name();
        if (!species.contains("MUSHROOM") && !species.contains("CHORUS")) add(e.getPlayer(), "tree.bonemeal", 1);
    }

    // ------------------------------------------------------------------ 블록 우클릭 (농지 밟기·퇴비통·모닥불·망원경)
    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent e) {
        Player p = e.getPlayer(); Block b = e.getClickedBlock();
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            if (b != null && b.getType() == Material.FARMLAND && e.useInteractedBlock() != Event.Result.DENY && counting("trample.farmland")
                && plugin.needed(p, "light_step"))
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && b.getType() == Material.DIRT) add(p, "trample.farmland", 1); });
            return;
        }
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND && e.getHand() != org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        ItemStack held = p.getInventory().getItem(e.getHand());
        if ((e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
            && held.getType() == Material.SPYGLASS && night(p.getWorld()) && counting("spyglass.night") && underSky(p)
            && ConditionRules.cooled(spyglass, p.getUniqueId(), tick, 100)) add(p, "spyglass.night", 1);
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || b == null || e.useInteractedBlock() == Event.Result.DENY) return;
        if (b.getType() != Material.COMPOSTER && b.getType() != Material.CAMPFIRE && b.getType() != Material.SOUL_CAMPFIRE) return;
        if (!ConditionRules.cooled(clicked, p.getUniqueId(), tick, 2)) return; // 양손 이벤트가 둘 다 와도 한 번만
        if (b.getType() == Material.COMPOSTER && b.getBlockData() instanceof Levelled lv && lv.getLevel() >= lv.getMaximumLevel()) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && b.getBlockData() instanceof Levelled after && after.getLevel() == 0) add(p, "composter.bonemeal", 1); });
            return;
        }
        if ((b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) && held.getType().isEdible() && counting("campfire.cook")) {
            Material food = held.getType(); int before = cooking(b, food);
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && cooking(b, food) > before) add(p, "campfire.cook", 1); });
        }
    }
    private static int cooking(Block b, Material food) {
        if (!(b.getState() instanceof org.bukkit.block.Campfire c)) return 0;
        int n = 0; for (int i = 0; i < c.getSize(); i++) { ItemStack it = c.getItem(i); if (it != null && it.getType() == food) n++; }
        return n;
    }

    // ------------------------------------------------------------------ 가마솥·양동이·화로
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void cauldron(CauldronLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        switch (e.getReason()) {
            case ARMOR_WASH -> add(p, "cauldron.armor_wash", 1);
            case BOTTLE_FILL -> add(p, "cauldron.bottle_fill", 1);
            case BUCKET_EMPTY, BOTTLE_EMPTY -> { if (e.getNewState().getType() == Material.WATER_CAULDRON) add(p, "cauldron.filled", 1); }
            default -> {}
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fill(PlayerBucketFillEvent e) { if (e.getItemStack() != null && e.getItemStack().getType() == Material.WATER_BUCKET) add(e.getPlayer(), "bucket.fill_water", 1); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void empty(PlayerBucketEmptyEvent e) { if (e.getBucket() == Material.WATER_BUCKET) add(e.getPlayer(), "bucket.empty_water", 1); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void furnace(FurnaceExtractEvent e) {
        Material out = e.getItemType(); int n = e.getItemAmount();
        if (e.getBlock().getType() == Material.BLAST_FURNACE && out.name().endsWith("_INGOT")) add(e.getPlayer(), "blast.ingots", n);
        if (e.getBlock().getType() == Material.FURNACE && out.isBlock() && !out.isEdible()) add(e.getPlayer(), "furnace.block_taken", n);
    }

    // ------------------------------------------------------------------ 마시기·던지기·효과
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drink(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer(); ItemStack it = e.getItem();
        if (it.getType() == Material.MILK_BUCKET && p.hasPotionEffect(PotionEffectType.POISON)) add(p, "drink.milk_poisoned", 1);
        if (it.getType() == Material.POTION && it.getItemMeta() instanceof PotionMeta meta) {
            PotionType base = meta.getBasePotionType();
            if (base == PotionType.HEALING || base == PotionType.STRONG_HEALING || meta.hasCustomEffect(PotionEffectType.INSTANT_HEALTH)) add(p, "drink.healing_potion", 1);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void thrown(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof ThrownPotion && e.getEntity().getShooter() instanceof Player p) add(p, "throw.splash_potion", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getAction() != EntityPotionEffectEvent.Action.ADDED || e.getNewEffect() == null) return;
        PotionEffectType t = e.getNewEffect().getType();
        if (t.equals(PotionEffectType.POISON)) add(p, "effect.poison", 1);
        else if (t.equals(PotionEffectType.WEAKNESS)) add(p, "effect.weakness", 1);
        else if (t.equals(PotionEffectType.SLOWNESS)) add(p, "effect.slowness", 1);
    }

    // ------------------------------------------------------------------ 싸움
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player p && e.getEntity() instanceof Enemy && e.getFinalDamage() > 0)
            add(p, "hit.arrow_monster", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getFinalDamage() > 0) add(p, "damage.taken", e.getFinalDamage());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity(); Player p = dead.getKiller();
        if (p == null || !(dead instanceof Enemy)) return;
        if (dead instanceof Enderman) add(p, "kill.enderman", 1);
        if (dead instanceof Phantom) add(p, "kill.phantom", 1);
        var cause = dead.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent d && d.getDamager().equals(p) && d.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) add(p, "kill.melee", 1);
        World w = p.getWorld();
        if (night(w)) add(p, "kill.night", 1);
        if (w.getEnvironment() == World.Environment.NORMAL && w.hasStorm()) add(p, "kill.rain", 1);
        if (p.isInWater()) add(p, "kill.in_water", 1);
        if (p.getFoodLevel() <= 4) add(p, "kill.hungry", 1);
        if (p.getHealth() <= 2.0) add(p, "kill.low_health", 1);
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) add(p, "kill.while_invisible", 1);
        if (counting("kill.desert") || counting("kill.swamp")) {
            String b = biome(p.getLocation());
            if (ConditionRules.desert(b)) add(p, "kill.desert", 1);
            if (ConditionRules.swamp(b)) add(p, "kill.swamp", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void lightning(LightningStrikeEvent e) {
        if (e.getCause() != LightningStrikeEvent.Cause.WEATHER || !counting("lightning.witnessed")) return;
        Location at = e.getLightning().getLocation();
        for (Player p : e.getWorld().getPlayers())
            if (ConditionRules.nearLightning(p.getLocation().distanceSquared(at)) && plugin.needed(p, "lightning_needle") && underSky(p)) add(p, "lightning.witnessed", 1);
    }

    // ------------------------------------------------------------------ 그 밖의 생활
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST && e.getPlayer() instanceof Player p) add(p, "open.ender_chest", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void craft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getRecipe().getResult().getType().name().endsWith("_DYE")) add(p, "craft.dye", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void level(PlayerLevelChangeEvent e) { if (e.getNewLevel() >= 30) add(e.getPlayer(), "level.thirty", 1); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pearl(PlayerTeleportEvent e) { if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) add(e.getPlayer(), "teleport.ender_pearl", 1); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void woke(PlayerBedLeaveEvent e) {
        long t = Math.floorMod(e.getPlayer().getWorld().getTime(), 24000L);
        if (t < 2000) add(e.getPlayer(), "sleep.woke", 1); // 밤을 넘겨 아침에 일어남 (밤중에 그냥 내린 것은 안 셈)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tool(PlayerItemBreakEvent e) {
        Material m = e.getBrokenItem().getType();
        if (Tag.ITEMS_PICKAXES.isTagged(m)) add(e.getPlayer(), "break.pickaxe", 1);
        else if (Tag.ITEMS_AXES.isTagged(m)) add(e.getPlayer(), "break.axe", 1);
        else if (Tag.ITEMS_SHOVELS.isTagged(m)) add(e.getPlayer(), "break.shovel", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bell(org.bukkit.event.block.BellRingEvent e) {
        if (e.getEntity() instanceof Player p && night(p.getWorld())) add(p, "bell.night", 1);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void leash(PlayerLeashEntityEvent e) {
        if (!counting("travel.leash") || !plugin.needed(e.getPlayer(), "herding_breeze")) return;
        var set = leashed.computeIfAbsent(e.getPlayer().getUniqueId(), k -> new HashSet<>());
        if (set.size() < 16) set.add(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ 이동 (얼음 위·끈·웅크리기·불 끄기)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Player p = e.getPlayer(); UUID id = p.getUniqueId();
        if (p.getFireTicks() > 0 && p.isInWater() && plugin.eligible(p) && plugin.needed(p, "quench") && ConditionRules.cooled(quenched, id, tick, 60)) add(p, "fire.quenched", 1);
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1e-4 || !plugin.eligible(p)) return;
        if (p.isOnGround() && ICE.contains(to.clone().subtract(0, 0.2, 0).getBlock().getType()) && plugin.needed(p, "frost_path"))
            add(p, "walk.ice", ConditionRules.whole(ice.computeIfAbsent(id, k -> new double[1]), flat));
        if (p.isSneaking() && p.isOnGround() && plugin.needed(p, "hushed_soles"))
            add(p, "sneak.any", ConditionRules.whole(sneak.computeIfAbsent(id, k -> new double[1]), flat));
        var set = leashed.get(id);
        if (set != null) {
            if (tick - leashChecked.getOrDefault(id, -100) >= 20) { leashChecked.put(id, tick); if (stillLeashed(p, set)) leashing.add(id); else leashing.remove(id); }
            if (leashing.contains(id)) add(p, "travel.leash", ConditionRules.whole(leash.computeIfAbsent(id, k -> new double[1]), flat));
        }
    }
    private boolean stillLeashed(Player p, Set<UUID> set) {
        boolean any = false;
        for (var it = set.iterator(); it.hasNext(); ) {
            Entity en = Bukkit.getEntity(it.next());
            if (en instanceof LivingEntity le && le.isValid() && le.isLeashed() && p.equals(le.getLeashHolder())) any = true; else it.remove();
        }
        if (set.isEmpty()) { leashed.remove(p.getUniqueId()); leashing.remove(p.getUniqueId()); }
        return any;
    }

    // ------------------------------------------------------------------ 1초·1분 확인 (그 마법이 필요한 접속자만)
    private void tick() {
        tick++;
        if (tick % 20 == 0 && (counting("visit.desert_seconds") || counting("deep.ocean_seconds"))) for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.eligible(p)) continue;
            boolean desert = plugin.needed(p, "heat_haze"), deep = plugin.needed(p, "abyssal_pressure") && p.getEyeLocation().getBlock().getType() == Material.WATER;
            if (!desert && !deep) continue;
            String b = biome(p.getLocation());
            if (desert && ConditionRules.desert(b)) add(p, "visit.desert_seconds", 1);
            if (deep && ConditionRules.deepOcean(b)) add(p, "deep.ocean_seconds", 1);
        }
        if (tick % 1200 == 0 && counting("rest.three_days")) for (Player p : Bukkit.getOnlinePlayers())
            if (plugin.eligible(p) && plugin.needed(p, "drowsiness") && p.getStatistic(Statistic.TIME_SINCE_REST) >= 72000) add(p, "rest.three_days", 1);
        if (tick % 6000 == 0) { quenched.values().removeIf(t -> tick - t > 600); spyglass.values().removeIf(t -> tick - t > 600); clicked.values().removeIf(t -> tick - t > 600); }
    }

    void quit(Player p) {
        UUID id = p.getUniqueId();
        ice.remove(id); leash.remove(id); sneak.remove(id); leashed.remove(id); leashing.remove(id); leashChecked.remove(id);
        quenched.remove(id); spyglass.remove(id); clicked.remove(id); replant.quit(id);
    }
    @Override public void close() { ice.clear(); leash.clear(); sneak.clear(); leashed.clear(); leashing.clear(); quenched.clear(); spyglass.clear(); leashChecked.clear(); clicked.clear(); }
}
