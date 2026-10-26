package school.chacademia.gimmick;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * One placed (or mob-attached) model. Placed ones live on the block grid (2026-10-20 owner: "가구 설치하듯이"):
 * anchor block (bx, by, bz) = the block the model was put on, facing 0..3 = south / west / north / east (yaw = facing x 90).
 * The model position (x, y, z) is derived from the anchor, the footprint and the collision type, never edited freely.
 * Saved in gimmicks.yml; the entities are rebuilt on chunk load.
 */
final class Gimmick {
    final String id;
    String model;
    String world;
    double x, y, z;
    float yaw;
    double scale = 1;
    /** Mob-attached: no saved position, follows the host. */
    final UUID host;

    // grid placement (placed gimmicks)
    int bx, by, bz, facing;
    // device state (saved)
    int pos;              // mirror / dial position 0..3
    int level;            // water pillar 0..3
    String color = "white"; // prism colour
    double travel;        // floating platform: 0 = start, 1 = moved by its 정답 (앞으로/위로 N칸)
    boolean latched;      // own saved on/off: lever pulled, brazier lit, ice thawed, door opened ... (Signals.LATCHED)
    int answer = -1;      // 정답: water level / dial position / statue facing / receiver colour / platform route (-1 = default)

    // live (not saved)
    Renderer.Handle handle;
    Interaction hitbox;
    int animToken;
    String lastAnim = "";

    Gimmick(String id, String model, String world, int bx, int by, int bz, int facing) {
        this.id = id; this.model = model; this.host = null; this.world = world;
        this.bx = bx; this.by = by; this.bz = bz; this.facing = Math.floorMod(facing, 4); this.yaw = this.facing * 90f;
    }
    Gimmick(String id, String model, UUID host) {
        this.id = id; this.model = model; this.host = host; this.world = "";
    }

    boolean attached() { return host != null; }
    boolean live() { return handle != null; }

    // ------------------------------------------------------------------ grid
    static int[] forward(int facing) {
        return switch (Math.floorMod(facing, 4)) { case 0 -> new int[]{0, 1}; case 1 -> new int[]{-1, 0}; case 2 -> new int[]{0, -1}; default -> new int[]{1, 0}; };
    }
    /** The model's right side when it faces `facing` (facing south = west). */
    static int[] right(int facing) { int[] f = forward(facing); return new int[]{-f[1], f[0]}; }
    static int facingOf(float yaw) { return Math.floorMod(Math.round(yaw / 90f), 4); }

    /** First / last offset of a footprint axis of n blocks: 3 -> -1..1, 2 -> 0..1, 1 -> 0..0. */
    private static int lo(int n) { return -(n - 1) / 2; }

    /** Footprint columns (x, z) for the anchor, footprint and facing. */
    List<int[]> columns(ModelCatalog.Info info) { return columns(info, 0, 0); }
    List<int[]> columns(ModelCatalog.Info info, int dx, int dz) {
        int[] f = forward(facing), r = right(facing);
        var out = new ArrayList<int[]>();
        for (int i = lo(info.footW()); i < lo(info.footW()) + info.footW(); i++)
            for (int j = lo(info.footD()); j < lo(info.footD()) + info.footD(); j++)
                out.add(new int[]{bx + dx + r[0] * i + f[0] * j, bz + dz + r[1] * i + f[1] * j});
        return out;
    }

    /** Recompute the model position from the grid (+ a fractional offset for a moving platform). */
    void place(ModelCatalog.Info info, Vector offset) {
        int[] f = forward(facing), r = right(facing);
        double ci = lo(info.footW()) + (info.footW() - 1) / 2.0, cj = lo(info.footD()) + (info.footD() - 1) / 2.0;
        x = bx + 0.5 + r[0] * ci + f[0] * cj;
        z = bz + 0.5 + r[1] * ci + f[1] * cj;
        y = by + ("platform".equals(info.collision()) ? Math.max(0, 1 - info.height() * scale) : 0);
        if (offset != null) { x += offset.getX(); y += offset.getY(); z += offset.getZ(); }
        yaw = facing * 90f;
    }

