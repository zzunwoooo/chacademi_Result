package kr.kkaegul.core.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Location <-> "world,x,y,z,yaw,pitch" 문자열 */
public final class Locs {
    private Locs() {}

    public static String toStr(Location l) {
        if (l == null || l.getWorld() == null) return "";
        return l.getWorld().getName() + "," + round(l.getX()) + "," + round(l.getY()) + "," + round(l.getZ())
                + "," + round(l.getYaw()) + "," + round(l.getPitch());
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }

    public static Location fromStr(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0].trim());
        if (w == null) return null;
        try {
            double x = Double.parseDouble(p[1].trim()), y = Double.parseDouble(p[2].trim()), z = Double.parseDouble(p[3].trim());
            float yaw = p.length > 4 ? Float.parseFloat(p[4].trim()) : 0f;
            float pitch = p.length > 5 ? Float.parseFloat(p[5].trim()) : 0f;
            return new Location(w, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
