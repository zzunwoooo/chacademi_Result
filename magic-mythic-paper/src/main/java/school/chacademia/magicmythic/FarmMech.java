package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * chafarm{type=...} - farming / nature utilities. Every block change goes through the same fake break/place/grow
 * events as the other spell utilities, so protected land refuses it.
 *
 *  seedrow max=6   - plant seeds from the inventory on farmland in a straight line ahead (107 씨앗 줄심기)
 *  harvest len=5   - harvest only fully grown crops in the line ahead, no replanting (122 동면의 씨앗)
 *  ripen len=5     - crops in the line ahead grow to full (125 기억하는 밭)
 *  revive len=5    - crops in the line ahead standing on fully dried farmland: farmland is wetted and the crop grows
 *                    one stage (129 대지의 품 - the heat/drought system itself does not exist yet, see handoff)
 *  grass size=2    - only dirt in the aimed 2x2 becomes grass (115 잔디 돋우기)
 *  strip max=6     - connected logs of the aimed kind lose their bark, same wood, same axis (117 껍질 벗기기)
 *  compost         - the plant stack in hand becomes bone meal at the vanilla composter's expected rate
 *                    (7 successful layers = 1 bone meal; 118 퇴비 환원 - real table/ratio still undecided)
 *  breed range=10 r=8 - the aimed (or nearest) breedable animal and one partner of the same kind fall in love,
 *                    no food; age and breeding cooldown are respected (120 꽃가루 중매)
 *  bees r=8        - angry bees nearby calm down (108 벌떼 달래기); attacking them again angers them as usual
 *  craft in=ROTTEN_FLESH:4 out=LEATHER:1 - convert items from the inventory (150 햇살 말림)
 *  weather         - clear <-> rain in the caster's world; natural weather continues afterwards (106 기후 통제)
 */
