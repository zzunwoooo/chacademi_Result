package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 지도 이동 메뉴 (2026-10-23). The server opens the window (/지도) and decides everything; the client only shows the
 * map, lets the player pick a point and asks to travel there. The client never says what is unlocked or where the
 * personal point is: TRAVEL names a point id, the server checks its own unlock record (shared DB) and its own config.
 *
 * Tabs are exactly the configured ones (owner: 학교 / 야생1 / 야생2). A tab shows either a fixed image map (학교 town map,
 * replaceable: texture + three world reference corners in travel.yml, no code change) or the client's own terrain cache
 * of that server+world (야생), or nothing but a coordinate grid when neither exists yet.
 *
 * C2S  int version, byte action, then
 *        QUERY / CLOSE / OPEN   nothing (OPEN = the map key; the server decides whether to open)
 *        TRAVEL           UTF pointId, long operation
 * S2C  int version, byte frame, then
 *        STATE    byte kind (OPEN also opens the window), UTF currentTab, player (UTF tab, double x, double z, float yaw),
 *                 short tabs n x Tab, short points n x Point, UTF notice
 *        RESULT   long operation, byte stage (ACCEPTED / DONE / FAILED), UTF notice
 *        MARKERS  UTF world, UTF cache (minimap cache key of this world, "" = no map tab), short n x (UTF id, UTF name, double x, y, z, float radius, boolean unlocked)
 *                 -- the public points of the world the player is in on this server, for the portal marks drawn in
 *                 the world (only near ones are drawn). Sent on join, world change, unlock and reload.
 */
public final class TravelProtocol {
    public static final String REQUEST = "magiccodex:travel_request", RESPONSE = "magiccodex:travel_response";
    public static final int VERSION = 1, MAX_REQUEST_BYTES = 256, MAX_BYTES = 131072;
    public static final int QUERY = 0, TRAVEL = 1, CLOSE = 2, OPEN = 3;
    public static final int KIND_OPEN = 0, KIND_STATE = 1;
    public static final int FRAME_STATE = 1, FRAME_RESULT = 2, FRAME_MARKERS = 3;
    public static final int MAP_NONE = 0, MAP_IMAGE = 1, MAP_TERRAIN = 2;
    public static final int POINT_PUBLIC = 0, POINT_PERSONAL = 1;
    /** UNLOCKED = can travel. LOCKED = 미발견 (never travels). UNAVAILABLE = known, but its server/world can't take players now (reason says why). */
    public static final int UNLOCKED = 0, LOCKED = 1, UNAVAILABLE = 2;
    public static final int STAGE_ACCEPTED = 0, STAGE_DONE = 1, STAGE_FAILED = 2;
    public static final int MAX_TABS = 8, MAX_POINTS = 512, MAX_NAME = 32, MAX_TEXT = 120, MAX_TEXTURE = 160;
    /** The personal point's fixed id (one per player across every server, owner 2026-10-23). */
    public static final String PERSONAL_ID = "~personal";

    /** Point / tab / server / world ids: lower case ascii (travel.yml keys and DB values). */
    public static boolean validId(String s) { return s != null && s.matches("[a-z0-9_\\-]{1,48}"); }
    public static boolean validPointId(String s) { return PERSONAL_ID.equals(s) || validId(s); }
    public static boolean validWorld(String s) { return s != null && s.matches("[A-Za-z0-9_\\-./]{1,64}"); }
    private static boolean text(String s, int max) { return s != null && s.length() <= max; }
    private static boolean finite(double... v) { for (double d : v) if (!Double.isFinite(d) || Math.abs(d) > 3.0E7) return false; return true; }

    public record Request(int action, String pointId, long operation) {
        public Request {
            if (action < QUERY || action > OPEN || pointId == null) throw new IllegalArgumentException("travel request");
            if (action == TRAVEL && (!validPointId(pointId) || operation == 0)) throw new IllegalArgumentException("travel request point");
        }
        public static Request of(int action) { return new Request(action, "", 0); }
        public static Request travel(String pointId, long operation) { return new Request(TRAVEL, pointId, operation); }
    }

