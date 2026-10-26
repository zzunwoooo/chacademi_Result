package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 마일리지 상점 (2026-10-24, owner reference "마일리지 상점 UI"). Mileage is its own currency (not Vault gold): admins change it
 * with /유저관리 &lt;닉네임&gt; 마일리지 …, players spend it here and the bought item goes to the mailbox.
 * The server owns balances, products, prices and validation; the client only shows what it got and asks.
 *
 * C2S: int version, byte action, then
 *   OPEN / CLOSE / ADMIN_OPEN          nothing
 *   BUY                                String product, int quantity, long expectedPrice, long operation
 *   ADMIN_ADD                          Edit (product id ignored), byte slot (admin's inventory slot 0..35 holding the item)
 *   ADMIN_SAVE                         Edit
 *   ADMIN_ITEM                         String product, byte slot (replace the item with the one in that inventory slot)
 *   ADMIN_DELETE                       String product
 *   ADMIN_MOVE                         String product, byte step (0 = earlier, 1 = later)
 * S2C frames:
 *   STATE   kind, admin, balance, products, select (product the window should pick, "" = keep), notice
 *   RESULT  operation, ok, balance, notice
 */
public final class MileageProtocol {
    public static final String REQUEST = "magiccodex:mileage_request", RESPONSE = "magiccodex:mileage_response";
    public static final int VERSION = 1, MAX_REQUEST_BYTES = 4096, MAX_BYTES = 1_000_000;
    public static final int OPEN = 0, BUY = 1, CLOSE = 2, ADMIN_OPEN = 10, ADMIN_ADD = 11, ADMIN_SAVE = 12, ADMIN_ITEM = 13, ADMIN_DELETE = 14, ADMIN_MOVE = 15;
    public static final int KIND_OPEN = 0, KIND_STATE = 1;
    public static final int FRAME_STATE = 1, FRAME_RESULT = 2;
    /** Categories in tab order (owner README: 편의 / 이벤트 / 이용권). */
    public static final String[] CATEGORIES = {"편의", "이벤트", "이용권"};
    public static final int MAX_PRODUCTS = 120, MAX_NAME = 40, MAX_DESCRIPTION = 80, MAX_TEXT = 160, MAX_PREVIEW_BYTES = 32768;
    public static final int MAX_QUANTITY = 99;
    /** Player inventory slots an admin can pick from: 0..8 hotbar, 9..35 main (same numbers on Bukkit and the client). */
    public static final int SLOTS = 36;
    public static final long MAX_PRICE = 1_000_000_000L, MAX_BALANCE = 1_000_000_000_000L;

    /** Admin edit of one product. */
    public record Edit(String id, int category, String name, String description, long price, boolean enabled) {
        public Edit {
            if (id == null || id.length() > 36 || category < 0 || category >= CATEGORIES.length || !validName(name) || description == null
                    || description.length() > MAX_DESCRIPTION || price < 0 || price > MAX_PRICE) throw new IllegalArgumentException("mileage edit");
        }
    }
    public record Request(int action, String product, int quantity, long expected, long operation, int step, Edit edit, int slot) {
        public Request {
            if ((action == ADMIN_ADD || action == ADMIN_ITEM) != (slot >= 0) || slot >= SLOTS) throw new IllegalArgumentException("mileage request slot");
            if (!validAction(action) || product == null || product.length() > 36 || quantity < 0 || quantity > MAX_QUANTITY || expected < 0 || step < 0 || step > 1)
                throw new IllegalArgumentException("mileage request");
            if ((action == ADMIN_ADD || action == ADMIN_SAVE) != (edit != null)) throw new IllegalArgumentException("mileage request edit");
        }
        public static Request of(int action) { return new Request(action, "", 0, 0, 0, 0, null, -1); }
        public static Request buy(String product, int quantity, long expected, long operation) {
            if (quantity < 1) throw new IllegalArgumentException("mileage quantity");
            return new Request(BUY, product, quantity, expected, operation, 0, null, -1);
        }
        public static Request save(Edit edit) { return new Request(ADMIN_SAVE, edit.id(), 0, 0, 0, 0, edit, -1); }
        public static Request add(Edit edit, int slot) { return new Request(ADMIN_ADD, edit.id(), 0, 0, 0, 0, edit, slot); }
        public static Request replace(String product, int slot) { return new Request(ADMIN_ITEM, product, 0, 0, 0, 0, null, slot); }
        public static Request delete(String product) { return new Request(ADMIN_DELETE, product, 0, 0, 0, 0, null, -1); }
        public static Request move(String product, boolean later) { return new Request(ADMIN_MOVE, product, 0, 0, 0, later ? 1 : 0, null, -1); }
    }
    private static boolean validAction(int a) { return a == OPEN || a == BUY || a == CLOSE || a >= ADMIN_OPEN && a <= ADMIN_MOVE; }
    public static boolean validName(String s) { return s != null && !s.isBlank() && s.length() <= MAX_NAME; }

    /** One product. preview = the item's serialized NBT (compressed) for the icon/tooltip; enabled=false only reaches admins. */
    public record Product(String id, int category, String name, String description, long price, boolean enabled, byte[] preview) {
        public Product {
            if (id == null || id.isEmpty() || id.length() > 36 || category < 0 || category >= CATEGORIES.length || !validName(name) || description == null
                    || description.length() > MAX_DESCRIPTION || price < 0 || price > MAX_PRICE || preview == null || preview.length > MAX_PREVIEW_BYTES)
                throw new IllegalArgumentException("mileage product");
        }
    }
    public sealed interface Frame permits State, Result {}
    public record State(int kind, boolean admin, long balance, List<Product> products, String select, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || balance < 0 || products == null || products.size() > MAX_PRODUCTS || select == null
                    || select.length() > 36 || !text(notice)) throw new IllegalArgumentException("mileage state");
            products = List.copyOf(products);
        }
    }
    public record Result(long operation, boolean ok, long balance, String notice) implements Frame {
        public Result { if (balance < 0 || !text(notice)) throw new IllegalArgumentException("mileage result"); }
    }
    private static boolean text(String s) { return s != null && s.length() <= MAX_TEXT; }

    /** price x quantity, or -1 when it would pass the balance ceiling. */
    public static long total(long price, int quantity) {
        if (price < 0 || quantity < 0) return -1;
        try { long t = Math.multiplyExact(price, (long) quantity); return t > MAX_BALANCE ? -1 : t; }
        catch (ArithmeticException e) { return -1; }
    }

    /** "12,500" — display only. */
    public static String amount(long value) { return String.format(Locale.ROOT, "%,d", value); }

    // ------------------------------------------------------------------ codec

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action());
            switch (r.action()) {
                case BUY -> { d.writeUTF(r.product()); d.writeShort(r.quantity()); d.writeLong(r.expected()); d.writeLong(r.operation()); }
                case ADMIN_ADD, ADMIN_SAVE -> {
                    var e = r.edit(); d.writeUTF(e.id()); d.writeByte(e.category()); d.writeUTF(e.name()); d.writeUTF(e.description()); d.writeLong(e.price()); d.writeBoolean(e.enabled());
                    if (r.action() == ADMIN_ADD) d.writeByte(r.slot());
                }
                case ADMIN_ITEM -> { d.writeUTF(r.product()); d.writeByte(r.slot()); }
                case ADMIN_DELETE -> d.writeUTF(r.product());
                case ADMIN_MOVE -> { d.writeUTF(r.product()); d.writeByte(r.step()); }
                default -> {}
            }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            return switch (action) {
                case BUY -> Request.buy(d.readUTF(), d.readUnsignedShort(), d.readLong(), d.readLong());
                case ADMIN_ADD -> { var e = new Edit(d.readUTF(), d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readLong(), d.readBoolean()); yield Request.add(e, d.readUnsignedByte()); }
                case ADMIN_SAVE -> Request.save(new Edit(d.readUTF(), d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readLong(), d.readBoolean()));
                case ADMIN_ITEM -> { String id = d.readUTF(); yield Request.replace(id, d.readUnsignedByte()); }
                case ADMIN_DELETE -> Request.delete(d.readUTF());
                case ADMIN_MOVE -> { String id = d.readUTF(); int step = d.readUnsignedByte(); if (step > 1) throw new IOException("step"); yield Request.move(id, step == 1); }
                default -> Request.of(action);
            };
        });
    }

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind()); d.writeBoolean(s.admin()); d.writeLong(s.balance());
                    d.writeShort(s.products().size());
                    for (Product p : s.products()) {
                        d.writeUTF(p.id()); d.writeByte(p.category()); d.writeUTF(p.name()); d.writeUTF(p.description()); d.writeLong(p.price()); d.writeBoolean(p.enabled());
                        d.writeInt(p.preview().length); d.write(p.preview());
                    }
                    d.writeUTF(s.select()); d.writeUTF(s.notice());
                }
                case Result r -> { d.writeByte(FRAME_RESULT); d.writeLong(r.operation()); d.writeBoolean(r.ok()); d.writeLong(r.balance()); d.writeUTF(r.notice()); }
            }
        });
    }
    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte(); boolean admin = d.readBoolean(); long balance = d.readLong();
                int n = d.readUnsignedShort(); if (n > MAX_PRODUCTS) throw new IOException("products");
                var list = new ArrayList<Product>();
                for (int i = 0; i < n; i++) {
                    String id = d.readUTF(); int category = d.readUnsignedByte(); String name = d.readUTF(), description = d.readUTF(); long price = d.readLong(); boolean enabled = d.readBoolean();
                    int size = d.readInt(); if (size < 0 || size > MAX_PREVIEW_BYTES) throw new IOException("preview");
                    byte[] preview = new byte[size]; d.readFully(preview);
                    list.add(new Product(id, category, name, description, price, enabled, preview));
                }
                return new State(kind, admin, balance, list, d.readUTF(), d.readUTF());
            }
            if (type == FRAME_RESULT) return new Result(d.readLong(), d.readBoolean(), d.readLong(), d.readUTF());
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
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid mileage packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid mileage packet", e); }
    }

    private MileageProtocol() {}
}
