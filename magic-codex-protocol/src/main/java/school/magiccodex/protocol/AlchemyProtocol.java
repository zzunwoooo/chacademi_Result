package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * Alchemy (연금술, 2026-10-16): experience-cost crafting with hidden recipes. The server owns the recipes
 * (plugins/MagicCodexBridge/alchemy.yml), discovery (LuckPerms magiccodex.alchemy.recipe.&lt;id&gt;) and every
 * inventory / experience change; the client only shows what the server sent and asks it to craft.
 *
 * Item keys are opaque strings made by the server: the ItemsAdder id ("item:lily") or the vanilla id
 * ("minecraft:sugar"). Only keys and amounts cross the trust boundary from the client.
 *
 * C2S request: int version, byte action (QUERY / CRAFT / CLOSE), long sequence, byte n, n x (String key, byte amount).
 * S2C frames:
 *   STATE  byte kind (OPEN = also open the window / STATE), byte slots,
 *          items: short n, n x (String key, bytes preview)   -- icon/name dictionary for every key used below
 *          materials: short n, n x (String key, int owned, bool selectable)
 *          recipes: short n, n x (String id, String name, String result, short resultAmount, int exp, byte n, n x (key, amount))
 *          int locked, String notice
 *   RESULT long sequence, byte outcome, String recipeId, String result, short amount, bool mailed, String notice
 * Undiscovered recipes never leave the server: the client only learns how many are still locked.
 */
public final class AlchemyProtocol {
    public static final String REQUEST = "magiccodex:alchemy_request", RESPONSE = "magiccodex:alchemy_response";
    public static final int VERSION = 1;
    public static final int MAX_REQUEST_BYTES = 4096, MAX_BYTES = 262144;
    public static final int QUERY = 0, CRAFT = 1, CLOSE = 2;
    public static final int KIND_OPEN = 0, KIND_STATE = 1;
    public static final int FRAME_STATE = 1, FRAME_RESULT = 2;
    /** Craft outcomes. NEAR = one ingredient away from a recipe, NEAR_AMOUNT = right ingredients, wrong amounts. */
    public static final int CRAFTED = 0, DISCOVERED = 1, DISCOVERED_NO_EXP = 2, NEAR = 3, NEAR_AMOUNT = 4, NOTHING = 5, REFUSED = 6;
    public static final int MAX_SLOTS = 8, MAX_AMOUNT = 64, MAX_KEY = 96, MAX_PREVIEW = 2048, MAX_ITEMS = 1024,
            MAX_MATERIALS = 512, MAX_RECIPES = 512, MAX_ID = 64, MAX_NAME = 40, MAX_NOTICE = 160, MAX_EXP = 1_000_000;

    public record Stack(String key, int amount) {
        public Stack {
            if (!validKey(key) || amount < 1 || amount > MAX_AMOUNT) throw new IllegalArgumentException("alchemy stack");
        }
    }
    public record Request(int action, long sequence, List<Stack> slots) {
        public Request {
            if (action < QUERY || action > CLOSE || sequence < 0 || slots == null || slots.size() > MAX_SLOTS)
                throw new IllegalArgumentException("alchemy request");
            slots = List.copyOf(slots);
        }
    }
    /** Icon/name source for one key: the server's serialized item stack (compressed NBT, may be empty). */
    public record Item(String key, byte[] preview) {
        public Item {
            if (!validKey(key) || preview == null || preview.length > MAX_PREVIEW) throw new IllegalArgumentException("alchemy item");
        }
    }
    /** How many of this key the player carries. selectable = shown in the slot picker (category 재료). */
    public record Material(String key, int owned, boolean selectable) {
        public Material {
            if (!validKey(key) || owned < 0) throw new IllegalArgumentException("alchemy material");
        }
    }
    /** A discovered recipe. */
    public record Recipe(String id, String name, String result, int resultAmount, int exp, List<Stack> ingredients) {
        public Recipe {
            if (!validId(id) || name == null || name.isBlank() || name.length() > MAX_NAME || !validKey(result)
                    || resultAmount < 1 || resultAmount > 6400 || exp < 0 || exp > MAX_EXP
                    || ingredients == null || ingredients.isEmpty() || ingredients.size() > MAX_SLOTS)
                throw new IllegalArgumentException("alchemy recipe " + id);
            ingredients = List.copyOf(ingredients);
        }
    }
    public sealed interface Frame permits State, Result {}
    public record State(int kind, int slots, List<Item> items, List<Material> materials, List<Recipe> recipes, int locked, String notice) implements Frame {
        public State {
            if ((kind != KIND_OPEN && kind != KIND_STATE) || slots < 1 || slots > MAX_SLOTS || items == null || items.size() > MAX_ITEMS
                    || materials == null || materials.size() > MAX_MATERIALS || recipes == null || recipes.size() > MAX_RECIPES
                    || locked < 0 || locked > MAX_RECIPES || notice == null || notice.length() > MAX_NOTICE)
                throw new IllegalArgumentException("alchemy state");
            items = List.copyOf(items); materials = List.copyOf(materials); recipes = List.copyOf(recipes);
        }
    }
    /** recipeId/result are "" unless something was crafted or discovered. */
    public record Result(long sequence, int outcome, String recipeId, String result, int amount, boolean mailed, String notice) implements Frame {
        public Result {
            if (sequence < 0 || outcome < CRAFTED || outcome > REFUSED || recipeId == null || (!recipeId.isEmpty() && !validId(recipeId))
                    || result == null || (!result.isEmpty() && !validKey(result)) || amount < 0 || amount > 6400
                    || notice == null || notice.length() > MAX_NOTICE)
                throw new IllegalArgumentException("alchemy result");
        }
    }

