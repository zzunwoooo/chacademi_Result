package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * Artifact codex (아티팩트 도감). The server owns the catalogue (plugins/MagicCodexBridge/artifacts.yml), ownership and
 * the equipped list; the client only draws them. Only ids cross the trust boundary from the client.
 *
 * C2S request: int version, byte action (QUERY / EQUIP / UNEQUIP / USE), long knownCatalog, String id.
 * S2C response frames (each <= MAX_BYTES, so a 200-entry catalogue is split into parts):
 *   CATALOG: long catalogRevision, short part, short parts, entries... (v2: each entry ends with int cooldown)
 *   STATE:   long catalogRevision, byte maxEquipped, owned bitset (catalogue order), equipped ids, notice
 *            (v2: then one int per equipped id = remaining use cooldown in ms)
 * The client rebuilds the catalogue only when every part of one revision arrived, then applies STATE.
 *
 * Summon (2026-10-15): C2S SUMMON_INFO (key count) / SUMMON (spend one key, roll); S2C SUMMON frame
 *   byte kind (OPEN/INFO/RESULT/REFUSED), int keys, String artifactId, byte grade, bool isNew, short shards,
 *   4 decoy grades, String notice. The server decides everything; decoys only colour the unpicked chests.
 * Five-pull (2026-10-15): C2S SUMMON_MULTI (spend MULTI x cost keys, roll MULTI times); S2C SUMMON_MULTI_FRAME
 *   int keys, byte n, n x (String artifactId, byte grade, bool isNew, short shards), String notice.
 *   A refused five-pull answers with the normal SUMMON frame (kind REFUSED).
 *
 * Version 2 (2026-10-15, artifact use key): writers always send 2, readers accept 1 and 2 (v1 = no use data).
 * A v1 client cannot read v2 frames, so the mod and MagicCodexBridge must be deployed together.
 */
public final class ArtifactProtocol {
    public static final String REQUEST = "magiccodex:artifact_request", RESPONSE = "magiccodex:artifact_response";
    public static final int MAX_BYTES = 30000, MAX_ARTIFACTS = 1024, MAX_EQUIPPED = 8, MAX_PARTS = 256;
    public static final int VERSION = 2;
    public static final int QUERY = 0, EQUIP = 1, UNEQUIP = 2, USE = 3, SUMMON_INFO = 4, SUMMON = 5, SUMMON_MULTI = 6;
    /** Pulls in one five-pull. */
    public static final int MULTI = 5;
    /** Summon frame kinds. OPEN = the server asks the client to open the summon window (command). */
    public static final int SUMMON_OPEN = 0, SUMMON_STATE = 1, SUMMON_RESULT = 2, SUMMON_REFUSED = 3, DECOYS = 4;
    /** Entry.cooldown for passive artifacts (no use key). Usable ones carry their cooldown in seconds (0 = none). */
    public static final int PASSIVE = -1, MAX_COOLDOWN = 86400;
    public static final int CATALOG = 1, STATE = 2, SUMMON_FRAME = 3, SUMMON_MULTI_FRAME = 4;
    public static final int MAX_ID = 64, MAX_NAME = 40, MAX_DESCRIPTION = 200, MAX_EFFECT = 600, MAX_ICON = 200, MAX_NOTICE = 120;
    /** 0 일반, 1 고급, 2 유일, 3 전설. */
    public static final String[] GRADE_LABELS = {"일반", "고급", "유일", "전설"};
    public static final String[] GRADE_KEYS = {"common", "rare", "unique", "legendary"};

