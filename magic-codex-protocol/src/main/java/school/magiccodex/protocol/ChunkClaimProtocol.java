package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 야생 청크 점유 (땅문서, 메모 #88, 오너 2026-10-26).
 *
 * <p>서버 → 클라 {@value #STATE}: 보낼 일이 있을 때만 (땅문서를 손에 들었을 때·놓았을 때, 청크를 옮겼을 때, 주변 땅 주인이 바뀌었을 때,
 * 내 땅 안 사람·허용 명단이 바뀌었을 때). 매 틱 보내지 않는다.</p>
 * <pre>
 * u8 version(1) · u8 kind
 * VIEW(1):   bool holding; holding 이면 UTF world · i32 centerX · i32 centerZ · u8 radius(0..2) · UTF freeLabel · UTF restrictedLabel
 *            · (2r+1)² 칸 (z 행 → x 열 순서): u8 status · status 가 MINE/OTHER/TRUSTED 면 UTF owner
 * LAND(2):   bool inside; inside 면 i32 cx · i32 cz · u16 owned · u16 max(0=제한 없음)
 *            · u8 visitors · (i64 msb · i64 lsb · UTF name · u8 flags) · u8 trusted · (같은 모양)
 * RESULT(3): bool ok · UTF message
 * </pre>
 * <p>클라 → 서버 {@value #ACTION}: u8 version(1) · u8 action · TRUST/UNTRUST(1/2): i64 msb · i64 lsb · SELL(3): i32 cx · i32 cz.
 * 서버가 모든 동작을 다시 검사한다 (내 땅인지, 그 청크에 서 있는지, 상대가 접속 중인지).</p>
 */
public final class ChunkClaimProtocol {
    public static final String STATE = "chacademi:chunk_claim", ACTION = "chacademi:chunk_claim_action";
    public static final int VERSION = 1, MAX_BYTES = 16_384, MAX_ACTION_BYTES = 64, MAX_RADIUS = 2, MAX_PEOPLE = 48;
    public static final int KIND_VIEW = 1, KIND_LAND = 2, KIND_RESULT = 3;
    /** 칸 상태. */
    public static final int FREE = 0, RESTRICTED = 1, MINE = 2, OTHER = 3, TRUSTED = 4;
    /** 사람 flags. */
    public static final int F_TRUSTED = 1, F_ONLINE = 2;
    public static final int A_TRUST = 1, A_UNTRUST = 2, A_SELL = 3;

    private ChunkClaimProtocol() {}

    public sealed interface Frame permits View, Land, Result {}

    public record Cell(int status, String owner) {
        public Cell {
            if (status < FREE || status > TRUSTED) throw new IllegalArgumentException("Invalid cell status");
            owner = hasOwner(status) ? text(owner, 32, "owner") : "";
        }
    }
    public record View(boolean holding, String world, int centerX, int centerZ, int radius, String freeLabel, String restrictedLabel, List<Cell> cells) implements Frame {
        public static final View HIDDEN = new View(false, "", 0, 0, 0, "", "", List.of());
        public View {
            if (!holding) { world = ""; centerX = centerZ = radius = 0; freeLabel = restrictedLabel = ""; cells = List.of(); }
            else {
                if (radius < 0 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid radius");
                world = text(world, 64, "world");
                freeLabel = text(freeLabel, 24, "label");
                restrictedLabel = text(restrictedLabel, 24, "label");
                cells = List.copyOf(cells);
                if (cells.size() != (2 * radius + 1) * (2 * radius + 1)) throw new IllegalArgumentException("Cell count");
            }
        }
        /** dx, dz = -radius..radius. */
        public Cell cell(int dx, int dz) { int side = 2 * radius + 1; return cells.get((dz + radius) * side + dx + radius); }
    }
    public record Person(UUID id, String name, int flags) {
        public Person {
            Objects.requireNonNull(id);
            name = text(name, 32, "name");
            if ((flags & ~(F_TRUSTED | F_ONLINE)) != 0) throw new IllegalArgumentException("Unknown person flags");
        }
        public boolean trusted() { return (flags & F_TRUSTED) != 0; }
        public boolean online() { return (flags & F_ONLINE) != 0; }
    }
    public record Land(boolean inside, int cx, int cz, int owned, int max, List<Person> visitors, List<Person> trusted) implements Frame {
        public static final Land OUTSIDE = new Land(false, 0, 0, 0, 0, List.of(), List.of());
        public Land {
            if (!inside) { cx = cz = owned = max = 0; visitors = List.of(); trusted = List.of(); }
            else {
                if (owned < 0 || owned > 65535 || max < 0 || max > 65535) throw new IllegalArgumentException("Invalid count");
                visitors = List.copyOf(visitors); trusted = List.copyOf(trusted);
                if (visitors.size() > MAX_PEOPLE || trusted.size() > MAX_PEOPLE) throw new IllegalArgumentException("Too many people");
            }
        }
    }
    public record Result(boolean ok, String message) implements Frame {
        public Result { message = message == null ? "" : cut(message, 200); }
    }
    public record Action(int action, UUID target, int cx, int cz) {
        public Action {
            if (action < A_TRUST || action > A_SELL) throw new IllegalArgumentException("Unknown action");
            if (action != A_SELL) Objects.requireNonNull(target);
            else target = null;
            if (action != A_SELL) { cx = 0; cz = 0; }
        }
        public static Action trust(UUID id, boolean add) { return new Action(add ? A_TRUST : A_UNTRUST, id, 0, 0); }
        public static Action sell(int cx, int cz) { return new Action(A_SELL, null, cx, cz); }
    }

    static boolean hasOwner(int status) { return status == MINE || status == OTHER || status == TRUSTED; }

    /** 제어 문자·§ 없는 1..max 글자. */
    static String text(String s, int max, String what) {
        if (s == null || s.isBlank() || s.length() > max || s.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0xA7))
            throw new IllegalArgumentException("Invalid " + what);
        return s;
    }
    /** 보낼 글자를 안전하게 자른다 (서버 쪽 이름·문구). */
    public static String cut(String s, int max) {
        if (s == null) return "";
        var clean = s.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != 0xA7)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (clean.length() <= max) return clean;
        int end = max; if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end);
    }

    // ------------------------------------------------------------------ encode

    public static byte[] encode(Frame f) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            switch (f) {
                case View v -> {
                    out.writeByte(KIND_VIEW); out.writeBoolean(v.holding());
                    if (v.holding()) {
                        out.writeUTF(v.world()); out.writeInt(v.centerX()); out.writeInt(v.centerZ()); out.writeByte(v.radius());
                        out.writeUTF(v.freeLabel()); out.writeUTF(v.restrictedLabel());
                        for (var c : v.cells()) { out.writeByte(c.status()); if (hasOwner(c.status())) out.writeUTF(c.owner()); }
                    }
                }
                case Land l -> {
                    out.writeByte(KIND_LAND); out.writeBoolean(l.inside());
                    if (l.inside()) {
                        out.writeInt(l.cx()); out.writeInt(l.cz()); out.writeShort(l.owned()); out.writeShort(l.max());
                        people(out, l.visitors()); people(out, l.trusted());
                    }
                }
                case Result r -> { out.writeByte(KIND_RESULT); out.writeBoolean(r.ok()); out.writeUTF(r.message()); }
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Chunk claim packet too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    private static void people(DataOutputStream out, List<Person> list) throws IOException {
        out.writeByte(list.size());
        for (var p : list) { out.writeLong(p.id().getMostSignificantBits()); out.writeLong(p.id().getLeastSignificantBits()); out.writeUTF(p.name()); out.writeByte(p.flags()); }
    }
    public static byte[] encode(Action a) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeByte(VERSION); out.writeByte(a.action());
            if (a.action() == A_SELL) { out.writeInt(a.cx()); out.writeInt(a.cz()); }
            else { out.writeLong(a.target().getMostSignificantBits()); out.writeLong(a.target().getLeastSignificantBits()); }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    // ------------------------------------------------------------------ decode

    /** @throws IllegalArgumentException 형식이 맞지 않으면 (패킷 전체를 버린다) */
    public static Frame decode(byte[] data) {
        if (data == null || data.length < 3 || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid chunk claim size");
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unknown chunk claim version");
            int kind = in.readUnsignedByte();
            Frame f = switch (kind) {
                case KIND_VIEW -> {
                    if (!in.readBoolean()) yield View.HIDDEN;
                    String world = in.readUTF(); int x = in.readInt(), z = in.readInt(), r = in.readUnsignedByte();
                    if (r > MAX_RADIUS) throw new IllegalArgumentException("Invalid radius");
                    String free = in.readUTF(), restricted = in.readUTF();
                    int n = (2 * r + 1) * (2 * r + 1); var cells = new ArrayList<Cell>(n);
                    for (int i = 0; i < n; i++) { int s = in.readUnsignedByte(); cells.add(new Cell(s, hasOwner(s) ? in.readUTF() : "")); }
                    yield new View(true, world, x, z, r, free, restricted, cells);
                }
                case KIND_LAND -> {
                    if (!in.readBoolean()) yield Land.OUTSIDE;
                    int cx = in.readInt(), cz = in.readInt(), owned = in.readUnsignedShort(), max = in.readUnsignedShort();
                    yield new Land(true, cx, cz, owned, max, people(in), people(in));
                }
                case KIND_RESULT -> new Result(in.readBoolean(), in.readUTF());
                default -> throw new IllegalArgumentException("Unknown chunk claim kind");
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing chunk claim bytes");
            return f;
        } catch (IOException e) { throw new IllegalArgumentException("Truncated chunk claim packet", e); }
    }
    private static List<Person> people(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte(); if (n > MAX_PEOPLE) throw new IllegalArgumentException("Too many people");
        var out = new ArrayList<Person>(n); var seen = new HashSet<UUID>();
        for (int i = 0; i < n; i++) {
            var id = new UUID(in.readLong(), in.readLong()); if (!seen.add(id)) throw new IllegalArgumentException("Duplicate person");
            out.add(new Person(id, in.readUTF(), in.readUnsignedByte()));
        }
        return out;
    }

    public static Action decodeAction(byte[] data) {
        if (data == null || data.length < 2 || data.length > MAX_ACTION_BYTES) throw new IllegalArgumentException("Invalid chunk claim action size");
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unknown chunk claim version");
            int a = in.readUnsignedByte();
            Action out = switch (a) {
                case A_TRUST, A_UNTRUST -> new Action(a, new UUID(in.readLong(), in.readLong()), 0, 0);
                case A_SELL -> new Action(a, null, in.readInt(), in.readInt());
                default -> throw new IllegalArgumentException("Unknown action");
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing action bytes");
            return out;
        } catch (IOException e) { throw new IllegalArgumentException("Truncated chunk claim action", e); }
    }
}
