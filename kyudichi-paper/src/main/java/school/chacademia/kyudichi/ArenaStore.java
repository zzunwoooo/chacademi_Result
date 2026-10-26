package school.chacademia.kyudichi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** arenas.yml: every arena, written atomically after each admin change. */
final class ArenaStore {
    private final Path file;
    private final Logger log;
    private final Map<String, Arena> arenas = new TreeMap<>();

    ArenaStore(Path file, Logger log) { this.file = file; this.log = log; }

    void load() {
        arenas.clear();
        if (!Files.exists(file)) return;
        var y = new YamlConfiguration();
        try { y.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (IOException | InvalidConfigurationException e) { log.severe("arenas.yml 읽기 실패: " + e.getMessage()); return; }
        var root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            var s = root.getConfigurationSection(name);
            if (s != null && valid(name)) arenas.put(name, Arena.load(name, s));
        }
    }

    void save() {
        var y = new YamlConfiguration();
        for (Arena a : arenas.values()) a.save(y.createSection("arenas." + a.name));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { log.severe("arenas.yml 저장 실패: " + e.getMessage()); }
    }

    static boolean valid(String name) { return name.matches("[0-9A-Za-z가-힣_-]{1,32}"); }
    Arena get(String name) { return arenas.get(name); }
    Collection<Arena> all() { return arenas.values(); }
    Arena create(String name) { var a = new Arena(name); arenas.put(name, a); save(); return a; }
    boolean remove(String name) { boolean r = arenas.remove(name) != null; if (r) save(); return r; }
    static String key(String s) { return s.toLowerCase(Locale.ROOT); }
}
