package kr.kkaegul.core.util;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 이름 붙은 위치. /kg setloc <이름> 으로 저장(global.yml), 없으면 config.yml locations.<이름>.
 * 여러 개짜리는 이름 뒤에 .1 .2 ... (예: limited-mine.1)
 */
public final class Places {
    private final KkaegulCore plugin;

    public Places(KkaegulCore plugin) { this.plugin = plugin; }

    public Location get(String name) {
        String s = plugin.global().locations.get(name);
        if (s == null) s = plugin.getConfig().getString("locations." + name, "");
        Location l = Locs.fromStr(s);
        return l == null ? null : l.clone();
    }

    public void set(String name, Location l) {
        plugin.global().locations.put(name, Locs.toStr(l));
        plugin.global().dirty = true;
    }

    public boolean remove(String name) {
        boolean r = plugin.global().locations.remove(name) != null;
        plugin.global().dirty = true;
        return r;
    }

    /** name.1, name.2 ... 모두 */
    public List<Location> list(String prefix) {
        List<Location> out = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(plugin.global().locations.keySet());
        keys.addAll(configKeys());
        for (String k : keys) {
            if (k.equals(prefix) || k.startsWith(prefix + ".")) {
                Location l = get(k);
                if (l != null) out.add(l);
            }
        }
        return out;
    }

    /** 기계 블록: prefix.N 또는 prefix.N.아무거나 위치(기계 앞에 서서 저장한 자리)에서 2칸 안의 블록이면 N (가장 가까운 것), 아니면 null */
    public String machineAt(String prefix, org.bukkit.block.Block b) {
        if (b == null || b.getWorld() == null) return null;
        Location bl = b.getLocation().add(0.5, 0.5, 0.5);
        double r = plugin.getConfig().getDouble("machines.radius", 2.0), best = r * r + 1e-6;
        String hit = null;
        TreeSet<String> keys = new TreeSet<>(plugin.global().locations.keySet());
        keys.addAll(configKeys());
        for (String k : keys) {
            if (!k.startsWith(prefix + ".")) continue;
            Location l = get(k);
            if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(b.getWorld().getName())) continue;
            double d = l.distanceSquared(bl);
            if (d <= best) { best = d; String id = k.substring(prefix.length() + 1); int dot = id.indexOf('.'); hit = dot < 0 ? id : id.substring(0, dot); }   // gacha-machine.1.a → "1" (같은 기계 여러 곳)
        }
        return hit;
    }

    public java.util.Set<String> names() {
        TreeSet<String> keys = new TreeSet<>(plugin.global().locations.keySet());
        keys.addAll(configKeys());
        return keys;
    }

    /** 이동. 위치가 없으면 false + 관리자 안내 */
    public boolean tp(Player p, String name) {
        Location l = get(name);
        if (l == null) {
            plugin.ui().error(p, "&c[깨굴] 위치 '" + name + "' 가 설정되지 않았어요. 관리자: /kg setloc " + name);
            return false;
        }
        p.teleport(l);
        return true;
    }

    /** config.yml locations 의 키 (점이 들어간 이름도 포함) */
    private java.util.Set<String> configKeys() {
        java.util.Set<String> out = new TreeSet<>();
        var sec = plugin.getConfig().getConfigurationSection("locations");
        if (sec != null) for (String k : sec.getKeys(true)) if (sec.isString(k)) out.add(k);
        return out;
    }

    public Location lobby() {
        Location l = get("lobby");
        if (l == null && !plugin.getServer().getWorlds().isEmpty()) l = plugin.getServer().getWorlds().get(0).getSpawnLocation();
        return l;
    }
}