    /**
     * One map tab. IMAGE: texture (resource id, e.g. magiccodex:textures/gui/travel/maps/school.png) of imageW x imageH
     * pixels whose top-left / top-right / bottom-left pixel corners are the given world x,z points (any rotation or
     * mirror, an affine map). TERRAIN: cacheKey names the client terrain cache (server id / world / reset epoch) and
     * minX..maxZ bound the area the map may show. Both: world is the world name on that server.
     */
    public record Tab(String id, String label, String server, String world, int map, String texture, int imageW, int imageH,
                      double tlX, double tlZ, double trX, double trZ, double blX, double blZ,
                      String cacheKey, double minX, double minZ, double maxX, double maxZ, boolean online, String status) {
        public Tab {
            if (!validId(id) || label == null || label.isBlank() || label.length() > MAX_NAME || !validId(server) || !validWorld(world)
                    || map < MAP_NONE || map > MAP_TERRAIN || !text(texture, MAX_TEXTURE) || !text(cacheKey, MAX_TEXTURE)
                    || imageW < 0 || imageH < 0 || imageW > 16384 || imageH > 16384
                    || !finite(tlX, tlZ, trX, trZ, blX, blZ, minX, minZ, maxX, maxZ) || minX > maxX || minZ > maxZ || !text(status, MAX_TEXT))
                throw new IllegalArgumentException("travel tab");
            if (map == MAP_IMAGE && (texture.isBlank() || imageW < 1 || imageH < 1)) throw new IllegalArgumentException("travel tab image");
        }
    }

    /**
     * A point. tab "" = its server/world is on no tab (e.g. a server taken out of the map): listed, not drawn.
     * reason: why it can't be used right now ("" when it can), shown as is.
     */
    public record Point(String id, String name, String tab, int kind, int state, double x, double y, double z, String reason) {
        public Point {
            if (!validPointId(id) || name == null || name.isBlank() || name.length() > MAX_NAME || tab == null || !(tab.isEmpty() || validId(tab))
                    || kind < POINT_PUBLIC || kind > POINT_PERSONAL || state < UNLOCKED || state > UNAVAILABLE
                    || !finite(x, y, z) || !text(reason, MAX_TEXT))
                throw new IllegalArgumentException("travel point");
        }
        public boolean travelable() { return state == UNLOCKED; }
    }

    /** Where the player stands now (tab "" = on a server/world no tab shows, e.g. the arena). */
    public record Position(String tab, double x, double z, float yaw) {
        public Position {
            if (tab == null || !(tab.isEmpty() || validId(tab)) || !finite(x, z) || !Float.isFinite(yaw)) throw new IllegalArgumentException("travel position");
        }
    }

    /** A public point's portal mark in the world the player is in. */
    public record Marker(String id, String name, double x, double y, double z, float radius, boolean unlocked) {
        public Marker {
            if (!validId(id) || name == null || name.isBlank() || name.length() > MAX_NAME || !finite(x, y, z) || !Float.isFinite(radius) || radius <= 0 || radius > 64)
                throw new IllegalArgumentException("travel marker");
        }
    }
    public record Markers(String world, String cache, List<Marker> markers) implements Frame {
        public Markers {
            if (world == null || !(world.isEmpty() || validWorld(world)) || !text(cache, MAX_TEXTURE) || markers == null || markers.size() > MAX_POINTS) throw new IllegalArgumentException("travel markers");
            markers = List.copyOf(markers);
        }
    }

