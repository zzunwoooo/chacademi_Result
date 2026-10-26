package school.chacademia.magicmythic;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

/**
 * chaworld{type=...} - small world and item utilities that MythicMobs core cannot do safely.
 * Every block change runs the same fake break/place event check as MagicCodexBridge SpellUtility,
 * so protected regions and other plugins can refuse it.
 *
 *  decoy r=10 ticks=100 | platform ticks=400 | flight ticks=200 | companion ticks=600 period=30 range=10 mult
 *  frostwalk ticks=300 r=2 | kiln r=6 ticks=400 | heat r=10 | ore r=10 max=3 ticks=100
 *  water | line max=6 | cauldron | wetfarm r=2 max=9 | grow r=5 max=8 | concrete max=16 | sign
 *  lure | boat | dew max=3 | dye | sapgeum
 */
final class WorldMech extends Mech {
    WorldMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        Player pl = c.caster() instanceof Player x ? x : null;
        return switch (type) {
            case "decoy" -> decoy(c);
            case "companion" -> companion(c);
            case "heat" -> heat(c);
            case "platform" -> pl != null && plugin.worldState().platform(pl, p);
            case "flight" -> pl != null && plugin.worldState().broomOrFlight(pl, Math.clamp(p.i("ticks", 200), 20, 20 * 120));
            case "frostwalk" -> pl != null && frostwalk(pl);
            case "kiln" -> pl != null && kiln(pl);
            case "ore" -> pl != null && ore(pl);
            case "water" -> pl != null && water(pl, c.at());
            case "line" -> pl != null && line(pl, c.at());
            case "cauldron" -> pl != null && cauldron(pl, c.at());
            case "wetfarm" -> pl != null && wetFarm(pl, c.at());
            case "grow" -> pl != null && grow(pl);
            case "concrete" -> pl != null && concrete(pl, c.at());
            case "sign" -> pl != null && sign(pl, c.at());
            case "lure" -> pl != null && lure(pl);
            case "boat" -> pl != null && boat(pl);
            case "dew" -> pl != null && dew(pl);
            case "dye" -> pl != null && dye(pl);
            case "sapgeum" -> pl != null && sapgeum(pl);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ creatures
    private boolean decoy(Ctx c) {
        Location center = c.at().clone();
        double r = p.d("r", 10);
        int ticks = Math.clamp(p.i("ticks", 100), 10, 20 * 60);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (age++ >= ticks || !plugin.protection().pair(c.caster(),center)) return false;
                if (age % 10 != 1) return true;
                for (LivingEntity e : plugin.targets().enemiesNear(c.caster(), center, r, 32)) {
                    if (!(e instanceof Mob mob) || plugin.targets().boss(e)) continue;
                    mob.setTarget(null);
                    mob.getPathfinder().moveTo(center);
                }
                return true;
            }
        });
    }

    private boolean companion(Ctx c) {
        int ticks = Math.clamp(p.i("ticks", 600), 20, 20 * 300), period = Math.max(5, p.i("period", 30));
        double range = p.d("range", 10), dmg = c.dmg(p);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (age++ >= ticks || !c.caster().isValid()) return false;
                if (age % period != 0) return true;
                var near = plugin.targets().enemiesNear(c.caster(), c.caster().getLocation(), range, 1);
                if (near.isEmpty()) return true;
                LivingEntity t = near.getFirst();
                if(!plugin.targets().hurt(c.caster(), t, dmg))return true;
                plugin.targets().knock(t, c.caster().getLocation(), c.caster().getLocation().getDirection(), p.d("knock", 0.3), 0.1);
                return true;
            }
        });
    }

    private boolean heat(Ctx c) {
        Location at = c.caster().getLocation();
        int r = Math.clamp(p.i("r", 10), 1, 12), count = 0;
        for (int x = -r; x <= r && count < 32; x++) for (int y = -r / 2; y <= r / 2 && count < 32; y++) for (int z = -r; z <= r && count < 32; z++) {
            Block b = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
            Material m = b.getType();
            boolean hot = m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LAVA || m == Material.MAGMA_BLOCK
                || (b.getBlockData() instanceof Lightable l && l.isLit() && (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                    || m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER));
            if (hot) count++;
        }
        if (count == 0) return false;
        double heal = Math.min(p.d("cap", 8), p.d("base", 1) + count * p.d("per", 0.5));
        c.caster().setHealth(Math.min(Targets.maxHealth(c.caster()), c.caster().getHealth() + heal));
        return true;
    }

    // ------------------------------------------------------------------ timed player helpers
    private boolean frostwalk(Player pl) {
        int ticks = Math.clamp(p.i("ticks", 300), 20, 20 * 120);
        int r = Math.clamp(p.i("r", 2), 1, 4);
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (age++ >= ticks || !pl.isOnline()||!plugin.protection().at(pl.getLocation())) return false;
                if (age % 2 != 0) return true;
                Location at = pl.getLocation();
                for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                    if (x * x + z * z > r * r) continue;
                    Block b = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() - 1, at.getBlockZ() + z);
                    if (b.getType() != Material.WATER || !(b.getBlockData() instanceof Levelled l) || l.getLevel() != 0) continue;
                    if (!b.getRelative(BlockFace.UP).getType().isAir()) continue;
                    if(!MagicMythicAccess.at(pl,b.getLocation()))continue;
                    if(!MagicMythicAccess.at(pl,b.getLocation()))continue;
            BlockState next = b.getState();
                    next.setType(Material.FROSTED_ICE);
                    var event = new EntityBlockFormEvent(pl, b, next);
                    Bukkit.getPluginManager().callEvent(event);
                    if (!event.isCancelled()) next.update(true, false);
                }
                return true;
            }
        });
    }

    private boolean kiln(Player pl) {
        int r = Math.clamp(p.i("r", 6), 1, 10), ticks = Math.clamp(p.i("ticks", 400), 20, 20 * 120);
        var furnaces = new ArrayList<Block>();
        Location at = pl.getLocation();
        for (int x = -r; x <= r; x++) for (int y = -2; y <= 2; y++) for (int z = -r; z <= r; z++) {
            Block b = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
            if (b.getState() instanceof Furnace && furnaces.size() < 16) furnaces.add(b);
        }
        if (furnaces.isEmpty()) return false;
        return plugin.ticker().add(new Ticker.Tick() {
            int age = 0;
            public boolean tick() {
                if (age++ >= ticks) return false;
                for (Block b : furnaces) {
                    if (!MagicMythicAccess.at(pl,b.getLocation())||!(b.getState() instanceof Furnace f)) continue;
                    if (f.getBurnTime() <= 0 || f.getCookTime() <= 0 || f.getCookTime() >= f.getCookTimeTotal() - 1) continue;
                    f.setCookTime((short) (f.getCookTime() + 1)); // vanilla adds one more: about double speed
                    f.update(false, false);
                }
                return true;
            }
        });
    }

    private boolean ore(Player pl) {
        int r = Math.clamp(p.i("r", 10), 1, 16), max = Math.clamp(p.i("max", 3), 1, 8), ticks = Math.clamp(p.i("ticks", 100), 10, 20 * 30);
        Location at = pl.getLocation();
        var found = new ArrayList<Block>();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            if (x * x + y * y + z * z > r * r) continue;
            Block b = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
            if(!MagicMythicAccess.at(pl,b.getLocation()))continue;
            String n = b.getType().name();
            if (n.endsWith("_ORE") || b.getType() == Material.ANCIENT_DEBRIS) found.add(b);
        }
        if (found.isEmpty()) return false;
        found.sort(java.util.Comparator.comparingDouble(b -> b.getLocation().distanceSquared(at)));
        var shown = new ArrayList<Entity>();
        for (Block b : found.subList(0, Math.min(max, found.size()))) {
            BlockData data = b.getBlockData();
            BlockDisplay d = b.getWorld().spawn(b.getLocation(), BlockDisplay.class, e -> {
                e.setBlock(data); e.setGlowing(true); e.setVisibleByDefault(false); e.setPersistent(false);
            });
            pl.showEntity(plugin, d);
            shown.add(d);
        }
        plugin.worldState().track(shown);
        Bukkit.getScheduler().runTaskLater(plugin, () -> shown.forEach(Entity::remove), ticks);
        return true;
    }

    // ------------------------------------------------------------------ blocks
    /** The free block in front of the aimed surface (one step back toward the caster). */
    private static Block aimedAir(Player pl, Location at) {
        Block b = at.getBlock();
        if (b.getType().isAir()) return b;
        Vector back = pl.getEyeLocation().toVector().subtract(at.toVector());
        if (back.lengthSquared() < 1e-4) return null;
        Block front = at.clone().add(back.normalize().multiply(0.35)).getBlock();
        return front.getType().isAir() ? front : null;
    }
    private static Block aimedSolid(Player pl, Location at) {
        Block b = at.getBlock();
        if (!b.getType().isAir()) return b;
        Vector dir = at.toVector().subtract(pl.getEyeLocation().toVector());
        if (dir.lengthSquared() < 1e-4) return b;
        return at.clone().add(dir.normalize().multiply(0.35)).getBlock();
    }

    private boolean water(Player pl, Location at) {
        if (at.getWorld().getEnvironment() == World.Environment.NETHER) return false;
        Block b = aimedAir(pl, at);
        if (b == null || !Targets.canPlace(pl, b, b.getRelative(BlockFace.DOWN), Material.WATER_BUCKET)) return false;
        b.setType(Material.WATER);
        return true;
    }

    private boolean line(Player pl, Location at) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        Material m = hand.getType();
        if (!m.isBlock() || !m.isSolid() || hand.getAmount() <= 0) return false;
        Block b = aimedAir(pl, at);
        if (b == null) return false;
        BlockFace dir = pl.getFacing();
        int placed = 0, max = Math.clamp(p.i("max", 6), 1, 16);
        while (placed < max && hand.getAmount() > 0 && b.getType().isAir()) {
            if (!Targets.canPlace(pl, b, b.getRelative(dir.getOppositeFace()), m)) break;
            b.setType(m);
            if (pl.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            placed++;
            b = b.getRelative(dir);
        }
        return placed > 0;
    }

    private boolean cauldron(Player pl, Location at) {
        Block b = aimedSolid(pl, at);
        if (b.getType() != Material.CAULDRON && b.getType() != Material.WATER_CAULDRON) return false;
        if (!Targets.canPlace(pl, b, b.getRelative(BlockFace.DOWN), Material.WATER_BUCKET)) return false;
        if (b.getType() == Material.CAULDRON) {
            BlockData d = Material.WATER_CAULDRON.createBlockData();
            ((Levelled) d).setLevel(1);
            b.setBlockData(d);
            return true;
        }
        if (!(b.getBlockData() instanceof Levelled l) || l.getLevel() >= l.getMaximumLevel()) return false;
        l.setLevel(l.getLevel() + 1);
        b.setBlockData(l);
        return true;
    }

    private boolean wetFarm(Player pl, Location at) {
        Block center = aimedSolid(pl, at);
        int r = Math.clamp(p.i("r", 2), 0, 4), max = Math.clamp(p.i("max", 9), 1, 25), done = 0;
        for (int x = -r; x <= r && done < max; x++) for (int y = -1; y <= 1 && done < max; y++) for (int z = -r; z <= r && done < max; z++) {
            Block b = center.getRelative(x, y, z);
            if (!(b.getBlockData() instanceof Farmland f) || f.getMoisture() >= f.getMaximumMoisture()) continue;
            if (!Targets.canPlace(pl, b, b.getRelative(BlockFace.DOWN), Material.WATER_BUCKET)) continue;
            f.setMoisture(f.getMaximumMoisture());
            b.setBlockData(f);
            done++;
        }
        return done > 0;
    }

    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES);
    private boolean grow(Player pl) {
        Location at = pl.getLocation();
        int r = Math.clamp(p.i("r", 5), 1, 8), max = Math.clamp(p.i("max", 8), 1, 32), done = 0;
        for (int x = -r; x <= r && done < max; x++) for (int y = -2; y <= 2 && done < max; y++) for (int z = -r; z <= r && done < max; z++) {
            Block b = at.getWorld().getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
            if (!CROPS.contains(b.getType()) || !(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) continue;
            if(!MagicMythicAccess.at(pl,b.getLocation()))continue;
            BlockState next = b.getState();
            Ageable grown = (Ageable) a.clone();
            grown.setAge(a.getAge() + 1);
            next.setBlockData(grown);
            var event = new BlockGrowEvent(b, next);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) continue;
            next.update(true);
            done++;
        }
        return done > 0;
    }

    private boolean concrete(Player pl, Location at) {
        Block start = aimedSolid(pl, at);
        Material powder = start.getType();
        if (!powder.name().endsWith("_CONCRETE_POWDER")) return false;
        Material solid = Material.matchMaterial(powder.name().replace("_POWDER", ""));
        if (solid == null) return false;
        int max = Math.clamp(p.i("max", 16), 1, 64), done = 0;
        var queue = new ArrayDeque<Block>(List.of(start));
        var seen = new HashSet<Block>();
        while (!queue.isEmpty() && done < max) {
            Block b = queue.poll();
            if (!seen.add(b) || b.getType() != powder) continue;
            if (!Targets.canBreak(pl, b)) continue;
            b.setType(solid);
            done++;
            for (BlockFace f : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
                queue.add(b.getRelative(f));
        }
        return done > 0;
    }

    private boolean sign(Player pl, Location at) {
        Block b = aimedSolid(pl, at);
        if (!(b.getState() instanceof Sign sign) || sign.isWaxed()) return false;
        if (!Targets.canBreak(pl, b)) return false;
        var side = sign.getTargetSide(pl);
        for (int i = 0; i < 4; i++) side.line(i, Component.empty());
        sign.update();
        return true;
    }

    // ------------------------------------------------------------------ items & misc
    private boolean lure(Player pl) {
        for (FishHook hook : pl.getWorld().getEntitiesByClass(FishHook.class)) {
            if (!(hook.getShooter() instanceof Player owner) || !owner.equals(pl)) continue;
            hook.setWaitTime(Math.max(1, p.i("min", 20)), Math.max(2, p.i("max", 60)));
            return true;
        }
        return false;
    }

    private boolean boat(Player pl) {
        Block feet = pl.getLocation().getBlock();
        if (feet.getType() != Material.WATER && feet.getRelative(BlockFace.DOWN).getType() != Material.WATER) return false;
        if (!pl.getNearbyEntities(3, 3, 3).stream().filter(e -> e instanceof Boat).toList().isEmpty()) return false;
        pl.getWorld().spawnEntity(pl.getLocation().add(0, 0.4, 0), EntityType.OAK_BOAT);
        return true;
    }

    private boolean dew(Player pl) {
        int left = Math.clamp(p.i("max", 3), 1, 16), filled = 0;
        var inv = pl.getInventory();
        for (int slot = 0; slot < inv.getSize() && left > 0; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() != Material.GLASS_BOTTLE) continue;
            int n = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - n);
            left -= n; filled += n;
        }
        if (filled == 0) return false;
        ItemStack water = new ItemStack(Material.POTION, filled);
        if (water.getItemMeta() instanceof PotionMeta meta) { meta.setBasePotionType(PotionType.WATER); water.setItemMeta(meta); }
        inv.addItem(water).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        return true;
    }

    private boolean dye(Player pl) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        if (!(hand.getItemMeta() instanceof LeatherArmorMeta meta)) return false;
        meta.setColor(null);
        hand.setItemMeta(meta);
        return true;
    }

    private boolean sapgeum(Player pl) {
        Block feet = pl.getLocation().getBlock();
        if (feet.getType() != Material.WATER && feet.getRelative(BlockFace.DOWN).getType() != Material.WATER) return false;
        int n = ThreadLocalRandom.current().nextInt(Math.max(1, p.i("min", 1)), Math.max(1, p.i("max", 3)) + 1);
        Material item = Material.matchMaterial(p.s("item", "GOLD_NUGGET"));
        if (item == null) item = Material.GOLD_NUGGET;
        pl.getInventory().addItem(new ItemStack(item, n)).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        return true;
    }

    // ------------------------------------------------------------------ shared state
    static final class State {
        private final MagicMythicPlugin plugin;
        private final Map<UUID, List<Block>> platforms = new HashMap<>();
        private final Map<UUID, Integer> flights = new HashMap<>();
        /** 비행 마법으로 빗자루에 태운 사람. 보호 구역에 들어가면 내리게 하려고 기억해 둠. */
        private final Set<UUID> brooms = new HashSet<>();
        private final List<Entity> displays = new ArrayList<>();
        private final Map<String, int[]> combos = new HashMap<>(); // key -> {count, expiresTick}
        private int tick = 0;

        State(MagicMythicPlugin plugin) {
            this.plugin = plugin;
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                tick++;
                for(UUID id:new ArrayList<>(platforms.keySet()))if(platforms.get(id).stream().anyMatch(b->!plugin.protection().at(b.getLocation())))clearPlatform(id);
                for(UUID id:new ArrayList<>(flights.keySet())){Player player=Bukkit.getPlayer(id);if(player!=null&&!plugin.protection().at(player.getLocation()))endFlight(id);}
                if((tick % 5)==0&&!brooms.isEmpty())for(UUID id:new ArrayList<>(brooms)){Player player=Bukkit.getPlayer(id);
                    if(player==null||!BroomLink.riding(player)){brooms.remove(id);continue;}
                    if(!plugin.protection().at(player.getLocation())){BroomLink.stop(player);brooms.remove(id);}}
                if ((tick & 63) == 0) { combos.values().removeIf(v -> v[1] < tick); displays.removeIf(e -> !e.isValid()); }
            }, 1, 1);
        }

        void track(List<Entity> shown) { displays.addAll(shown); }

        /** Counts hits on one target per caster+key; true (and reset) when `needed` hits land within 3 seconds. */
        boolean combo(LivingEntity caster, String key, LivingEntity target, int needed) {
            String k = caster.getUniqueId() + ":" + key + ":" + target.getUniqueId();
            int[] v = combos.computeIfAbsent(k, x -> new int[]{0, 0});
            if (v[1] < tick) v[0] = 0;
            v[0]++; v[1] = tick + 60;
            if (v[0] >= needed) { combos.remove(k); return true; }
            return false;
        }

        boolean platform(Player pl, Params p) {
            clearPlatform(pl.getUniqueId());
            Material m = Material.matchMaterial(plugin.getConfig().getString("platform-material", "BARRIER"));
            if (m == null || !m.isBlock()) m = Material.BARRIER;
            Block under = pl.getLocation().getBlock().getRelative(BlockFace.DOWN);
            var spots = new ArrayList<Block>();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                Block b = under.getRelative(x, 0, z);
                if (!b.getType().isAir() || !Targets.canPlace(pl, b, b.getRelative(BlockFace.DOWN), m)) continue;
                spots.add(b);
            }
            // one journal write for the whole platform (was one per block)
            var placed = new ArrayList<Block>(plugin.tempBlocks().placeAll(spots, m));
            if (placed.isEmpty()) return false;
            platforms.put(pl.getUniqueId(), placed);
            pl.setFallDistance(0);
            int ticks = Math.clamp(p.i("ticks", 400), 20, 20 * 120);
            UUID id = pl.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (platforms.get(id) == placed) clearPlatform(id); }, ticks);
            return true;
        }
        private void clearPlatform(UUID id) {
            var old = platforms.remove(id);
            if (old != null) plugin.tempBlocks().removeAll(old); // one journal write for the whole platform
        }

        /**
         * 비행 마법: ChacademyMotion 빗자루 비행으로 처리(빗자루가 있으면 무제한, 없으면 연습용 빗자루로 ticks 동안).
         * 그 플레이어에게 차카데미 모션 모드가 없거나 ChacademyMotion 이 꺼져 있으면 예전 바닐라 비행(ticks 동안)으로 대신한다.
         */
        boolean broomOrFlight(Player pl, int ticks) {
            if (flights.containsKey(pl.getUniqueId())) return flight(pl, ticks); // 예전 비행 중이면 그대로 연장
            Boolean handled = BroomLink.flight(pl, ticks);
            if (handled != null) {
                if (!handled) return false; // A denied broom request must not bypass policy via vanilla flight.
                if (BroomLink.riding(pl)) brooms.add(pl.getUniqueId()); else brooms.remove(pl.getUniqueId());
                return true;
            }
            return flight(pl, ticks);
        }

        boolean flight(Player pl, int ticks) {
            if (pl.getGameMode() != GameMode.SURVIVAL && pl.getGameMode() != GameMode.ADVENTURE) return false;
            if (pl.getAllowFlight() && !flights.containsKey(pl.getUniqueId())) return false; // flight granted by something else
            pl.setAllowFlight(true);
            pl.setFlying(true);
            UUID id = pl.getUniqueId();
            int token = flights.merge(id, 1, Integer::sum);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (Objects.equals(flights.get(id), token)) endFlight(id); }, ticks);
            return true;
        }
        private void endFlight(UUID id) {
            flights.remove(id);
            Player pl = Bukkit.getPlayer(id);
            if (pl == null || (pl.getGameMode() != GameMode.SURVIVAL && pl.getGameMode() != GameMode.ADVENTURE)) return;
            pl.setFlying(false);
            pl.setAllowFlight(false);
            pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0));
        }

        void closeAll() {
            for (UUID id : new ArrayList<>(brooms)) { Player p = Bukkit.getPlayer(id); if (p != null) BroomLink.stop(p); }
            brooms.clear();
            for (UUID id : new ArrayList<>(platforms.keySet())) clearPlatform(id);
            for (UUID id : new ArrayList<>(flights.keySet())) endFlight(id);
            displays.forEach(Entity::remove);
            displays.clear();
        }
    }
}
