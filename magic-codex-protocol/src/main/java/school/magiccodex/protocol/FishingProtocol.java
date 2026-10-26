package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 낚시 (2026-10-20). The server owns everything that matters: the water measurement, which fish bites, its size,
 * the gear (stored in the held rod's PDC) and every reward / durability change. The client draws the panel, plays the
 * minigame from the parameters the server sent and reports how it ended; the server checks the timing.
 *
 * C2S request: int version, byte action, long session, then
 *   STOP       float depth                       -- the bobber was stopped at this depth (m)
 *   RESULT     int token, byte outcome, int elapsedMs
 *   RECAST / CANCEL / GEAR_OPEN / CODEX_OPEN     -- nothing
 *   GEAR_EQUIP byte kind, int inventorySlot
 *   CODEX_CLAIM byte tier (0..3 = 25/50/75/100%)
 * S2C frames: SESSION, BITE, RESULT, CLOSE, GEAR, CODEX (see the records).
 */
public final class FishingProtocol {
    public static final String REQUEST = "magiccodex:fishing_request", RESPONSE = "magiccodex:fishing_response";
    public static final int VERSION = 3;   // 2026-10-20 v2: floor depth, tank count, hook gauge, strain, attacks, codex tier rewards
                                           // 2026-10-21 v3: BITE no longer carries rush (the legendary flag); the client derives the needle rush from the zone width
    public static final int MAX_REQUEST_BYTES = 256, MAX_BYTES = 262144;

    // C2S actions
    public static final int STOP = 0, RESULT = 1, RECAST = 2, CANCEL = 3, GEAR_OPEN = 4, GEAR_EQUIP = 5, CODEX_OPEN = 6, CODEX_CLAIM = 7;
    // S2C frames
    public static final int FRAME_SESSION = 1, FRAME_BITE = 2, FRAME_RESULT = 3, FRAME_CLOSE = 4, FRAME_GEAR = 5, FRAME_CODEX = 6;
    // session phases
    public static final int PHASE_READY = 0, PHASE_WATER = 1;
    // outcomes (client reports CAUGHT..ESCAPED; the server turns a caught chest into TREASURE)
    public static final int CAUGHT = 0, SPOOKED = 1, MISSED = 2, SNAPPED = 3, ESCAPED = 4, TREASURE = 5, REFUSED = 6;
    // gear kinds
    public static final int ROD = 0, FLOAT = 1, LINE = 2, BAIT = 3;
    public static final String[] KIND_LABELS = {"낚싯대", "찌", "낚싯줄", "미끼"};
    public static final String[] KIND_FOLDERS = {"rods", "floats", "lines", "baits"};
    // grades 0..3 = 일반 고급 유일 전설
    public static final String[] GRADE_LABELS = {"일반", "고급", "유일", "전설"};
    // backgrounds
    public static final int BG_FRESH = 0, BG_OCEAN = 1, BG_ICE = 2, BG_CAVE = 3;
    // silhouettes
    public static final int SHAPE_FISH = 0, SHAPE_SHARK = 1, SHAPE_EEL = 2, SHAPE_RAY = 3, SHAPE_SQUID = 4, SHAPE_CHEST = 5;
    // struggle patterns
    public static final String[] PATTERNS = {"평온", "몸부림", "돌진", "잠수", "지그재그", "역류", "순간이동", "분신"};
    public static final int CALM = 0, THRASH = 1, DASH = 2, DIVE = 3, ZIGZAG = 4, SURGE = 5, TELEPORT = 6, DECOY = 7;

    public static final int MAX_ID = 64, MAX_NAME = 48, MAX_TEXT = 200, MAX_NOTICE = 160, MAX_OWNED = 108, MAX_FISH = 400,
            MAX_REWARDS = 16, MAX_PREVIEW = 2048, MAX_SPECIAL = 64;
    public static final float MAX_DEPTH = 999;
    public static final int MAX_ATTACKS = 8;
    // fish attacks during the reel (extra wear): bite the line / shake the rod
    public static final int ATTACK_LINE = 0, ATTACK_ROD = 1;

    // ------------------------------------------------------------------ records

    /** RESULT v2: heldMask = bit i set when the player was winding during attack i (that attack wears double; it can only add wear). */
    public record Request(int action, long session, float depth, int token, int outcome, int elapsedMs, int kind, int slot, int tier, int heldMask) {
        public Request {
            if (action < STOP || action > CODEX_CLAIM || !Float.isFinite(depth) || depth < 0 || depth > MAX_DEPTH
                    || outcome < 0 || outcome > ESCAPED || elapsedMs < 0 || kind < ROD || kind > BAIT || slot < 0 || slot > 1000
                    || tier < 0 || tier > 3 || heldMask < 0 || heldMask > 255) throw new IllegalArgumentException("fishing request");
        }
        public static Request of(int action, long session) { return new Request(action, session, 0, 0, 0, 0, 0, 0, 0, 0); }
        public static Request stop(long session, float depth) { return new Request(STOP, session, depth, 0, 0, 0, 0, 0, 0, 0); }
        public static Request result(long session, int token, int outcome, int elapsed, int heldMask) { return new Request(RESULT, session, 0, token, outcome, elapsed, 0, 0, 0, heldMask); }
        public static Request equip(int kind, int slot) { return new Request(GEAR_EQUIP, 0, 0, 0, 0, 0, kind, slot, 0, 0); }
        public static Request claim(int tier) { return new Request(CODEX_CLAIM, 0, 0, 0, 0, 0, 0, 0, tier, 0); }
    }

    /** One equipped piece. count = bait amount (-1 = endless default), durability/max = -1 when it has none. */
    public record Piece(int kind, String id, String name, int grade, int durability, int maxDurability, int count, boolean fallback, String special) {
        public Piece {
            if (kind < ROD || kind > BAIT || !validId(id) || name == null || name.length() > MAX_NAME || grade < 0 || grade > 3
                    || special == null || special.length() > MAX_SPECIAL) throw new IllegalArgumentException("fishing piece");
        }
    }
    /** The equipped set with its combined numbers. */
    public record Gear(List<Piece> pieces, float reelPower, float hookWindow, float sinkSpeed, float sensitivity, float lineLength, float maxTension) {
        public Gear {
            if (pieces == null || pieces.size() != 4) throw new IllegalArgumentException("fishing gear");
            pieces = List.copyOf(pieces);
        }
        public Piece piece(int kind) { return pieces.get(kind); }
    }
    public sealed interface Frame permits Session, Bite, Result, Close, GearList, Codex {}
    /**
     * maxDepth = how deep the bobber can sink here (line length and the water's real depth). floorDepth = the water's
     * real bottom (m); MAX_DEPTH = deeper than anything measurable. The bobber stopped by the line (floorDepth > maxDepth)
     * hangs in open water: no sea floor is drawn. tankCount / tankCapacity = the 아공간 수조 tank, shown next to the title.
     */
    public record Session(long session, int phase, int background, float maxDepth, float floorDepth, int waterClass, int waterBlocks,
                          int tankCount, int tankCapacity, Gear gear, String notice) implements Frame {
        public Session {
            if (phase < PHASE_READY || phase > PHASE_WATER || background < 0 || background > 3 || !Float.isFinite(maxDepth) || maxDepth < 0
                    || maxDepth > MAX_DEPTH || !Float.isFinite(floorDepth) || floorDepth < 0 || floorDepth > MAX_DEPTH
                    || waterClass < 0 || waterClass > 3 || tankCount < 0 || tankCapacity < 0 || gear == null || !okNotice(notice)) throw new IllegalArgumentException("fishing session");
        }
        public boolean bottomless() { return floorDepth > maxDepth + .5f; }
    }
    /** A fish attack during the reel at atMs after the hook: kind ATTACK_LINE / ATTACK_ROD, damage = durability lost (x2 when winding). */
    public record Attack(int atMs, int kind, int damage) {
        public Attack { if (atMs < 0 || atMs > 600000 || kind < ATTACK_LINE || kind > ATTACK_ROD || damage < 0 || damage > 1000) throw new IllegalArgumentException("fishing attack"); }
    }
    /**
     * What will bite. waitMs = until it starts coming, teases = fake nibbles before the real one, hookWindowMs = the
     * reaction window, size = 0..1 within the silhouette range, grade = -1 unless the float reveals it, magic = a magic fish
     * (the mana-amplifier rod reels it faster).
     * v2: sweepMs = how long the hook gauge needle takes across the bar, zone = width of the success zone (0..1); strain = how hard this fish is for this gear at this depth (1 = normal,
     * higher = stronger pull / lower tension room: low-grade gear deep down); attacks = what the fish does to the gear
     * while it is reeled (the server charges the wear of the attacks that happened before the reported end).
     * v3: no rush flag on the wire (it told a modded client "legendary" before the catch); see {@link #rushes(float)}.
     */
    public record Bite(long session, int token, int shape, float size, int grade, int waitMs, int teases, int hookWindowMs,
                       int power, int pattern, long seed, float depth, boolean magic, int sweepMs, float zone, float strain,
                       List<Attack> attacks) implements Frame {
        public Bite {
            if (shape < 0 || shape > SHAPE_CHEST || !Float.isFinite(size) || size < 0 || size > 1 || grade < -1 || grade > 3 || waitMs < 0 || waitMs > 60000
                    || teases < 0 || teases > 8 || hookWindowMs < 100 || hookWindowMs > 5000 || power < 1 || power > 10 || pattern < 0
                    || pattern >= PATTERNS.length || !Float.isFinite(depth) || depth < 0 || depth > MAX_DEPTH || sweepMs < 200 || sweepMs > 20000
                    || !Float.isFinite(zone) || zone < .03f || zone > .6f || !Float.isFinite(strain) || strain < .5f || strain > 5
                    || attacks == null || attacks.size() > MAX_ATTACKS) throw new IllegalArgumentException("fishing bite");
            attacks = List.copyOf(attacks);
        }
    }
    public record Reward(String name, int amount, byte[] preview) {
        public Reward {
            if (name == null || name.length() > MAX_NAME || amount < 0 || preview == null || preview.length > MAX_PREVIEW) throw new IllegalArgumentException("fishing reward");
        }
    }
    /**
     * fishId/name are "" unless something was caught. grade = fish grade or chest grade. Reward.preview is the serialized
     * item: the client shows its own localized name and the normal item tooltip from it (name is only a fallback).
     */
    public record Result(long session, int outcome, String fishId, String name, int grade, int shape, float sizeCm, boolean record, boolean newEntry,
                         List<Reward> rewards, boolean mailed, Gear gear, int tankCount, int tankCapacity, String notice) implements Frame {
        public Result {
            if (outcome < CAUGHT || outcome > REFUSED || fishId == null || (!fishId.isEmpty() && !validId(fishId)) || name == null || name.length() > MAX_NAME
                    || grade < 0 || grade > 3 || shape < 0 || shape > SHAPE_CHEST || !Float.isFinite(sizeCm) || sizeCm < 0 || rewards == null
                    || rewards.size() > MAX_REWARDS || gear == null || tankCount < 0 || tankCapacity < 0 || !okNotice(notice)) throw new IllegalArgumentException("fishing result");
            rewards = List.copyOf(rewards);
        }
    }
    public record Close(long session, String notice) implements Frame {
        public Close { if (!okNotice(notice)) throw new IllegalArgumentException("fishing close"); }
    }
    /** A gear item in the player's inventory (slot = Bukkit inventory index). */
    public record Owned(int kind, int slot, String id, String name, int grade, int durability, int maxDurability, int count, String stats, String special) {
        public Owned {
            if (kind < ROD || kind > BAIT || slot < 0 || slot > 1000 || !validId(id) || name == null || name.length() > MAX_NAME || grade < 0 || grade > 3
                    || stats == null || stats.length() > MAX_TEXT || special == null || special.length() > MAX_TEXT) throw new IllegalArgumentException("fishing owned");
        }
    }
    public record GearList(Gear gear, boolean locked, List<Owned> owned, List<String> stats, String notice) implements Frame {
        public GearList {
            if (gear == null || owned == null || owned.size() > MAX_OWNED || stats == null || stats.size() != 4 || !okNotice(notice))
                throw new IllegalArgumentException("fishing gear list");
            owned = List.copyOf(owned); stats = List.copyOf(stats);
        }
    }
    /** One codex row. For uncaught fish the server sends name "???" and hides part of the conditions. */
    public record Entry(String id, String name, int grade, int shape, boolean caught, float best, int count, String description,
                        String season, String place, String depth, String time) {
        public Entry {
            if (!validId(id) || name == null || name.length() > MAX_NAME || grade < 0 || grade > 3 || shape < 0 || shape > SHAPE_CHEST
                    || !Float.isFinite(best) || best < 0 || count < 0 || !okText(description) || !okText(season) || !okText(place) || !okText(depth) || !okText(time))
                throw new IllegalArgumentException("fishing entry " + id);
        }
    }
    /**
     * treasures[g] = chests of grade g opened; claimed/available = bit masks over the 4 completion tiers.
     * tierRewards[t] = what tier t gives (items: serialized preview; command rewards: name = the yml display text, empty preview).
     */
    public record Codex(List<Entry> entries, int[] treasures, int claimed, int available, List<List<Reward>> tierRewards, String notice) implements Frame {
        public Codex {
            if (entries == null || entries.size() > MAX_FISH || treasures == null || treasures.length != 4 || claimed < 0 || claimed > 15
                    || available < 0 || available > 15 || tierRewards == null || tierRewards.size() != 4 || !okNotice(notice)) throw new IllegalArgumentException("fishing codex");
            for (var l : tierRewards) if (l == null || l.size() > MAX_REWARDS) throw new IllegalArgumentException("fishing codex rewards");
            entries = List.copyOf(entries); treasures = treasures.clone(); tierRewards = tierRewards.stream().map(List::copyOf).toList();
        }
        public int caught() { int n = 0; for (Entry e : entries) if (e.caught()) n++; return n; }
    }

    public static boolean validId(String id) { return id != null && !id.isEmpty() && id.length() <= MAX_ID && id.matches("[a-z0-9_]+"); }
    private static boolean okNotice(String s) { return s != null && s.length() <= MAX_NOTICE; }
    private static boolean okText(String s) { return s != null && s.length() <= MAX_TEXT; }
    public static int gradeIndex(String label) {
        for (int i = 0; i < GRADE_LABELS.length; i++) if (GRADE_LABELS[i].equals(label)) return i;
        return -1;
    }
    public static int patternIndex(String label) {
        for (int i = 0; i < PATTERNS.length; i++) if (PATTERNS[i].equals(label)) return i;
        return -1;
    }
    /** Reeling speed in m/s at full winding (both sides use this so the server can check the elapsed time). */
    public static float reelSpeed(float reelPower) { return Math.max(.5f, reelPower * .35f); }
    /** Hook gauge zones narrower than this make the needle rush near the zone (the narrowest, legendary zone .12). v3: derived on the client, not sent. */
    public static final float RUSH_ZONE = .135f;
    /** Fastest winding the client allows, as a share of reelSpeed (tired fish: .75 x 1.3). The server's minimum assumes it. */
    public static final double MAX_WIND = .975;
    public static boolean rushes(float zone) { return zone < RUSH_ZONE; }
    /**
     * v3 (2026-10-21): the fastest an honest catch can end after the bite frame arrived (ms). Mirrors the client's shortest
     * possible timings: approach (1.25 s, none for a chest) + .5 s + 1.25 s per tease + .25 s warning + .2 s sensitivity +
     * the needle reaching the earliest zone (.25 of the sweep), then winding non-stop at the client's top winding speed
     * (.975 x reelSpeed: .75 x 1.3 while the fish is tired, client FishMood 2026-10-21) against the weakest pull this fish
     * can have (power, strain, its pattern's lowest pull capped at the tired .6, stamina 0). reelBonus = the rod's best reel
     * multiplier (sea / magic rod effects). The server measures from sending, so the round trip only adds.
     * Margin: the result is (that time x 0.9) - 400 ms, i.e. 10% + 400 ms below the fastest possible honest catch.
     */
    public static int minimumCatchMillis(Bite b, float reelPower, float reelBonus) {
        boolean chest = b.shape() == SHAPE_CHEST;
        double before = b.waitMs() + (chest ? 0 : 1250 + b.teases() * 1250) + 500 + 250 + 200 + .25 * b.sweepMs();
        double low = Math.min(.6, switch (b.pattern()) { case ZIGZAG -> .75; case DASH -> .9; case DIVE -> .95; case SURGE -> .35; default -> 1; });
        double force = (.20 + .075 * b.power()) * b.strain() * .4 * low;
        double speed = reelSpeed(reelPower) * Math.max(1, reelBonus) * MAX_WIND * Math.max(.15, 1 - .8 * force);
        return (int) Math.max(0, (before + b.depth() / speed * 1000) * .9 - 400);
    }
    /** Completion tier reached: 0..4 tiers at 25/50/75/100%. */
    public static int tiersReached(int caught, int total) {
        if (total <= 0) return 0;
        int n = 0;
        for (int t = 1; t <= 4; t++) if (caught * 4 >= total * t) n = t;
        return n;
    }

    // ------------------------------------------------------------------ request

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.session());
            switch (r.action()) {
                case STOP -> d.writeFloat(r.depth());
                case RESULT -> { d.writeInt(r.token()); d.writeByte(r.outcome()); d.writeInt(r.elapsedMs()); d.writeByte(r.heldMask()); }
                case GEAR_EQUIP -> { d.writeByte(r.kind()); d.writeInt(r.slot()); }
                case CODEX_CLAIM -> d.writeByte(r.tier());
                default -> {}
            }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte(); long session = d.readLong();
            return switch (action) {
                case STOP -> Request.stop(session, d.readFloat());
                case RESULT -> { int token = d.readInt(); int outcome = d.readUnsignedByte(); int elapsed = d.readInt(); yield Request.result(session, token, outcome, elapsed, d.readUnsignedByte()); }
                case GEAR_EQUIP -> { int kind = d.readUnsignedByte(); yield new Request(GEAR_EQUIP, session, 0, 0, 0, 0, kind, d.readInt(), 0, 0); }
                case CODEX_CLAIM -> new Request(CODEX_CLAIM, session, 0, 0, 0, 0, 0, 0, d.readUnsignedByte(), 0);
                default -> Request.of(action, session);
            };
        });
    }

    // ------------------------------------------------------------------ frames

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case Session s -> {
                    d.writeByte(FRAME_SESSION); d.writeLong(s.session()); d.writeByte(s.phase()); d.writeByte(s.background());
                    d.writeFloat(s.maxDepth()); d.writeFloat(s.floorDepth()); d.writeByte(s.waterClass()); d.writeInt(s.waterBlocks());
                    d.writeInt(s.tankCount()); d.writeInt(s.tankCapacity()); gear(d, s.gear()); d.writeUTF(s.notice());
                }
                case Bite b -> {
                    d.writeByte(FRAME_BITE); d.writeLong(b.session()); d.writeInt(b.token()); d.writeByte(b.shape()); d.writeFloat(b.size());
                    d.writeByte(b.grade() + 1); d.writeInt(b.waitMs()); d.writeByte(b.teases()); d.writeInt(b.hookWindowMs()); d.writeByte(b.power());
                    d.writeByte(b.pattern()); d.writeLong(b.seed()); d.writeFloat(b.depth()); d.writeBoolean(b.magic());
                    d.writeInt(b.sweepMs()); d.writeFloat(b.zone()); d.writeFloat(b.strain());
                    d.writeByte(b.attacks().size()); for (Attack a : b.attacks()) { d.writeInt(a.atMs()); d.writeByte(a.kind()); d.writeShort(a.damage()); }
                }
                case Result r -> {
                    d.writeByte(FRAME_RESULT); d.writeLong(r.session()); d.writeByte(r.outcome()); d.writeUTF(r.fishId()); d.writeUTF(r.name());
                    d.writeByte(r.grade()); d.writeByte(r.shape()); d.writeFloat(r.sizeCm()); d.writeBoolean(r.record()); d.writeBoolean(r.newEntry());
                    d.writeByte(r.rewards().size());
                    for (Reward w : r.rewards()) reward(d, w);
                    d.writeBoolean(r.mailed()); gear(d, r.gear()); d.writeInt(r.tankCount()); d.writeInt(r.tankCapacity()); d.writeUTF(r.notice());
                }
                case Close c -> { d.writeByte(FRAME_CLOSE); d.writeLong(c.session()); d.writeUTF(c.notice()); }
                case GearList g -> {
                    d.writeByte(FRAME_GEAR); gear(d, g.gear()); d.writeBoolean(g.locked()); d.writeShort(g.owned().size());
                    for (Owned o : g.owned()) {
                        d.writeByte(o.kind()); d.writeShort(o.slot()); d.writeUTF(o.id()); d.writeUTF(o.name()); d.writeByte(o.grade());
                        d.writeInt(o.durability()); d.writeInt(o.maxDurability()); d.writeInt(o.count()); d.writeUTF(o.stats()); d.writeUTF(o.special());
                    }
                    for (String s : g.stats()) d.writeUTF(s);
                    d.writeUTF(g.notice());
                }
                case Codex c -> {
                    d.writeByte(FRAME_CODEX); d.writeShort(c.entries().size());
                    for (Entry e : c.entries()) {
                        d.writeUTF(e.id()); d.writeUTF(e.name()); d.writeByte(e.grade()); d.writeByte(e.shape()); d.writeBoolean(e.caught());
                        d.writeFloat(e.best()); d.writeInt(e.count()); d.writeUTF(e.description()); d.writeUTF(e.season()); d.writeUTF(e.place());
                        d.writeUTF(e.depth()); d.writeUTF(e.time());
                    }
                    for (int t : c.treasures()) d.writeInt(t);
                    d.writeByte(c.claimed()); d.writeByte(c.available());
                    for (var l : c.tierRewards()) { d.writeByte(l.size()); for (Reward w : l) reward(d, w); }
                    d.writeUTF(c.notice());
                }
            }
        });
    }

    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            return switch (type) {
                case FRAME_SESSION -> new Session(d.readLong(), d.readUnsignedByte(), d.readUnsignedByte(), d.readFloat(), d.readFloat(), d.readUnsignedByte(),
                        d.readInt(), d.readInt(), d.readInt(), gear(d), d.readUTF());
                case FRAME_BITE -> {
                    long session = d.readLong(); int token = d.readInt(), shape = d.readUnsignedByte(); float size = d.readFloat();
                    int grade = d.readUnsignedByte() - 1, wait = d.readInt(), teases = d.readUnsignedByte(), window = d.readInt(), power = d.readUnsignedByte(),
                            pattern = d.readUnsignedByte();
                    long seed = d.readLong(); float depth = d.readFloat(); boolean magic = d.readBoolean();
                    int sweep = d.readInt(); float zone = d.readFloat(); float strain = d.readFloat();
                    int n = d.readUnsignedByte(); if (n > MAX_ATTACKS) throw new IOException("attacks");
                    var attacks = new ArrayList<Attack>();
                    for (int i = 0; i < n; i++) attacks.add(new Attack(d.readInt(), d.readUnsignedByte(), d.readUnsignedShort()));
                    yield new Bite(session, token, shape, size, grade, wait, teases, window, power, pattern, seed, depth, magic, sweep, zone, strain, attacks);
                }
                case FRAME_RESULT -> {
                    long session = d.readLong(); int outcome = d.readUnsignedByte(); String id = d.readUTF(), name = d.readUTF();
                    int grade = d.readUnsignedByte(), shape = d.readUnsignedByte(); float size = d.readFloat();
                    boolean record = d.readBoolean(), fresh = d.readBoolean();
                    int n = d.readUnsignedByte(); if (n > MAX_REWARDS) throw new IOException("rewards");
                    var rewards = new ArrayList<Reward>();
                    for (int i = 0; i < n; i++) rewards.add(reward(d));
                    boolean mailed = d.readBoolean(); Gear g = gear(d); int tank = d.readInt(), capacity = d.readInt();
                    yield new Result(session, outcome, id, name, grade, shape, size, record, fresh, rewards, mailed, g, tank, capacity, d.readUTF());
                }
                case FRAME_CLOSE -> new Close(d.readLong(), d.readUTF());
                case FRAME_GEAR -> {
                    Gear g = gear(d); boolean locked = d.readBoolean(); int n = d.readUnsignedShort();
                    if (n > MAX_OWNED) throw new IOException("owned");
                    var owned = new ArrayList<Owned>();
                    for (int i = 0; i < n; i++) owned.add(new Owned(d.readUnsignedByte(), d.readUnsignedShort(), d.readUTF(), d.readUTF(), d.readUnsignedByte(),
                            d.readInt(), d.readInt(), d.readInt(), d.readUTF(), d.readUTF()));
                    var stats = new ArrayList<String>();
                    for (int i = 0; i < 4; i++) stats.add(d.readUTF());
                    yield new GearList(g, locked, owned, stats, d.readUTF());
                }
                case FRAME_CODEX -> {
                    int n = d.readUnsignedShort(); if (n > MAX_FISH) throw new IOException("fish");
                    var entries = new ArrayList<Entry>();
                    for (int i = 0; i < n; i++) entries.add(new Entry(d.readUTF(), d.readUTF(), d.readUnsignedByte(), d.readUnsignedByte(), d.readBoolean(),
                            d.readFloat(), d.readInt(), d.readUTF(), d.readUTF(), d.readUTF(), d.readUTF(), d.readUTF()));
                    int[] t = new int[4]; for (int i = 0; i < 4; i++) t[i] = d.readInt();
                    int claimed = d.readUnsignedByte(), available = d.readUnsignedByte();
                    var tiers = new ArrayList<List<Reward>>();
                    for (int k = 0; k < 4; k++) {
                        int m = d.readUnsignedByte(); if (m > MAX_REWARDS) throw new IOException("tier rewards");
                        var l = new ArrayList<Reward>(); for (int i = 0; i < m; i++) l.add(reward(d)); tiers.add(l);
                    }
                    yield new Codex(entries, t, claimed, available, tiers, d.readUTF());
                }
                default -> throw new IOException("frame " + type);
            };
        });
    }

    private static void reward(DataOutputStream d, Reward w) throws IOException { d.writeUTF(w.name()); d.writeInt(w.amount()); d.writeShort(w.preview().length); d.write(w.preview()); }
    private static Reward reward(DataInputStream d) throws IOException {
        String rn = d.readUTF(); int amount = d.readInt(); int len = d.readUnsignedShort();
        if (len > MAX_PREVIEW) throw new IOException("preview");
        byte[] p = new byte[len]; d.readFully(p); return new Reward(rn, amount, p);
    }
    private static void gear(DataOutputStream d, Gear g) throws IOException {
        for (Piece p : g.pieces()) {
            d.writeByte(p.kind()); d.writeUTF(p.id()); d.writeUTF(p.name()); d.writeByte(p.grade()); d.writeInt(p.durability());
            d.writeInt(p.maxDurability()); d.writeInt(p.count()); d.writeBoolean(p.fallback()); d.writeUTF(p.special());
        }
        d.writeFloat(g.reelPower()); d.writeFloat(g.hookWindow()); d.writeFloat(g.sinkSpeed()); d.writeFloat(g.sensitivity());
        d.writeFloat(g.lineLength()); d.writeFloat(g.maxTension());
    }
    private static Gear gear(DataInputStream d) throws IOException {
        var pieces = new ArrayList<Piece>();
        for (int i = 0; i < 4; i++) pieces.add(new Piece(d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readUnsignedByte(), d.readInt(), d.readInt(),
                d.readInt(), d.readBoolean(), d.readUTF()));
        return new Gear(pieces, d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat());
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fishing packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fishing packet", e); }
    }

    private FishingProtocol() {}
}
