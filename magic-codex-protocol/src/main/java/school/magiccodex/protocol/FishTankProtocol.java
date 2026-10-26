package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 아공간 수조 window (2026-10-20). The tank lives in the shared player-state DB; the server decides everything.
 *
 * C2S: int version, byte action, then
 *   QUERY / CLOSE                     -- nothing
 *   ITEMIZE / RELEASE                 String tankId, long operation
 *   SELL                              String tankId, long expectedPrice, long operation
 *   INSERT                            byte inventorySlot, String tankId ("" for an old fish item without one), long operation
 * S2C frames:
 *   STATE   kind (OPEN also opens the window), capacity, inventoryFull, tank fish, inventory fish, species info, notice
 *   RESULT  operation, action, ok, tankId, notice
 */
public final class FishTankProtocol {
    public static final String REQUEST = "magiccodex:fish_tank_request", RESPONSE = "magiccodex:fish_tank_response";
    public static final int VERSION = 1, MAX_REQUEST_BYTES = 512, MAX_BYTES = 262144;
    public static final int QUERY = 0, ITEMIZE = 1, RELEASE = 2, SELL = 3, INSERT = 4, CLOSE = 5;
    public static final int KIND_OPEN = 0, KIND_STATE = 1;
    public static final int FRAME_STATE = 1, FRAME_RESULT = 2;
    public static final int MAX_FISH = 1000, MAX_INVENTORY = 41, MAX_TEXT = 120, MAX_DESCRIPTION = 240;

    public static boolean validTankId(String s) { return s != null && s.length() <= 36 && s.matches("[0-9a-fA-F-]*"); }

    public record Request(int action, String tankId, long expected, int slot, long operation) {
        public Request {
            if (action < QUERY || action > CLOSE || !validTankId(tankId) || expected < 0 || slot < 0 || slot >= MAX_INVENTORY)
                throw new IllegalArgumentException("fish tank request");
        }
        public static Request of(int action) { return new Request(action, "", 0, 0, 0); }
        public static Request fish(int action, String tankId, long operation) { return new Request(action, tankId, 0, 0, operation); }
        public static Request sell(String tankId, long expected, long operation) { return new Request(SELL, tankId, expected, 0, operation); }
        public static Request insert(int slot, String tankId, long operation) { return new Request(INSERT, tankId, 0, slot, operation); }
    }

