package school.magiccodex.discovery;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/**
 * Natural acquisition counters for spells 101-167 (2026-10-14). Only vanilla, server-verified actions; each key is
 * defined in discoveries.yml. Keys counted by other plugins (magic kills / heals / cleanse / six elements / mana 0 from
 * ChAcademiaMagicMythic, friend.made from the Bridge) are listed in the handoff, not here.
 * Movement and polling checks run only for players who still need the spell (plugin.needed).
 */
final class ExpansionConditions implements Listener, AutoCloseable {
    private final MagicDiscovery plugin;
    private int tick;
    private static final class Trace {
        double pet, sneak; int dark; boolean petNear; int petChecked; // 2026-10-26 v2: 나침반 걷기(길잡이 별 → 스토리)·슬라임 연속 반동(용수철 지반 → 슬라임 위 점프) 삭제
        UUID blockedAttacker; int blockedUntil;
    }
    private final Map<UUID, Trace> traces = new HashMap<>();
    private final Map<Location, UUID> pumpkins = new HashMap<>();
    private final Map<Location, Integer> pumpkinTick = new HashMap<>();

    ExpansionConditions(MagicDiscovery plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    private void add(Player p, String key, double amount) { if (amount > 0) plugin.signal(p.getUniqueId(), key, amount); }
    private Trace trace(Player p) { return traces.computeIfAbsent(p.getUniqueId(), k -> new Trace()); }
    void quit(Player p) { traces.remove(p.getUniqueId()); }
    private static Player attacker(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }
    private static boolean monster(Entity e) {
        if (e instanceof Monster) return true;
        return e instanceof Projectile pr && pr.getShooter() instanceof Monster;
    }
    private static boolean night(World w) { long t = w.getTime(); return w.getEnvironment() == World.Environment.NORMAL && t >= 13000 && t <= 23000; }

    // ------------------------------------------------------------------ block placing (101 112 107 115 148 126 165; 121 자동 보급 2026-10-26 삭제)
    private static final Set<Material> CROP_BLOCKS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
        Material.MELON_STEM, Material.PUMPKIN_STEM, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP, Material.NETHER_WART);
    private static final Set<Material> TALL_FLOWERS = EnumSet.of(Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.PITCHER_PLANT);
    private static final Set<Material> TORCHES = EnumSet.of(Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();
        if (Tag.DIRT.isTagged(m)) add(p, "place.dirt_family", 1);
        if (m == Material.STONE_BRICKS) add(p, "place.stone_bricks", 1);
        if (CROP_BLOCKS.contains(m)) add(p, "plant.seeds", 1);
        if (Tag.SMALL_FLOWERS.isTagged(m) || TALL_FLOWERS.contains(m)) add(p, "plant.flower", 1);
        if (TORCHES.contains(m)) {
            if (night(p.getWorld())) add(p, "place.torch_night", 1);
            trace(p).dark = 0; // 165: placing a torch breaks the dark streak
        }
        if (m == Material.CARVED_PUMPKIN || m == Material.JACK_O_LANTERN) {
            Location at = e.getBlockPlaced().getLocation();
            pumpkins.put(at, p.getUniqueId()); pumpkinTick.put(at, tick);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void golem(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM) return;
        Location at = e.getLocation();
        UUID best = null; double bd = 9;
        for (var entry : pumpkins.entrySet()) {
            if (!Objects.equals(entry.getKey().getWorld(), at.getWorld()) || tick - pumpkinTick.getOrDefault(entry.getKey(), -100) > 2) continue;
            double d = entry.getKey().clone().add(0.5, 0, 0.5).distanceSquared(at);
            if (d < bd) { bd = d; best = entry.getValue(); }
        }
        Player p = best == null ? null : Bukkit.getPlayer(best);
        if (p != null) add(p, "build.iron_golem", 1);
    }

    // ------------------------------------------------------------------ breaking (110 119 125/129)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broke(BlockBreakEvent e) {
        if (!e.isDropItems()) return; // protection probes from spells (drops off) are not real breaks
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Material m = b.getType();
        if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE) add(p, "mine.diamond_ore", 1);
        if (m == Material.CACTUS) {
            int n = 0;
            for (Block x = b; x.getType() == Material.CACTUS && n < 16; x = x.getRelative(BlockFace.UP)) n++;
            add(p, "harvest.cactus", n);
        }
        if ((m == Material.WHEAT || m == Material.CARROTS || m == Material.POTATOES || m == Material.BEETROOTS || m == Material.NETHER_WART)
            && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
            add(p, "harvest.crops", 1);
            // 2026-10-26 v2 풍요의 손길: 다 자란 밀·당근·감자 각각
            if (m == Material.WHEAT) add(p, "harvest.wheat", 1); else if (m == Material.CARROTS) add(p, "harvest.carrots", 1); else if (m == Material.POTATOES) add(p, "harvest.potatoes", 1);
        }
    }

    // ------------------------------------------------------------------ 117 bark stripping with an axe
    @EventHandler(priority = EventPriority.MONITOR)
    public void strip(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.useInteractedBlock() == Event.Result.DENY || e.getClickedBlock() == null || e.getHand() == null) return;
        Player p = e.getPlayer();
        if (!Tag.ITEMS_AXES.isTagged(p.getInventory().getItem(e.getHand()).getType())) return;
        Block b = e.getClickedBlock();
        Material before = b.getType();
        String n = before.name();
        if (n.startsWith("STRIPPED_") || !(Tag.LOGS.isTagged(before) || before == Material.BAMBOO_BLOCK)) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && b.getType().name().equals("STRIPPED_" + n)) add(p, "strip.log", 1); });
    }

    // ------------------------------------------------------------------ breeding (108 120)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p)) return;
        switch (e.getEntity().getType()) {
            case BEE -> add(p, "breed.bee", 1);
            case COW -> add(p, "breed.cow", 1);
            case SHEEP -> add(p, "breed.sheep", 1);
            case PIG -> add(p, "breed.pig", 1);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ damage taken / dealt (109 111 113 123 134 146 152)
    @SuppressWarnings("deprecation")
    private static boolean shieldBlocked(EntityDamageEvent e) {
        try { return e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) && e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0; }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }
    private static boolean fullIron(Player p) {
        var a = p.getInventory();
        return a.getHelmet() != null && a.getHelmet().getType() == Material.IRON_HELMET && a.getChestplate() != null && a.getChestplate().getType() == Material.IRON_CHESTPLATE
            && a.getLeggings() != null && a.getLeggings().getType() == Material.IRON_LEGGINGS && a.getBoots() != null && a.getBoots().getType() == Material.IRON_BOOTS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void berry(EntityDamageByBlockEvent e) {
        if (e.getEntity() instanceof Player p && e.getDamager() != null && e.getDamager().getType() == Material.SWEET_BERRY_BUSH && e.getFinalDamage() > 0)
            add(p, "hurt.sweet_berry_bush", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim) {
            Entity source = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : e.getDamager();
            if (victim.isBlocking() && shieldBlocked(e)) {
                add(victim, "shield.block", 1);
                if (monster(e.getDamager())) add(victim, "shield.block_monster", 1);
                var t = trace(victim); t.blockedAttacker = source.getUniqueId(); t.blockedUntil = tick + 60;
            }
            if (monster(e.getDamager()) && e.getFinalDamage() > 0 && fullIron(victim)) add(victim, "damage.monster_iron_armor", e.getFinalDamage());
        }
        Player p = attacker(e.getDamager());
        if (p == null || p.equals(e.getEntity())) return;
        var t = traces.get(p.getUniqueId());
        if (t != null && t.blockedAttacker != null && t.blockedAttacker.equals(e.getEntity().getUniqueId()) && t.blockedUntil >= tick) {
            add(p, "shield.counter", 1); t.blockedAttacker = null;
        }
        if (e.getEntity() instanceof Monster mob) {
            if (e.getDamager() instanceof SpectralArrow) add(p, "hit.spectral_monster", 1);
            if (e.getDamager() instanceof Arrow) {
                var v = mob.getVelocity();
                if (Math.hypot(v.getX(), v.getZ()) >= 0.1) add(p, "hit.running_monster", 1);
            }
        }
    }

    // ------------------------------------------------------------------ kills (137 143 156)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player p = dead.getKiller();
        if (p == null) return;
        if (dead instanceof Monster && (dead.isInvisible() || dead.hasPotionEffect(PotionEffectType.INVISIBILITY))) add(p, "kill.invisible_monster", 1);
        if (dead instanceof Zombie || dead instanceof AbstractSkeleton) add(p, "kill.zombie_skeleton", 1);
        if (dead instanceof Mob mob && mob.getTarget() instanceof Player other && !other.equals(p)) add(p, "kill.mob_attacking_player", 1);
    }

    // ------------------------------------------------------------------ items (140 144 142 150 151 153)
    private static boolean tool(Material m) {
        return Tag.ITEMS_PICKAXES.isTagged(m) || Tag.ITEMS_AXES.isTagged(m) || Tag.ITEMS_SHOVELS.isTagged(m) || Tag.ITEMS_HOES.isTagged(m)
            || m == Material.SHEARS || m == Material.FISHING_ROD || m == Material.FLINT_AND_STEEL || m == Material.BRUSH;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void anvil(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory inv) || e.getRawSlot() != 2 || !(e.getWhoClicked() instanceof Player p)) return;
        ItemStack in = inv.getItem(0), out = e.getCurrentItem();
        if (in == null || out == null || out.getType().isAir() || !tool(out.getType()) || in.getType() != out.getType()) return;
        if (p.getGameMode() != GameMode.CREATIVE && (p.getLevel() < inv.getRepairCost() || inv.getRepairCost() >= 40)) return;
        String before = in.hasItemMeta() && in.getItemMeta().hasDisplayName() ? in.getItemMeta().getDisplayName() : "";
        String after = out.hasItemMeta() && out.getItemMeta().hasDisplayName() ? out.getItemMeta().getDisplayName() : "";
        boolean renamed = !after.isEmpty() && !after.equals(before);
        int dIn = in.getItemMeta() instanceof Damageable d ? d.getDamage() : 0, dOut = out.getItemMeta() instanceof Damageable d2 ? d2.getDamage() : 0;
        boolean repaired = dOut < dIn;
        if (!renamed && !repaired) return;
        // count only when the result was really taken (input slot consumed on the next tick)
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack left = inv.getItem(0);
            if (!p.isOnline() || left != null && !left.getType().isAir()) return;
            if (renamed) add(p, "anvil.rename_tool", 1);
            if (repaired) add(p, "anvil.repair_tool", 1);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void splash(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player thrower)) return;
        boolean healing = e.getPotion().getEffects().stream().anyMatch(fx -> fx.getType().equals(PotionEffectType.INSTANT_HEALTH));
        if (!healing) return;
        for (LivingEntity hit : e.getAffectedEntities())
            if (hit instanceof Player other && !other.equals(thrower) && e.getIntensity(hit) > 0) { add(thrower, "splash.heal_player", 1); return; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void eat(PlayerItemConsumeEvent e) { if (e.getItem().getType() == Material.ROTTEN_FLESH) add(e.getPlayer(), "eat.rotten_flesh", 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void book(PlayerEditBookEvent e) { if (e.isSigning()) add(e.getPlayer(), "book.signed", 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shared(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player picker)) return;
        var item = e.getItem();
        if (!item.getItemStack().getType().isEdible() || item.getThrower() == null || item.getThrower().equals(picker.getUniqueId())) return;
        Player thrower = Bukkit.getPlayer(item.getThrower());
        int amount = item.getItemStack().getAmount() - e.getRemaining();
        if (thrower != null && amount > 0) add(thrower, "food.shared", amount);
    }

    // ------------------------------------------------------------------ movement (127 132 154 167; 103 덩굴 사다리 2026-10-26 삭제)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Player p = e.getPlayer();
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ(), dy = to.getY() - from.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1e-4 && Math.abs(dy) < 1e-4) return;
        if (!plugin.eligible(p) || flat > 3) return; // teleports / vehicles
        Trace t = null;
        if (flat > 0 && p.isSneaking() && night(p.getWorld()) && plugin.needed(p, "faint_veil")) {
            t = t == null ? trace(p) : t; t.sneak += flat; int whole = (int) t.sneak; if (whole > 0) { t.sneak -= whole; add(p, "sneak.night", whole); }
        }
        if (flat > 0 && plugin.needed(p, "summons_letter")) {
            t = t == null ? trace(p) : t;
            if (tick - t.petChecked >= 40) { t.petChecked = tick; t.petNear = petNear(p); }
            if (t.petNear) { t.pet += flat; int whole = (int) t.pet; if (whole > 0) { t.pet -= whole; add(p, "travel.with_pet", whole); } }
        }
    }
    private static boolean petNear(Player p) {
        for (Entity e : p.getNearbyEntities(12, 6, 12))
            if ((e instanceof Wolf || e instanceof Cat) && e instanceof Tameable tame && tame.isTamed() && p.getUniqueId().equals(tame.getOwnerUniqueId())) return true;
        return false;
    }
    // ------------------------------------------------------------------ polling (106 seasons, 165 dark caves)
    private void tick() {
        tick++;
        if (tick % 200 == 0) { pumpkins.keySet().removeIf(l -> tick - pumpkinTick.getOrDefault(l, 0) > 4); pumpkinTick.keySet().retainAll(pumpkins.keySet()); }
        if (tick % 20 == 0) for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.eligible(p) || !plugin.needed(p, "night_sight")) continue;
            Trace t = trace(p);
            Block b = p.getEyeLocation().getBlock();
            boolean dark = p.getWorld().getEnvironment() == World.Environment.NORMAL && b.getLightLevel() == 0 && b.getLightFromSky() == 0;
            t.dark = dark ? t.dark + 1 : 0;
            if (t.dark >= 120) { add(p, "dark.cave_120", 1); t.dark = 0; }
        }
        if (tick % 1200 == 0) {
            String season = season();
            if (season != null) for (Player p : Bukkit.getOnlinePlayers()) if (plugin.eligible(p) && plugin.needed(p, "climate_control")) add(p, "season." + season, 1);
        }
    }
    /** ClimateService (MagicCodexBridge) current season as spring/summer/autumn/winter, or null. */
    private static String season() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals("school.magiccodex.paper.ClimateService")) continue;
                Object service = Bukkit.getServicesManager().load(c);
                if (service == null) return null;
                Object s = c.getMethod("season").invoke(service);
                return s == null ? null : ((Enum<?>) s).name().toLowerCase(Locale.ROOT);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        return null;
    }

    @Override public void close() { traces.clear(); pumpkins.clear(); pumpkinTick.clear(); }
}