    public record Request(int action, long knownCatalog, String id) {
        public Request {
            if (action < QUERY || action > SUMMON_MULTI || id == null || id.length() > MAX_ID
                    || ((action == EQUIP || action == UNEQUIP || action == USE) && !validId(id)))
                throw new IllegalArgumentException("artifact request");
        }
    }
    public record Entry(String id, String name, int grade, String description, String effect, String icon, int cooldown) {
        public Entry {
            if (!validId(id) || name == null || name.isBlank() || name.length() > MAX_NAME || grade < 0 || grade > 3
                    || description == null || description.length() > MAX_DESCRIPTION || effect == null || effect.length() > MAX_EFFECT
                    || icon == null || icon.length() > MAX_ICON || cooldown < PASSIVE || cooldown > MAX_COOLDOWN)
                throw new IllegalArgumentException("artifact entry " + id);
        }
        public Entry(String id, String name, int grade, String description, String effect, String icon) {
            this(id, name, grade, description, effect, icon, PASSIVE);
        }
        public boolean usable() { return cooldown >= 0; }
    }
    public sealed interface Frame permits Catalog, State, Summon, SummonMulti {}
    /** One pull of a five-pull. */
    public record Pull(String artifactId, int grade, boolean isNew, int shards) {
        public Pull {
            if (!validId(artifactId) || grade < 0 || grade > 3 || shards < 0 || shards > 32767) throw new IllegalArgumentException("pull");
        }
    }
    /** Five-pull result (all pulls decided by the server, shown at once). */
    public record SummonMulti(int keys, List<Pull> pulls, String notice) implements Frame {
        public SummonMulti {
            if (keys < 0 || pulls == null || pulls.isEmpty() || pulls.size() > 10 || notice == null || notice.length() > MAX_NOTICE)
                throw new IllegalArgumentException("summon multi");
            pulls = List.copyOf(pulls);
        }
    }
    /** artifactId/grade/isNew/shards/decoys are only meaningful for SUMMON_RESULT (else "", 0, false, 0, zeros). */
    public record Summon(int kind, int keys, String artifactId, int grade, boolean isNew, int shards, int[] decoys, String notice) implements Frame {
        public Summon {
            if (kind < SUMMON_OPEN || kind > SUMMON_REFUSED || keys < 0 || artifactId == null || (!artifactId.isEmpty() && !validId(artifactId))
                    || grade < 0 || grade > 3 || shards < 0 || shards > 32767 || decoys == null || decoys.length != DECOYS
                    || notice == null || notice.length() > MAX_NOTICE) throw new IllegalArgumentException("summon");
            for (int d : decoys) if (d < 0 || d > 3) throw new IllegalArgumentException("decoy");
            decoys = decoys.clone();
        }
        public static Summon simple(int kind, int keys, String notice) { return new Summon(kind, keys, "", 0, false, 0, new int[DECOYS], notice); }
    }
    public record Catalog(long revision, int part, int parts, List<Entry> entries) implements Frame {
        public Catalog { if (parts < 1 || parts > MAX_PARTS || part < 0 || part >= parts) throw new IllegalArgumentException("part"); entries = List.copyOf(entries); }
    }
    /** cooldownMs: remaining use cooldown per equipped id (same order), 0 = ready or passive. */
    public record State(long revision, int maxEquipped, boolean[] owned, List<String> equipped, String notice, int[] cooldownMs) implements Frame {
        public State {
            if (maxEquipped < 1 || maxEquipped > MAX_EQUIPPED || owned.length > MAX_ARTIFACTS || equipped.size() > MAX_EQUIPPED
                    || notice == null || notice.length() > MAX_NOTICE || cooldownMs == null || cooldownMs.length != equipped.size())
                throw new IllegalArgumentException("state");
            for (String id : equipped) if (!validId(id)) throw new IllegalArgumentException("equipped id");
            for (int ms : cooldownMs) if (ms < 0 || ms > MAX_COOLDOWN * 1000) throw new IllegalArgumentException("cooldown");
            equipped = List.copyOf(equipped); cooldownMs = cooldownMs.clone();
        }
        public State(long revision, int maxEquipped, boolean[] owned, List<String> equipped, String notice) {
            this(revision, maxEquipped, owned, equipped, notice, new int[equipped.size()]);
        }
    }

