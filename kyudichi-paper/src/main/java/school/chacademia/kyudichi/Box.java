package school.chacademia.kyudichi;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Axis-aligned block box, inclusive block corners (pos1/pos2 like WorldEdit): covers [min, max+1). */
record Box(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
    Box {
        int a = Math.min(x1, x2), b = Math.max(x1, x2); x1 = a; x2 = b;
        a = Math.min(y1, y2); b = Math.max(y1, y2); y1 = a; y2 = b;
        a = Math.min(z1, z2); b = Math.max(z1, z2); z1 = a; z2 = b;
    }
    static Box of(Location a, Location b) {
        return new Box(a.getWorld().getName(), a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }
    double minX() { return x1; } double minY() { return y1; } double minZ() { return z1; }
    double maxX() { return x2 + 1.0; } double maxY() { return y2 + 1.0; } double maxZ() { return z2 + 1.0; }

    boolean contains(Vector p) { return contains(p.getX(), p.getY(), p.getZ()); }
    boolean contains(double x, double y, double z) {
        return x >= minX() && x < maxX() && y >= minY() && y < maxY() && z >= minZ() && z < maxZ();
    }
    boolean contains(Location l) { return l.getWorld() != null && l.getWorld().getName().equals(world) && contains(l.getX(), l.getY(), l.getZ()); }
    boolean inside(Box o) {
        return world.equals(o.world) && x1 >= o.x1 && y1 >= o.y1 && z1 >= o.z1 && x2 <= o.x2 && y2 <= o.y2 && z2 <= o.z2;
    }
    Vector center() { return new Vector((minX() + maxX()) / 2, (minY() + maxY()) / 2, (minZ() + maxZ()) / 2); }
    long volume() { return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }
    String size() { return (x2 - x1 + 1) + "×" + (y2 - y1 + 1) + "×" + (z2 - z1 + 1); }

    /** Pushes a point back inside by `margin` blocks; returns null if already inside the shrunk box. */
    Vector clampInside(Vector p, double margin) {
        double m = Math.min(margin, Math.min(Math.min(maxX() - minX(), maxY() - minY()), maxZ() - minZ()) / 2 - 0.01);
        double x = Math.clamp(p.getX(), minX() + m, maxX() - m), y = Math.clamp(p.getY(), minY() + m, maxY() - m), z = Math.clamp(p.getZ(), minZ() + m, maxZ() - m);
        return x == p.getX() && y == p.getY() && z == p.getZ() ? null : new Vector(x, y, z);
    }

    /** Segment a→b crosses the box (slab test). Used so a fast ball cannot skip a thin goal. */
    boolean segmentHits(Vector a, Vector b) {
        double t0 = 0, t1 = 1;
        double[] s = {a.getX(), a.getY(), a.getZ()}, d = {b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()};
        double[] lo = {minX(), minY(), minZ()}, hi = {maxX(), maxY(), maxZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) { if (s[i] < lo[i] || s[i] >= hi[i]) return false; continue; }
            double u = (lo[i] - s[i]) / d[i], v = (hi[i] - s[i]) / d[i];
            if (u > v) { double t = u; u = v; v = t; }
            t0 = Math.max(t0, u); t1 = Math.min(t1, v);
            if (t0 > t1) return false;
        }
        return true;
    }

    String serialize() { return world + "," + x1 + "," + y1 + "," + z1 + "," + x2 + "," + y2 + "," + z2; }
    static Box parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 7) return null;
        try {
            return new Box(p[0], Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                    Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()), Integer.parseInt(p[6].trim()));
        } catch (NumberFormatException e) { return null; }
    }
}
