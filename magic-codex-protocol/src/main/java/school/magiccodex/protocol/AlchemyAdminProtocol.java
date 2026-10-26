package school.magiccodex.protocol;

import java.io.*;
import java.util.*;
import school.magiccodex.protocol.AlchemyProtocol.Item;
import school.magiccodex.protocol.AlchemyProtocol.Stack;

/**
 * Alchemy recipe editor for operators (/연금술관리, 2026-10-17). The server owns alchemy.yml and checks the
 * operator permission on every request; the client only edits a draft and sends it.
 *
 * C2S: int version, byte action, long sequence, then SAVE: Row / DELETE: String id.
 * S2C STATE: byte kind (OPEN/STATE), byte slots, items (key, preview), inventory (key, count, material),
 *            rows (every recipe in alchemy.yml, switched-off ones too), String notice.
 * S2C RESULT: long sequence, bool ok, String id (saved/deleted recipe), String message.
 */
public final class AlchemyAdminProtocol {
    public static final String REQUEST = "magiccodex:alchemy_admin_request", RESPONSE = "magiccodex:alchemy_admin_response";
    public static final int VERSION = 1, MAX_REQUEST_BYTES = 8192, MAX_BYTES = 262144;
    public static final int QUERY = 0, SAVE = 1, DELETE = 2, RELOAD = 3, CLOSE = 4;
    public static final int KIND_OPEN = 0, KIND_STATE = 1, FRAME_STATE = 1, FRAME_RESULT = 2;
    public static final int MAX_RESULT_AMOUNT = 576;