    /** Current position: saved spot, or the host mob. */
    Location location() {
        if (attached()) {
            Entity e = Bukkit.getEntity(host);
            return e == null ? null : e.getLocation();
        }
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, 0);
    }

    int chunkX() { return (int) Math.floor(x) >> 4; }
    int chunkZ() { return (int) Math.floor(z) >> 4; }

    /** Horizontal facing as a unit vector (yaw 0 = south, Minecraft convention). */
    Vector forward() {
        if (!attached()) { int[] f = forward(facing); return new Vector(f[0], 0, f[1]); }
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    /** Collision/selection box: the footprint blocks (placed) or the model width (attached), model height tall. */
    BoundingBox box(ModelCatalog.Info info) {
        Location l = location();
        if (l == null) return new BoundingBox();
        double h = Math.max(0.2, info.height() * scale);
        if (attached()) {
            double hw = info.width() * scale / 2;
            return new BoundingBox(l.getX() - hw, l.getY(), l.getZ() - hw, l.getX() + hw, l.getY() + h, l.getZ() + hw);
        }
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double ox = x - (bx + 0.5) - centreX(info), oz = z - (bz + 0.5) - centreZ(info); // moving platform offset
        for (int[] c : columns(info)) { minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0] + 1); minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1] + 1); }
        // thin models keep a small inset so a block-wide box does not swallow clicks meant for neighbours
        double inset = info.footW() == 1 && info.footD() == 1 ? Math.max(0, (1 - info.width() * scale) / 2 - 0.05) : 0;
        return new BoundingBox(minX + ox + inset, y, minZ + oz + inset, maxX + ox - inset, y + h, maxZ + oz - inset);
    }
    private double centreX(ModelCatalog.Info info) { int[] f = forward(facing), r = right(facing); return r[0] * (lo(info.footW()) + (info.footW() - 1) / 2.0) + f[0] * (lo(info.footD()) + (info.footD() - 1) / 2.0); }
    private double centreZ(ModelCatalog.Info info) { int[] f = forward(facing), r = right(facing); return r[1] * (lo(info.footW()) + (info.footW() - 1) / 2.0) + f[1] * (lo(info.footD()) + (info.footD() - 1) / 2.0); }

    /** Blocks tall (model height x scale, rounded up). Collision, block index and placement all use this. */
    int layers(ModelCatalog.Info info) { return Math.max(1, (int) Math.ceil(info.height() * scale - 1e-6)); }

    /** Snapshot for 248 되감기. */
    record Snapshot(int facing, int pos, int level, String color, boolean latched, long at) {}
    Snapshot snapshot(long now) { return new Snapshot(facing, pos, level, color, latched, now); }

    Map<String, Object> save() {
        var m = new LinkedHashMap<String, Object>();
        m.put("model", model); m.put("world", world);
        m.put("block", List.of(bx, by, bz)); m.put("facing", facing);
        m.put("scale", round(scale));
        if (latched) m.put("on", true);
        if (answer >= 0) m.put("answer", answer);
        if (pos != 0) m.put("pos", pos);
        if (level != 0) m.put("level", level);
        if (!"white".equals(color)) m.put("color", color);
        if (travel != 0) m.put("travel", round(travel));
        return m;
    }
    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    static Gimmick load(String id, Map<?, ?> m) {
        int bx, by, bz, facing;
        if (m.get("block") instanceof List<?> b && b.size() == 3) {
            bx = (int) num(b.get(0)); by = (int) num(b.get(1)); bz = (int) num(b.get(2));
            facing = (int) num(m.get("facing"));
        } else { // pre-grid save (free position): snap to the block under it and the nearest 90 degrees
            bx = (int) Math.floor(num(m.get("x"))); by = (int) Math.floor(num(m.get("y")) + 1e-6); bz = (int) Math.floor(num(m.get("z")));
            facing = facingOf((float) num(m.get("yaw")));
        }
        var g = new Gimmick(id, String.valueOf(m.get("model")), String.valueOf(m.get("world")), bx, by, bz, facing);
        g.scale = m.containsKey("scale") ? num(m.get("scale")) : 1;
        g.latched = Boolean.TRUE.equals(m.get("on"));
        g.answer = m.containsKey("answer") ? (int) num(m.get("answer")) : -1;
        g.pos = Math.floorMod((int) num(m.get("pos")), 4);
        g.level = Math.clamp((int) num(m.get("level")), 0, 3);
        g.color = m.get("color") == null ? "white" : String.valueOf(m.get("color"));
        g.travel = Math.clamp(num(m.get("travel")), 0, 1);
        return g;
    }
    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }
}