    public static boolean validId(String id) { return id != null && !id.isEmpty() && id.length() <= MAX_ID && id.matches("[a-z0-9_]+"); }
    public static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY && key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    // ------------------------------------------------------------------ request

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.sequence()); d.writeByte(r.slots().size());
            for (Stack s : r.slots()) { d.writeUTF(s.key()); d.writeByte(s.amount()); }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte(); long sequence = d.readLong(); int n = d.readUnsignedByte();
            if (n > MAX_SLOTS) throw new IOException("slots");
            var slots = new ArrayList<Stack>();
            for (int i = 0; i < n; i++) slots.add(new Stack(d.readUTF(), d.readUnsignedByte()));
            return new Request(action, sequence, slots);
        });
    }

    // ------------------------------------------------------------------ frames

    public static byte[] encode(Frame frame) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION);
            switch (frame) {
                case State s -> {
                    d.writeByte(FRAME_STATE); d.writeByte(s.kind()); d.writeByte(s.slots());
                    d.writeShort(s.items().size());
                    for (Item i : s.items()) { d.writeUTF(i.key()); d.writeShort(i.preview().length); d.write(i.preview()); }
                    d.writeShort(s.materials().size());
                    for (Material m : s.materials()) { d.writeUTF(m.key()); d.writeInt(m.owned()); d.writeBoolean(m.selectable()); }
                    d.writeShort(s.recipes().size());
                    for (Recipe r : s.recipes()) {
                        d.writeUTF(r.id()); d.writeUTF(r.name()); d.writeUTF(r.result()); d.writeShort(r.resultAmount()); d.writeInt(r.exp());
                        d.writeByte(r.ingredients().size());
                        for (Stack st : r.ingredients()) { d.writeUTF(st.key()); d.writeByte(st.amount()); }
                    }
                    d.writeInt(s.locked()); d.writeUTF(s.notice());
                }
                case Result r -> {
                    d.writeByte(FRAME_RESULT); d.writeLong(r.sequence()); d.writeByte(r.outcome()); d.writeUTF(r.recipeId());
                    d.writeUTF(r.result()); d.writeShort(r.amount()); d.writeBoolean(r.mailed()); d.writeUTF(r.notice());
                }
            }
        });
    }

    public static Frame decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int type = d.readUnsignedByte();
            if (type == FRAME_STATE) {
                int kind = d.readUnsignedByte(), slots = d.readUnsignedByte();
                int n = d.readUnsignedShort(); if (n > MAX_ITEMS) throw new IOException("items");
                var items = new ArrayList<Item>();
                for (int i = 0; i < n; i++) {
                    String key = d.readUTF(); int len = d.readUnsignedShort();
                    if (len > MAX_PREVIEW || len > d.available()) throw new IOException("preview");
                    items.add(new Item(key, d.readNBytes(len)));
                }
                n = d.readUnsignedShort(); if (n > MAX_MATERIALS) throw new IOException("materials");
                var materials = new ArrayList<Material>();
                for (int i = 0; i < n; i++) materials.add(new Material(d.readUTF(), d.readInt(), d.readBoolean()));
                n = d.readUnsignedShort(); if (n > MAX_RECIPES) throw new IOException("recipes");
                var recipes = new ArrayList<Recipe>();
                for (int i = 0; i < n; i++) {
                    String id = d.readUTF(), name = d.readUTF(), result = d.readUTF(); int amount = d.readUnsignedShort(), exp = d.readInt();
                    int m = d.readUnsignedByte(); if (m > MAX_SLOTS) throw new IOException("ingredients");
                    var ingredients = new ArrayList<Stack>();
                    for (int j = 0; j < m; j++) ingredients.add(new Stack(d.readUTF(), d.readUnsignedByte()));
                    recipes.add(new Recipe(id, name, result, amount, exp, ingredients));
                }
                return new State(kind, slots, items, materials, recipes, d.readInt(), d.readUTF());
            }
            if (type == FRAME_RESULT)
                return new Result(d.readLong(), d.readUnsignedByte(), d.readUTF(), d.readUTF(), d.readUnsignedShort(), d.readBoolean(), d.readUTF());
            throw new IOException("frame");
        });
    }

    // ------------------------------------------------------------------ io

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid alchemy packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid alchemy packet", e); }
    }

    private AlchemyProtocol() {}
}