    private ArtifactProtocol() {}

    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_]{1," + MAX_ID + "}"); }

    public static byte[] request(Request r) {
        return write(d -> { d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.knownCatalog()); d.writeUTF(r.id()); });
    }
    public static Request request(byte[] raw) {
        return read(raw, d -> { int v = d.readInt(); if (v != 1 && v != VERSION) throw new IOException("version"); return new Request(d.readUnsignedByte(), d.readLong(), d.readUTF()); });
    }

    /** Splits the catalogue into frames that each stay under MAX_BYTES. */
    public static List<byte[]> catalog(long revision, List<Entry> entries) {
        if (entries.size() > MAX_ARTIFACTS) throw new IllegalArgumentException("too many artifacts");
        var groups = new ArrayList<List<Entry>>();
        var current = new ArrayList<Entry>();
        int size = 0;
        for (Entry e : entries) {
            int cost = 20 + 3 * (e.id().length() + e.name().length() + e.description().length() + e.effect().length() + e.icon().length());
            if (!current.isEmpty() && size + cost > MAX_BYTES - 64) { groups.add(current); current = new ArrayList<>(); size = 0; }
            current.add(e); size += cost;
        }
        groups.add(current);
        if (groups.size() > MAX_PARTS) throw new IllegalArgumentException("catalogue too large");
        var out = new ArrayList<byte[]>();
        for (int i = 0; i < groups.size(); i++) out.add(encode(new Catalog(revision, i, groups.size(), groups.get(i))));
        return out;
    }

    public static byte[] encode(Frame frame) {
        return write(d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case Catalog c -> {
                    d.writeByte(CATALOG); d.writeLong(c.revision()); d.writeShort(c.part()); d.writeShort(c.parts()); d.writeShort(c.entries().size());
                    for (Entry e : c.entries()) { d.writeUTF(e.id()); d.writeUTF(e.name()); d.writeByte(e.grade()); d.writeUTF(e.description()); d.writeUTF(e.effect()); d.writeUTF(e.icon()); d.writeInt(e.cooldown()); }
                }
                case State s -> {
                    d.writeByte(STATE); d.writeLong(s.revision()); d.writeByte(s.maxEquipped());
                    d.writeShort(s.owned().length);
                    byte[] bits = new byte[(s.owned().length + 7) / 8];
                    for (int i = 0; i < s.owned().length; i++) if (s.owned()[i]) bits[i / 8] |= (byte) (1 << (i % 8));
                    d.write(bits);
                    d.writeByte(s.equipped().size()); for (String id : s.equipped()) d.writeUTF(id);
                    d.writeUTF(s.notice());
                    for (int ms : s.cooldownMs()) d.writeInt(ms);
                }
                case Summon m -> {
                    d.writeByte(SUMMON_FRAME); d.writeByte(m.kind()); d.writeInt(m.keys()); d.writeUTF(m.artifactId()); d.writeByte(m.grade());
                    d.writeBoolean(m.isNew()); d.writeShort(m.shards()); for (int g : m.decoys()) d.writeByte(g); d.writeUTF(m.notice());
                }
                case SummonMulti m -> {
                    d.writeByte(SUMMON_MULTI_FRAME); d.writeInt(m.keys()); d.writeByte(m.pulls().size());
                    for (Pull x : m.pulls()) { d.writeUTF(x.artifactId()); d.writeByte(x.grade()); d.writeBoolean(x.isNew()); d.writeShort(x.shards()); }
                    d.writeUTF(m.notice());
                }
            }
        });
    }

    public static Frame decode(byte[] raw) {
        return read(raw, d -> {
            int version = d.readInt();
            if (version != 1 && version != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == CATALOG) {
                long revision = d.readLong(); int part = d.readUnsignedShort(), parts = d.readUnsignedShort(), count = d.readUnsignedShort();
                if (count > MAX_ARTIFACTS) throw new IOException("count");
                var list = new ArrayList<Entry>(count);
                for (int i = 0; i < count; i++) list.add(new Entry(d.readUTF(), d.readUTF(), d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readUTF(),
                        version == 1 ? PASSIVE : d.readInt()));
                return new Catalog(revision, part, parts, list);
            }
            if (type == STATE) {
                long revision = d.readLong(); int max = d.readUnsignedByte(); int n = d.readUnsignedShort();
                if (n > MAX_ARTIFACTS) throw new IOException("owned");
                byte[] bits = d.readNBytes((n + 7) / 8); if (bits.length != (n + 7) / 8) throw new IOException("bits");
                boolean[] owned = new boolean[n]; for (int i = 0; i < n; i++) owned[i] = (bits[i / 8] & (1 << (i % 8))) != 0;
                int e = d.readUnsignedByte(); if (e > MAX_EQUIPPED) throw new IOException("equipped");
                var equipped = new ArrayList<String>(e); for (int i = 0; i < e; i++) equipped.add(d.readUTF());
                String notice = d.readUTF();
                int[] cooldown = new int[e]; if (version >= 2) for (int i = 0; i < e; i++) cooldown[i] = d.readInt();
                return new State(revision, max, owned, equipped, notice, cooldown);
            }
            if (type == SUMMON_FRAME) {
                int kind = d.readUnsignedByte(), keys = d.readInt(); String id = d.readUTF(); int grade = d.readUnsignedByte();
                boolean isNew = d.readBoolean(); int shards = d.readUnsignedShort();
                int[] decoys = new int[DECOYS]; for (int i = 0; i < DECOYS; i++) decoys[i] = d.readUnsignedByte();
                return new Summon(kind, keys, id, grade, isNew, shards, decoys, d.readUTF());
            }
            if (type == SUMMON_MULTI_FRAME) {
                int keys = d.readInt(), n = d.readUnsignedByte();
                if (n < 1 || n > 10) throw new IOException("pulls");
                var pulls = new ArrayList<Pull>(n);
                for (int i = 0; i < n; i++) pulls.add(new Pull(d.readUTF(), d.readUnsignedByte(), d.readBoolean(), d.readUnsignedShort()));
                return new SummonMulti(keys, pulls, d.readUTF());
            }
            throw new IOException("type");
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(Writer w) {
        try { var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b)); if (b.size() > MAX_BYTES) throw new IOException("size"); return b.toByteArray(); }
        catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid artifact packet", e); }
    }
    private static <T> T read(byte[] raw, Reader<T> r) {
        try {
            if (raw == null || raw.length > MAX_BYTES) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(raw)); T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing"); return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid artifact packet", e); }
    }
}