    public sealed interface Frame permits State, Result, Markers {}
    public record State(int kind, Position player, List<Tab> tabs, List<Point> points, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || player == null || tabs == null || tabs.isEmpty() || tabs.size() > MAX_TABS
                    || points == null || points.size() > MAX_POINTS || !text(notice, MAX_TEXT))
                throw new IllegalArgumentException("travel state");
            tabs = List.copyOf(tabs); points = List.copyOf(points);
            var ids = new HashSet<String>();
            for (Tab t : tabs) if (!ids.add(t.id())) throw new IllegalArgumentException("travel state duplicate tab");
            var pointIds = new HashSet<String>();
            for (Point p : points) if (!(p.tab().isEmpty() || ids.contains(p.tab())) || !pointIds.add(p.id())) throw new IllegalArgumentException("travel state point tab");
        }
    }
    public record Result(long operation, int stage, String notice) implements Frame {
        public Result { if (stage < STAGE_ACCEPTED || stage > STAGE_FAILED || !text(notice, MAX_TEXT)) throw new IllegalArgumentException("travel result"); }
    }

    // ------------------------------------------------------------------ codec

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action());
            if (r.action() == TRAVEL) { d.writeUTF(r.pointId()); d.writeLong(r.operation()); }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            return action == TRAVEL ? Request.travel(d.readUTF(), d.readLong()) : Request.of(action);
        });
    }

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind());
                    var p = s.player(); d.writeUTF(p.tab()); d.writeDouble(p.x()); d.writeDouble(p.z()); d.writeFloat(p.yaw());
                    d.writeShort(s.tabs().size());
                    for (Tab t : s.tabs()) {
                        d.writeUTF(t.id()); d.writeUTF(t.label()); d.writeUTF(t.server()); d.writeUTF(t.world()); d.writeByte(t.map());
                        d.writeUTF(t.texture()); d.writeShort(t.imageW()); d.writeShort(t.imageH());
                        for (double v : new double[]{t.tlX(), t.tlZ(), t.trX(), t.trZ(), t.blX(), t.blZ()}) d.writeDouble(v);
                        d.writeUTF(t.cacheKey());
                        for (double v : new double[]{t.minX(), t.minZ(), t.maxX(), t.maxZ()}) d.writeDouble(v);
                        d.writeBoolean(t.online()); d.writeUTF(t.status());
                    }
                    d.writeShort(s.points().size());
                    for (Point q : s.points()) {
                        d.writeUTF(q.id()); d.writeUTF(q.name()); d.writeUTF(q.tab()); d.writeByte(q.kind()); d.writeByte(q.state());
                        d.writeDouble(q.x()); d.writeDouble(q.y()); d.writeDouble(q.z()); d.writeUTF(q.reason());
                    }
                    d.writeUTF(s.notice());
                }
                case Result r -> { d.writeByte(FRAME_RESULT); d.writeLong(r.operation()); d.writeByte(r.stage()); d.writeUTF(r.notice()); }
                case Markers m -> {
                    d.writeByte(FRAME_MARKERS); d.writeUTF(m.world()); d.writeUTF(m.cache()); d.writeShort(m.markers().size());
                    for (Marker k : m.markers()) { d.writeUTF(k.id()); d.writeUTF(k.name()); d.writeDouble(k.x()); d.writeDouble(k.y()); d.writeDouble(k.z()); d.writeFloat(k.radius()); d.writeBoolean(k.unlocked()); }
                }
            }
        });
    }
    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte();
                var player = new Position(d.readUTF(), d.readDouble(), d.readDouble(), d.readFloat());
                int n = d.readUnsignedShort(); if (n < 1 || n > MAX_TABS) throw new IOException("tabs");
                var tabs = new ArrayList<Tab>();
                for (int i = 0; i < n; i++) {
                    String id = d.readUTF(), label = d.readUTF(), server = d.readUTF(), world = d.readUTF(); int map = d.readUnsignedByte();
                    String texture = d.readUTF(); int w = d.readUnsignedShort(), h = d.readUnsignedShort();
                    double[] c = new double[6]; for (int k = 0; k < 6; k++) c[k] = d.readDouble();
                    String cache = d.readUTF();
                    double[] b = new double[4]; for (int k = 0; k < 4; k++) b[k] = d.readDouble();
                    tabs.add(new Tab(id, label, server, world, map, texture, w, h, c[0], c[1], c[2], c[3], c[4], c[5], cache, b[0], b[1], b[2], b[3], d.readBoolean(), d.readUTF()));
                }
                int m = d.readUnsignedShort(); if (m > MAX_POINTS) throw new IOException("points");
                var points = new ArrayList<Point>();
                for (int i = 0; i < m; i++)
                    points.add(new Point(d.readUTF(), d.readUTF(), d.readUTF(), d.readUnsignedByte(), d.readUnsignedByte(), d.readDouble(), d.readDouble(), d.readDouble(), d.readUTF()));
                return new State(kind, player, tabs, points, d.readUTF());
            }
            if (type == FRAME_RESULT) return new Result(d.readLong(), d.readUnsignedByte(), d.readUTF());
            if (type == FRAME_MARKERS) {
                String world = d.readUTF(), cache = d.readUTF(); int n = d.readUnsignedShort(); if (n > MAX_POINTS) throw new IOException("markers");
                var list = new ArrayList<Marker>();
                for (int i = 0; i < n; i++) list.add(new Marker(d.readUTF(), d.readUTF(), d.readDouble(), d.readDouble(), d.readDouble(), d.readFloat(), d.readBoolean()));
                return new Markers(world, cache, list);
            }
            throw new IOException("frame " + type);
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid travel packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid travel packet", e); }
    }

    private TravelProtocol() {}
}