    /** One recipe as written in alchemy.yml. id "" = a new recipe (the server names it). result "" = not chosen yet. */
    public record Row(String id, String name, String result, int amount, int exp, boolean enabled, int order, List<Stack> ingredients) {
        public Row {
            if (id == null || (!id.isEmpty() && !AlchemyProtocol.validId(id)) || name == null || name.length() > AlchemyProtocol.MAX_NAME
                    || result == null || (!result.isEmpty() && !AlchemyProtocol.validKey(result)) || amount < 1 || amount > MAX_RESULT_AMOUNT
                    || exp < 0 || exp > AlchemyProtocol.MAX_EXP || ingredients == null || ingredients.size() > AlchemyProtocol.MAX_SLOTS)
                throw new IllegalArgumentException("alchemy admin row " + id);
            ingredients = List.copyOf(ingredients);
        }
    }
    /** An item the operator carries (to pick ingredients / results from). material = the player slot picker shows it. */
    public record Held(String key, int count, boolean material) {
        public Held { if (!AlchemyProtocol.validKey(key) || count < 0) throw new IllegalArgumentException("alchemy admin held"); }
    }
    public record Request(int action, long sequence, Row row, String id) {
        public Request {
            if (action < QUERY || action > CLOSE || sequence < 0 || (action == SAVE && row == null)
                    || (action == DELETE && (id == null || !AlchemyProtocol.validId(id))))
                throw new IllegalArgumentException("alchemy admin request");
            if (id == null) id = "";
        }
    }
    public sealed interface Frame permits State, Result {}
    public record State(int kind, int slots, List<Item> items, List<Held> inventory, List<Row> rows, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || slots < 1 || slots > AlchemyProtocol.MAX_SLOTS || items.size() > AlchemyProtocol.MAX_ITEMS
                    || inventory.size() > 64 || rows.size() > AlchemyProtocol.MAX_RECIPES * 2 || notice == null || notice.length() > AlchemyProtocol.MAX_NOTICE)
                throw new IllegalArgumentException("alchemy admin state");
            items = List.copyOf(items); inventory = List.copyOf(inventory); rows = List.copyOf(rows);
        }
    }
    public record Result(long sequence, boolean ok, String id, String message) implements Frame {
        public Result {
            if (sequence < 0 || id == null || (!id.isEmpty() && !AlchemyProtocol.validId(id)) || message == null || message.length() > 300)
                throw new IllegalArgumentException("alchemy admin result");
        }
    }

    // ------------------------------------------------------------------ codec

    private static void row(DataOutputStream d, Row r) throws IOException {
        d.writeUTF(r.id()); d.writeUTF(r.name()); d.writeUTF(r.result()); d.writeShort(r.amount()); d.writeInt(r.exp());
        d.writeBoolean(r.enabled()); d.writeInt(r.order()); d.writeByte(r.ingredients().size());
        for (Stack s : r.ingredients()) { d.writeUTF(s.key()); d.writeByte(s.amount()); }
    }
    private static Row row(DataInputStream d) throws IOException {
        String id = d.readUTF(), name = d.readUTF(), result = d.readUTF(); int amount = d.readUnsignedShort(), exp = d.readInt();
        boolean enabled = d.readBoolean(); int order = d.readInt(), n = d.readUnsignedByte();
        if (n > AlchemyProtocol.MAX_SLOTS) throw new IOException("ingredients");
        var list = new ArrayList<Stack>();
        for (int i = 0; i < n; i++) list.add(new Stack(d.readUTF(), d.readUnsignedByte()));
        return new Row(id, name, result, amount, exp, enabled, order, list);
    }

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.sequence());
            if (r.action() == SAVE) row(d, r.row());
            if (r.action() == DELETE) d.writeUTF(r.id());
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte(); long seq = d.readLong();
            Row row = action == SAVE ? row(d) : null;
            String id = action == DELETE ? d.readUTF() : "";
            return new Request(action, seq, row, id);
        });
    }

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind()); d.writeByte(s.slots());
                    d.writeShort(s.items().size());
                    for (Item i : s.items()) { d.writeUTF(i.key()); d.writeShort(i.preview().length); d.write(i.preview()); }
                    d.writeByte(s.inventory().size());
                    for (Held h : s.inventory()) { d.writeUTF(h.key()); d.writeInt(h.count()); d.writeBoolean(h.material()); }
                    d.writeShort(s.rows().size());
                    for (Row r : s.rows()) row(d, r);
                    d.writeUTF(s.notice());
                }
                case Result r -> { d.writeByte(FRAME_RESULT); d.writeLong(r.sequence()); d.writeBoolean(r.ok()); d.writeUTF(r.id()); d.writeUTF(r.message()); }
            }
        });
    }
    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte(), slots = d.readUnsignedByte();
                int n = d.readUnsignedShort(); if (n > AlchemyProtocol.MAX_ITEMS) throw new IOException("items");
                var items = new ArrayList<Item>();
                for (int i = 0; i < n; i++) {
                    String key = d.readUTF(); int len = d.readUnsignedShort();
                    if (len > AlchemyProtocol.MAX_PREVIEW || len > d.available()) throw new IOException("preview");
                    items.add(new Item(key, d.readNBytes(len)));
                }
                n = d.readUnsignedByte(); if (n > 64) throw new IOException("inventory");
                var inv = new ArrayList<Held>();
                for (int i = 0; i < n; i++) inv.add(new Held(d.readUTF(), d.readInt(), d.readBoolean()));
                n = d.readUnsignedShort(); if (n > AlchemyProtocol.MAX_RECIPES * 2) throw new IOException("rows");
                var rows = new ArrayList<Row>();
                for (int i = 0; i < n; i++) rows.add(row(d));
                return new State(kind, slots, items, inv, rows, d.readUTF());
            }
            if (type == FRAME_RESULT) return new Result(d.readLong(), d.readBoolean(), d.readUTF(), d.readUTF());
            throw new IOException("frame");
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try { var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b)); if (b.size() > max) throw new IOException("too large"); return b.toByteArray(); }
        catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid alchemy admin packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes)); T v = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return v;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid alchemy admin packet", e); }
    }
    private AlchemyAdminProtocol() {}
}
