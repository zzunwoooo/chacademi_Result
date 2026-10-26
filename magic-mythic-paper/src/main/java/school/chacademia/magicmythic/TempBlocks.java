package school.chacademia.magicmythic;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Temporary blocks placed by spells (air_step platforms), journaled to temp-blocks.yml.
 * <ul>
 *   <li>Write-ahead: the record is saved (atomically, temp file + move) <b>before</b> the block is placed.
 *       If the journal cannot be written, nothing is placed.</li>
 *   <li>Records are dropped from the journal only after the original block is back.</li>
 *   <li>Records for a world that is not loaded are kept and restored when that world loads.</li>
 * </ul>
 */
final class TempBlocks implements Listener {
    record Entry(String world, int x, int y, int z, Material placed, String original) {
        String key() { return world + ":" + x + ":" + y + ":" + z; }
        String line() { return String.join("|", world, "" + x, "" + y, "" + z, placed.name(), original); }
        static Entry parse(String s) {
            String[] f = s.split("\\|", 6);
            if (f.length < 6) return null;
            try { return new Entry(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Material.valueOf(f[4]), f[5]); }
            catch (IllegalArgumentException e) { return null; }
        }
    }
    private final java.util.logging.Logger logger;
    private final java.util.function.Function<String,World> worlds;
    private final java.util.function.Function<String,org.bukkit.block.data.BlockData> blockData;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    TempBlocks(MagicMythicPlugin plugin) {
        this(new File(plugin.getDataFolder(), "temp-blocks.yml"), plugin.getLogger(), Bukkit::getWorld, Bukkit::createBlockData);
    }
    TempBlocks(File file, java.util.logging.Logger logger, java.util.function.Function<String,World> worlds, java.util.function.Function<String,org.bukkit.block.data.BlockData> blockData) {
        this.file=file; this.logger=logger; this.worlds=worlds; this.blockData=blockData;
    }
    int count() { return entries.size(); }

    /** Place only into air; returns false when the spot is not free or the journal could not be written. */
    boolean place(Block b, Material material) {
        if (b.getType()!=Material.AIR && b.getType()!=Material.CAVE_AIR && b.getType()!=Material.VOID_AIR) return false;
        var e = new Entry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), material, b.getBlockData().getAsString());
        entries.put(e.key(), e);
        if (!save()) { entries.remove(e.key()); return false; } // journal first; never place an unrecorded block
        b.setType(material, false);
        return true;
    }

    void remove(Block b) {
        var e = entries.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ());
        if (e != null && restore(e)) { entries.remove(e.key()); save(); }
    }

    /**
     * Batch place (one journal write per cast instead of one per block).
     * Order: collect originals of the free (air) spots → save the journal once → place only if that save succeeded.
     * @return the blocks actually placed (empty when nothing was free or the journal could not be written).
     */
    List<Block> placeAll(Collection<Block> blocks, Material material) {
        var added = new ArrayList<Entry>(); var targets = new ArrayList<Block>();
        for (Block b : blocks) {
            if (b.getType()!=Material.AIR && b.getType()!=Material.CAVE_AIR && b.getType()!=Material.VOID_AIR) continue;
            var e = new Entry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), material, b.getBlockData().getAsString());
            if (entries.containsKey(e.key())) continue; // already ours (or listed twice): never overwrite its original
            entries.put(e.key(), e); added.add(e); targets.add(b);
        }
        if (added.isEmpty()) return List.of();
        if (!save()) { for (Entry e : added) entries.remove(e.key()); return List.of(); } // journal first; never place unrecorded blocks
        for (Block b : targets) b.setType(material, false);
        return targets;
    }

    /**
     * Batch remove for one cast's blocks (one journal write). Records whose block could not be restored
     * (world not loaded) stay journaled. The no-argument {@link #removeAll()} stays the full shutdown restore.
     */
    void removeAll(Collection<Block> blocks) {
        boolean changed = false;
        for (Block b : blocks) {
            var e = entries.get(b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ());
            if (e != null && restore(e)) { entries.remove(e.key()); changed = true; }
        }
        if (changed) save();
    }

    /** Plugin disable: put back everything we can; anything in an unloaded world stays journaled. */
    void removeAll() {
        entries.values().removeIf(this::restore);
        save();
    }

    /** Startup: restore leftovers from a crash or unclean stop. */
    void restoreLeftovers() {
        if (!file.exists()) return;
        var yaml = YamlConfiguration.loadConfiguration(file);
        for (String s : yaml.getStringList("blocks")) {
            Entry e = Entry.parse(s);
            if (e != null) entries.put(e.key(), e);
        }
        int before = entries.size();
        entries.values().removeIf(this::restore);
        save();
        if (before > 0) logger.info("임시 블록 복구: " + (before - entries.size()) + "개 복구, " + entries.size() + "개는 월드가 로드되면 복구");
    }

    @EventHandler public void worldLoad(WorldLoadEvent event) {
        String name = event.getWorld().getName();
        if (entries.values().removeIf(e -> e.world().equals(name) && restore(e))) save();
    }

    /** @return true when the record is finished (restored, or the block was already changed by someone else). */
    private boolean restore(Entry e) {
        World w = worlds.apply(e.world());
        if (w == null) return false; // keep until the world loads
        Block b = w.getBlockAt(e.x(), e.y(), e.z());
        if (b.getType() != e.placed()) return true; // someone already changed it; leave it alone
        try { b.setBlockData(blockData.apply(e.original()), false); }
        catch (IllegalArgumentException ex) { b.setType(Material.AIR, false); }
        return true;
    }

    /** Atomic write: temp file then move over the journal. */
    private boolean save() {
        var yaml = new YamlConfiguration();
        var list = new ArrayList<String>();
        for (Entry e : entries.values()) list.add(e.line());
        yaml.set("blocks", list);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            file.getParentFile().mkdirs();
            yaml.save(tmp);
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException ex) {
            logger.warning("temp-blocks.yml 저장 실패 (임시 블록을 놓지 않음): " + ex.getMessage());
            return false;
        }
    }
}