final class FarmMech extends Mech {
    FarmMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false;
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        boolean done = switch (type) {
            case "seedrow" -> seedRow(pl);
            case "harvest" -> line(pl, this::harvest, "external:harvest");
            case "ripen" -> line(pl, this::ripen, "external:grow");
            case "revive" -> line(pl, this::revive, "external:restore");
            case "grass" -> grass(pl, c.at());
            case "strip" -> strip(pl, c.at());
            case "compost" -> compost(pl);
            case "breed" -> breed(pl, c);
            case "bees" -> bees(pl);
            case "craft" -> craft(pl);
            case "weather" -> weather(pl);
            default -> false;
        };
        return done;
    }

    private void vfx(Player pl, String event, Location at) {
        String spell = p.s("spell", "");
        if (!spell.isBlank()) Links.vfx(pl, spell, event, at, null);
    }

    // ------------------------------------------------------------------ the line ahead (front 5 tiles)
    static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
        Material.NETHER_WART, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP, Material.MELON_STEM, Material.PUMPKIN_STEM, Material.SWEET_BERRY_BUSH, Material.COCOA);

    /** Blocks of the `len` tiles straight ahead of the caster (horizontal facing), crop height only. */
    List<Block> ahead(Player pl, int len) {
        Location base = pl.getLocation();
        Vector f = base.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) return List.of();
        f.normalize();
        var out = new ArrayList<Block>();
        var seen = new HashSet<Block>();
        for (int i = 1; i <= len; i++) {
            Location at = base.clone().add(f.clone().multiply(i));
            for (int dy : new int[]{0, -1, 1}) {
                Block b = at.getBlock().getRelative(0, dy, 0);
                if (CROPS.contains(b.getType()) && seen.add(b)) { out.add(b); break; }
            }
        }
        return out;
    }

    private interface Each { boolean apply(Player pl, Block b); }
    private boolean line(Player pl, Each each) { return line(pl, each, "external:grow"); }
    private boolean line(Player pl, Each each, String event) {
        int done = 0;
        for (Block b : ahead(pl, Math.clamp(p.i("len", 5), 1, 16))) {
            if (!MagicMythicAccess.at(pl, b.getLocation())) continue;
            if (each.apply(pl, b)) { done++; vfx(pl, event, b.getLocation().add(0.5, 0, 0.5)); }
        }
        return done > 0;
    }

    private boolean harvest(Player pl, Block b) {
        if (!(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return false;
        if (b.getType() == Material.MELON_STEM || b.getType() == Material.PUMPKIN_STEM) return false; // stems are not a harvest
        if (!Targets.canBreak(pl, b)) return false;
        b.breakNaturally(new ItemStack(Material.AIR), true);
        return true;
    }

    private boolean grow(Block b, int toAge) {
        if (!(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) return false;
        BlockState next = b.getState();
        Ageable grown = (Ageable) a.clone();
        grown.setAge(Math.min(a.getMaximumAge(), toAge));
        next.setBlockData(grown);
        var event = new BlockGrowEvent(b, next);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        next.update(true);
        return true;
    }
    private boolean ripen(Player pl, Block b) {
        return b.getBlockData() instanceof Ageable a && grow(b, a.getMaximumAge());
    }
    private boolean revive(Player pl, Block b) {
        Block soil = b.getRelative(BlockFace.DOWN);
        if (!(soil.getBlockData() instanceof Farmland f) || f.getMoisture() > 0) return false;
        if (!Targets.canPlace(pl, soil, soil.getRelative(BlockFace.DOWN), Material.WATER_BUCKET)) return false;
        f.setMoisture(f.getMaximumMoisture());
        soil.setBlockData(f);
        if (b.getBlockData() instanceof Ageable a) grow(b, a.getAge() + 1);
        return true;
    }

    // ------------------------------------------------------------------ planting
    private static final Map<Material, Material> SEEDS = Map.of(
        Material.WHEAT_SEEDS, Material.WHEAT, Material.BEETROOT_SEEDS, Material.BEETROOTS, Material.CARROT, Material.CARROTS,
        Material.POTATO, Material.POTATOES, Material.MELON_SEEDS, Material.MELON_STEM, Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM,
        Material.TORCHFLOWER_SEEDS, Material.TORCHFLOWER_CROP, Material.PITCHER_POD, Material.PITCHER_CROP);

    /** The seed stack to use: the one in hand first, otherwise the first seed in the inventory. */
    private ItemStack seedStack(Player pl) {
        var inv = pl.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (SEEDS.containsKey(hand.getType())) return hand;
        for (ItemStack it : inv.getStorageContents()) if (it != null && SEEDS.containsKey(it.getType()) && it.getAmount() > 0) return it;
        return null;
    }

    private boolean seedRow(Player pl) {
        Location base = pl.getLocation();
        Vector f = base.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) return false;
        f.normalize();
        int planted = 0, max = Math.clamp(p.i("max", 6), 1, 16);
        for (int i = 1; i <= max; i++) {
            ItemStack seeds = seedStack(pl);
            if (seeds == null) break;
            Location at = base.clone().add(f.clone().multiply(i));
            Block air = null;
            for (int dy : new int[]{0, 1, -1}) {
                Block b = at.getBlock().getRelative(0, dy, 0);
                if (b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND) { air = b; break; }
            }
            if (air == null) continue;
            Material crop = SEEDS.get(seeds.getType());
            if (!Targets.canPlace(pl, air, air.getRelative(BlockFace.DOWN), seeds.getType())) continue;
            air.setType(crop);
            if (pl.getGameMode() != GameMode.CREATIVE) seeds.setAmount(seeds.getAmount() - 1);
            vfx(pl, "external:land", air.getLocation().add(0.5, 0, 0.5));
            planted++;
        }
        return planted > 0;
    }

    // ------------------------------------------------------------------ blocks at the aim point
    private static Block aimedSolid(Player pl, Location at) {
        Block b = at.getBlock();
        if (!b.getType().isAir()) return b;
        Vector dir = at.toVector().subtract(pl.getEyeLocation().toVector());
        if (dir.lengthSquared() < 1e-4) return b;
        return at.clone().add(dir.normalize().multiply(0.35)).getBlock();
    }

    private boolean grass(Player pl, Location at) {
        Block origin = aimedSolid(pl, at);
        int size = Math.clamp(p.i("size", 2), 1, 4), done = 0;
        // 2x2 grows toward the side of the block the aim point is on
        int sx = at.getX() - origin.getX() >= 0.5 ? 0 : -(size - 1), sz = at.getZ() - origin.getZ() >= 0.5 ? 0 : -(size - 1);
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            Block b = origin.getRelative(sx + x, 0, sz + z);
            if (b.getType() != Material.DIRT || !b.getRelative(BlockFace.UP).isPassable()) continue;
            if (!Targets.canPlace(pl, b, b.getRelative(BlockFace.DOWN), Material.GRASS_BLOCK)) continue;
            b.setType(Material.GRASS_BLOCK);
            done++;
        }
        if (done > 0) vfx(pl, "external:sprout", origin.getLocation().add(0.5, 1, 0.5));
        return done > 0;
    }

    /** OAK_LOG -> STRIPPED_OAK_LOG, CRIMSON_HYPHAE -> STRIPPED_CRIMSON_HYPHAE ... (null when not strippable). */
    static Material stripped(Material m) {
        String n = m.name();
        if (n.startsWith("STRIPPED_")) return null;
        if (!(n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE") || n.equals("BAMBOO_BLOCK"))) return null;
        if (n.endsWith("_STEM") && !(n.startsWith("CRIMSON") || n.startsWith("WARPED"))) return null; // melon/pumpkin stems
        return Material.matchMaterial("STRIPPED_" + n);
    }

    private boolean strip(Player pl, Location at) {
        Block start = aimedSolid(pl, at);
        Material kind = start.getType();
        if (stripped(kind) == null) return false;
        int max = Math.clamp(p.i("max", 6), 1, 64), done = 0;
        var queue = new ArrayDeque<Block>(List.of(start));
        var seen = new HashSet<Block>();
        while (!queue.isEmpty() && done < max) {
            Block b = queue.poll();
            if (!seen.add(b) || b.getType() != kind) continue;
            if (!Targets.canBreak(pl, b)) continue;
            BlockData before = b.getBlockData();
            BlockData after = stripped(kind).createBlockData();
            if (before instanceof Orientable o && after instanceof Orientable o2) o2.setAxis(o.getAxis());
            b.setBlockData(after);
            vfx(pl, "external:peel", b.getLocation().add(0.5, 0.5, 0.5));
            done++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dy != 0 || dz != 0) queue.add(b.getRelative(dx, dy, dz));
        }
        return done > 0;
    }

    // ------------------------------------------------------------------ items
    /** Items needed for `boneMeal` at the vanilla expected rate (7 layers per bone meal). */
    static int compostCost(float chance, int boneMeal) {
        if (chance <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(boneMeal * 7 / chance - 1e-9);
    }
    static int compostYield(float chance, int items) {
        if (chance <= 0) return 0;
        return (int) Math.floor(items * chance / 7 + 1e-9);
    }

    private boolean compost(Player pl) {
        ItemStack hand = pl.getInventory().getItemInMainHand();
        Material m = hand.getType();
        if (!m.isCompostable()) return false;
        float chance = m.getCompostChance();
        int bones = compostYield(chance, hand.getAmount());
        if (bones <= 0) return false;
        int cost = Math.min(hand.getAmount(), compostCost(chance, bones));
        hand.setAmount(hand.getAmount() - cost);
        pl.getInventory().addItem(new ItemStack(Material.BONE_MEAL, bones)).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        return true;
    }

    private boolean craft(Player pl) {
        String[] in = p.s("in", "").split(":"), out = p.s("out", "").split(":");
        Material inM = Material.matchMaterial(in[0]), outM = Material.matchMaterial(out[0]);
        if (inM == null || outM == null) return false;
        int need, give;
        try { need = in.length > 1 ? Integer.parseInt(in[1].trim()) : 1; give = out.length > 1 ? Integer.parseInt(out[1].trim()) : 1; }
        catch (NumberFormatException e) { return false; }
        if (need < 1 || give < 1) return false;
        var inv = pl.getInventory();
        if (!inv.containsAtLeast(new ItemStack(inM), need)) return false;
        inv.removeItem(new ItemStack(inM, need));
        inv.addItem(new ItemStack(outM, give)).values().forEach(i -> pl.getWorld().dropItemNaturally(pl.getLocation(), i));
        return true;
    }

    // ------------------------------------------------------------------ creatures
    private boolean breed(Player pl, Ctx c) {
        double r = p.d("r", 8);
        Animals first = c.target() instanceof Animals a && a.canBreed() && !a.isLoveMode() ? a : null;
        Location around = first != null ? first.getLocation() : c.at();
        if (first == null) first = nearestBreedable(pl, around, r, null);
        if (first == null) return false;
        Animals second = nearestBreedable(pl, first.getLocation(), r, first);
        if (second == null) return false;
        for (Animals a : List.of(first, second)) {
            a.setBreedCause(pl.getUniqueId());
            a.setLoveModeTicks(600);
            if(!p.s("spell", "").isBlank())Links.vfx(pl,p.s("spell",""),"external:send",a.getLocation(),a.getUniqueId());
        }
        if (!p.s("spell", "").isBlank()) Links.vfx(pl, p.s("spell", ""), "external:connect", first.getLocation(), second.getUniqueId());
        return true;
    }
    private Animals nearestBreedable(Player pl, Location at, double r, Animals pairWith) {
        Animals best = null; double bd = Double.MAX_VALUE;
        for (Entity e : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (!(e instanceof Animals a) || e.equals(pairWith) || !a.isAdult() || !a.canBreed() || a.isLoveMode()) continue;
            if (pairWith != null && a.getType() != pairWith.getType()) continue;
            if (!plugin.canTouch(pl, a)) continue;
            double d = e.getLocation().distanceSquared(at);
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }

    private boolean bees(Player pl) {
        double r = p.d("r", 8);
        int calmed = 0;
        for (Entity e : pl.getWorld().getNearbyEntities(pl.getLocation(), r, r, r)) {
            if (!(e instanceof Bee bee) || bee.getAnger() <= 0 || !plugin.canTouch(pl, bee)) continue;
            bee.setAnger(0);
            bee.setTarget(null);
            calmed++;
            if (!p.s("spell", "").isBlank()) Links.vfx(pl, p.s("spell", ""), "external:calm", bee.getLocation(), bee.getUniqueId());
        }
        return calmed > 0;
    }

    private boolean weather(Player pl) {
        World w = pl.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || !plugin.protection().at(pl.getLocation())) return false;
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        if (w.hasStorm()) {
            w.setStorm(false); w.setThundering(false);
            w.setWeatherDuration(rnd.nextInt(12000, 180001)); // vanilla clear spell length; nature takes over afterwards
            vfx(pl, "external:clear", pl.getLocation());
        } else {
            w.setStorm(true);
            w.setWeatherDuration(rnd.nextInt(12000, 24001)); // vanilla rain length
            vfx(pl, "external:rain", pl.getLocation());
        }
        return true;
    }
}