    /** One fish swimming in the tank. price = instant sale price (shop prices); locked = the species is locked (never sold). */
    public record Fish(String id, String fish, float size, long caughtAt, long price, boolean locked) {
        public Fish {
            if (!validTankId(id) || id.isEmpty() || !FishingProtocol.validId(fish) || !Float.isFinite(size) || size < 0 || caughtAt < 0 || price < 0)
                throw new IllegalArgumentException("fish tank fish");
        }
    }
    /** A fish item in the player's inventory (main inventory slots 0..35, offhand 40). */
    public record Held(int slot, String id, String fish, float size, long caughtAt) {
        public Held {
            if (slot < 0 || slot >= MAX_INVENTORY || !validTankId(id) || !FishingProtocol.validId(fish) || !Float.isFinite(size) || size < 0 || caughtAt < 0)
                throw new IllegalArgumentException("fish tank held");
        }
    }
    /** What the client needs to show a species (only the ones in the tank or the inventory are sent). */
    public record Info(String id, String name, int grade, int shape, String description) {
        public Info {
            if (!FishingProtocol.validId(id) || name == null || name.length() > FishingProtocol.MAX_NAME || grade < 0 || grade > 3 || shape < 0 || shape > 255
                    || description == null || description.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("fish tank info");
        }
    }
    public sealed interface Frame permits State, Result {}
    public record State(int kind, int capacity, boolean inventoryFull, List<Fish> fish, List<Held> inventory, List<Info> species, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || capacity < 1 || fish == null || fish.size() > MAX_FISH || inventory == null
                    || inventory.size() > MAX_INVENTORY || species == null || species.size() > MAX_FISH + MAX_INVENTORY || !text(notice))
                throw new IllegalArgumentException("fish tank state");
            fish = List.copyOf(fish); inventory = List.copyOf(inventory); species = List.copyOf(species);
        }
    }
    public record Result(long operation, int action, boolean ok, String tankId, String notice) implements Frame {
        public Result { if (action < QUERY || action > CLOSE || !validTankId(tankId) || !text(notice)) throw new IllegalArgumentException("fish tank result"); }
    }

    private static boolean text(String s) { return s != null && s.length() <= MAX_TEXT; }

    // ------------------------------------------------------------------ codec

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action());
            switch (r.action()) {
                case ITEMIZE, RELEASE -> { d.writeUTF(r.tankId()); d.writeLong(r.operation()); }
                case SELL -> { d.writeUTF(r.tankId()); d.writeLong(r.expected()); d.writeLong(r.operation()); }
                case INSERT -> { d.writeByte(r.slot()); d.writeUTF(r.tankId()); d.writeLong(r.operation()); }
                default -> {}
            }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            return switch (action) {
                case ITEMIZE, RELEASE -> Request.fish(action, d.readUTF(), d.readLong());
                case SELL -> Request.sell(d.readUTF(), d.readLong(), d.readLong());
                case INSERT -> { int slot = d.readUnsignedByte(); yield Request.insert(slot, d.readUTF(), d.readLong()); }
                default -> Request.of(action);
            };
        });
    }

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind()); d.writeInt(s.capacity()); d.writeBoolean(s.inventoryFull());
                    d.writeShort(s.fish().size());
                    for (Fish f : s.fish()) { d.writeUTF(f.id()); d.writeUTF(f.fish()); d.writeFloat(f.size()); d.writeLong(f.caughtAt()); d.writeLong(f.price()); d.writeBoolean(f.locked()); }
                    d.writeByte(s.inventory().size());
                    for (Held h : s.inventory()) { d.writeByte(h.slot()); d.writeUTF(h.id()); d.writeUTF(h.fish()); d.writeFloat(h.size()); d.writeLong(h.caughtAt()); }
                    d.writeShort(s.species().size());
                    for (Info i : s.species()) { d.writeUTF(i.id()); d.writeUTF(i.name()); d.writeByte(i.grade()); d.writeByte(i.shape()); d.writeUTF(i.description()); }
                    d.writeUTF(s.notice());
                }
                case Result r -> { d.writeByte(FRAME_RESULT); d.writeLong(r.operation()); d.writeByte(r.action()); d.writeBoolean(r.ok()); d.writeUTF(r.tankId()); d.writeUTF(r.notice()); }
            }
        });
    }
    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte(), capacity = d.readInt(); boolean full = d.readBoolean();
                int n = d.readUnsignedShort(); if (n > MAX_FISH) throw new IOException("fish");
                var fish = new ArrayList<Fish>();
                for (int i = 0; i < n; i++) fish.add(new Fish(d.readUTF(), d.readUTF(), d.readFloat(), d.readLong(), d.readLong(), d.readBoolean()));
                int h = d.readUnsignedByte(); if (h > MAX_INVENTORY) throw new IOException("inventory");
                var held = new ArrayList<Held>();
                for (int i = 0; i < h; i++) held.add(new Held(d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readFloat(), d.readLong()));
                int m = d.readUnsignedShort(); if (m > MAX_FISH + MAX_INVENTORY) throw new IOException("species");
                var species = new ArrayList<Info>();
                for (int i = 0; i < m; i++) species.add(new Info(d.readUTF(), d.readUTF(), d.readUnsignedByte(), d.readUnsignedByte(), d.readUTF()));
                return new State(kind, capacity, full, fish, held, species, d.readUTF());
            }
            if (type == FRAME_RESULT) return new Result(d.readLong(), d.readUnsignedByte(), d.readBoolean(), d.readUTF(), d.readUTF());
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
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fish tank packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid fish tank packet", e); }
    }

    private FishTankProtocol() {}
}
