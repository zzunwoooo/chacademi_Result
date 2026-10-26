package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 물고기 판매소 (2026-10-20): sells fish straight from the 아공간 수조 tank by grade, skipping locked species.
 * The server owns prices (fish-shop.yml), the tank and the money; the client only shows what it got and asks.
 *
 * C2S: int version, byte action, then
 *   QUERY / CLOSE        -- nothing
 *   SELL                 byte gradeMask, long expectedTotal, long operation (the server refuses when the total changed)
 *   LOCKS                short n, n x String fishId (the whole locked set)
 * S2C frames:
 *   STATE   kind (OPEN also opens the window), title, role, npcName, portrait, balance, tankCount, capacity,
 *           4 x Grade, locks, species (OPEN only, else empty), notice
 *   RESULT  operation, ok, sold, total, notice
 */
public final class FishShopProtocol {
    public static final String REQUEST = "magiccodex:fish_shop_request", RESPONSE = "magiccodex:fish_shop_response";
    public static final int VERSION = 1, MAX_REQUEST_BYTES = 16384, MAX_BYTES = 65536;
    public static final int QUERY = 0, SELL = 1, LOCKS = 2, CLOSE = 3;
    public static final int KIND_OPEN = 0, KIND_STATE = 1;
    public static final int FRAME_STATE = 1, FRAME_RESULT = 2;
    public static final int MAX_SPECIES = 400, MAX_TEXT = 120;

    public record Request(int action, int mask, long expected, long operation, List<String> locks) {
        public Request {
            if (action < QUERY || action > CLOSE || mask < 0 || mask > 15 || expected < 0 || locks == null || locks.size() > MAX_SPECIES)
                throw new IllegalArgumentException("fish shop request");
            for (String s : locks) if (!FishingProtocol.validId(s)) throw new IllegalArgumentException("fish shop lock");
            locks = List.copyOf(locks);
        }
        public static Request of(int action) { return new Request(action, 0, 0, 0, List.of()); }
        public static Request sell(int mask, long expected, long operation) { return new Request(SELL, mask, expected, operation, List.of()); }
        public static Request locks(Collection<String> ids) { return new Request(LOCKS, 0, 0, 0, List.copyOf(ids)); }
    }

    /** Sellable fish of one grade (locked species already left out). sample = fish id to draw on the row ("" = none). */
    public record Grade(int count, long total, String sample) {
        public Grade {
            if (count < 0 || total < 0 || sample == null || (!sample.isEmpty() && !FishingProtocol.validId(sample))) throw new IllegalArgumentException("fish shop grade");
        }
    }
    public record Species(String id, String name, int grade) {
        public Species {
            if (!FishingProtocol.validId(id) || name == null || name.length() > FishingProtocol.MAX_NAME || grade < 0 || grade > 3)
                throw new IllegalArgumentException("fish shop species");
        }
    }
    public sealed interface Frame permits State, Result {}
    public record State(int kind, String title, String role, String npcName, String portrait, String balance, int tankCount, int capacity,
                        List<Grade> grades, List<String> locks, List<Species> species, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || !text(title) || !text(role) || !text(npcName) || !text(portrait) || !text(balance)
                    || tankCount < 0 || capacity < 1 || grades == null || grades.size() != 4 || locks == null || locks.size() > MAX_SPECIES
                    || species == null || species.size() > MAX_SPECIES || !text(notice)) throw new IllegalArgumentException("fish shop state");
            grades = List.copyOf(grades); locks = List.copyOf(locks); species = List.copyOf(species);
        }
    }
    public record Result(long operation, boolean ok, int sold, long total, String notice) implements Frame {
        public Result { if (sold < 0 || total < 0 || !text(notice)) throw new IllegalArgumentException("fish shop result"); }
    }

    private static boolean text(String s) { return s != null && s.length() <= MAX_TEXT; }

    // ------------------------------------------------------------------ price

    /**
     * Sale price of one fish: base x size factor, rounded. ratio = where the size sits in the species' range (0..1);
     * the factor goes linearly from sizeMin to sizeMax. Both sides use this (the client only for display).
     */
    public static long price(double base, double size, double minSize, double maxSize, double sizeMin, double sizeMax) {
        double ratio = maxSize > minSize ? Math.clamp((size - minSize) / (maxSize - minSize), 0, 1) : 1;
        return Math.max(0, Math.round(base * (sizeMin + (sizeMax - sizeMin) * ratio)));
    }

    // ------------------------------------------------------------------ codec

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action());
            if (r.action() == SELL) { d.writeByte(r.mask()); d.writeLong(r.expected()); d.writeLong(r.operation()); }
            if (r.action() == LOCKS) { d.writeShort(r.locks().size()); for (String s : r.locks()) d.writeUTF(s); }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            if (action == SELL) return Request.sell(d.readUnsignedByte(), d.readLong(), d.readLong());
            if (action == LOCKS) {
                int n = d.readUnsignedShort(); if (n > MAX_SPECIES) throw new IOException("locks");
                var list = new ArrayList<String>(); for (int i = 0; i < n; i++) list.add(d.readUTF());
                return Request.locks(list);
            }
            return Request.of(action);
        });
    }

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind()); d.writeUTF(s.title()); d.writeUTF(s.role()); d.writeUTF(s.npcName());
                    d.writeUTF(s.portrait()); d.writeUTF(s.balance()); d.writeInt(s.tankCount()); d.writeInt(s.capacity());
                    for (Grade g : s.grades()) { d.writeInt(g.count()); d.writeLong(g.total()); d.writeUTF(g.sample()); }
                    d.writeShort(s.locks().size()); for (String l : s.locks()) d.writeUTF(l);
                    d.writeShort(s.species().size()); for (Species sp : s.species()) { d.writeUTF(sp.id()); d.writeUTF(sp.name()); d.writeByte(sp.grade()); }
                    d.writeUTF(s.notice());
                }
                case Result r -> { d.writeByte(FRAME_RESULT); d.writeLong(r.operation()); d.writeBoolean(r.ok()); d.writeInt(r.sold()); d.writeLong(r.total()); d.writeUTF(r.notice()); }
            }
        });
    }
    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte(); String title = d.readUTF(), role = d.readUTF(), npc = d.readUTF(), portrait = d.readUTF(), balance = d.readUTF();
                int count = d.readInt(), capacity = d.readInt();
                var grades = new ArrayList<Grade>(); for (int i = 0; i < 4; i++) grades.add(new Grade(d.readInt(), d.readLong(), d.readUTF()));
                int n = d.readUnsignedShort(); if (n > MAX_SPECIES) throw new IOException("locks");
                var locks = new ArrayList<String>(); for (int i = 0; i < n; i++) locks.add(d.readUTF());
                int m = d.readUnsignedShort(); if (m > MAX_SPECIES) throw new IOException("species");
                var species = new ArrayList<Species>(); for (int i = 0; i < m; i++) species.add(new Species(d.readUTF(), d.readUTF(), d.readUnsignedByte()));
                return new State(kind, title, role, npc, portrait, balance, count, capacity, grades, locks, species, d.readUTF());
            }
            if (type == FRAME_RESULT) return new Result(d.readLong(), d.readBoolean(), d.readInt(), d.readLong(), d.readUTF());
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
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fish shop packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fish shop packet", e); }
    }

    private FishShopProtocol() {}
}
