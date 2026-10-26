package school.chacademia.kyudichi;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** A saved position + facing (world by name, so arenas load before the world). */
record Point(String world, double x, double y, double z, float yaw, float pitch) {
    static Point of(Location l) { return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }
    Location location() { World w = Bukkit.getWorld(world); return w == null ? null : new Location(w, x, y, z, yaw, pitch); }
    Vector vector() { return new Vector(x, y, z); }
    String serialize() { return String.format(java.util.Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.1f,%.1f", world, x, y, z, yaw, pitch); }
    static Point parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length < 4) return null;
        try {
            return new Point(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
        } catch (NumberFormatException e) { return null; }
    }
}
