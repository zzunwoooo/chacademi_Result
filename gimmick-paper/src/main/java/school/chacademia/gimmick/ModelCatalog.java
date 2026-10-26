package school.chacademia.gimmick;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** models.yml: size and animation lengths per model (generated from dungeon-gimmicks). Unknown models still work. */
final class ModelCatalog {
    record Anim(double seconds, String loop) { long ticks() { return Math.max(1, Math.round(seconds * 20)); } }
    /**
     * kind: device behaviour (conduit, mirror, prism, dial, statue, pillar, plate, vent, platform, gate, wheel, pot, seal, valve,
     * receiver, bridge, door, lever, brazier, turbine, chest, monument, sluice ...). footW x footD: blocks taken (right x forward).
     * collision: solid | floor | platform | none. open: "on" / "off" = state in which the collision is lifted ("" = never).
     * ports: conduit sides (front/back/left/right).
     */
    record Info(String id, String name, String category, double width, double height, Map<String, Anim> anims,
                String kind, int footW, int footD, String collision, String open, List<String> ports) {
        boolean has(String anim) { return anim != null && anims.containsKey(anim); }
        boolean is(String k) { return kind.equals(k); }
        int layers() { return Math.max(1, (int) Math.ceil(height - 1e-6)); }
    }
    private final Map<String, Info> models = new TreeMap<>();

    void load(File file) {
        models.clear();
        var yaml = AtomicYaml.load(file);
        ConfigurationSection root = yaml.getConfigurationSection("models");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            var anims = new LinkedHashMap<String, Anim>();
            var as = s.getConfigurationSection("animations");
            if (as != null) for (String a : as.getKeys(false))
                anims.put(a, new Anim(as.getDouble(a + ".length", 1), as.getString(a + ".loop", "once")));
            var fp = s.getIntegerList("footprint");
            int fw = fp.size() > 0 ? Math.clamp(fp.get(0), 1, 8) : Math.max(1, (int) Math.round(s.getDouble("width", 1)));
            int fd = fp.size() > 1 ? Math.clamp(fp.get(1), 1, 8) : fw;
            String col = s.getString("collision", "solid").toLowerCase(Locale.ROOT);
            if (!Set.of("solid", "floor", "platform", "none").contains(col)) col = "solid";
            models.put(id, new Info(id, s.getString("name", id), s.getString("category", "?"),
                Math.max(0.2, s.getDouble("width", 1)), Math.max(0.1, s.getDouble("height", 1)), Collections.unmodifiableMap(anims),
                s.getString("kind", "generic").toLowerCase(Locale.ROOT), fw, fd, col, s.getString("open", "").toLowerCase(Locale.ROOT),
                List.copyOf(s.getStringList("ports").stream().map(x -> x.toLowerCase(Locale.ROOT)).toList())));
        }
    }
    Info get(String id) {
        Info i = models.get(id);
        return i != null ? i : new Info(id, id, "?", 1, 1, Map.of(), "generic", 1, 1, "solid", "", List.of());
    }
    boolean known(String id) { return models.containsKey(id); }
    Set<String> ids() { return models.keySet(); }
}
