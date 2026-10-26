package school.magiccodex.protocol;

import java.io.*;
import java.util.Locale;

/**
 * Element marks above mobs (server MagicMythic -> client MagicCodex). Display only: the server decides every reaction.
 * Channel is S2C only and sent only to clients that registered it (vanilla clients never receive it).
 *
 * v1 frame: byte version=1, byte type, int entityId, then
 *   MARK:     byte element, short ticksLeft      (shows / refreshes the icon)
 *   CLEAR:    nothing                            (icon removed: expired, consumed or entity gone)
 *   REACTION: byte reaction                      (short name pop-up; the icon is cleared separately)
 */
public final class ElementProtocol {
    public static final String CHANNEL = "magiccodex:element";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 16;

    /** Order is part of the wire format. Ids match the spell catalogue `category`. */
    public enum Element {
        FIRE("fire", "화염"), WATER("water", "물"), WIND("wind", "바람"), EARTH("earth", "대지"), LIGHT("light", "빛"), DARK("dark", "어둠");
        public final String id, label;
        Element(String id, String label) { this.id = id; this.label = label; }
        public static Element of(String raw) {
            if (raw == null) return null;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            for (Element e : values()) if (e.id.equals(v)) return e;
            return null;
        }
    }

    /** One per unordered element pair. Order is part of the wire format. */
    public enum Reaction {
        STEAM(Element.FIRE, Element.WATER, "증기 폭발"),
        WILDFIRE(Element.FIRE, Element.WIND, "불씨 확산"),
        MAGMA(Element.FIRE, Element.EARTH, "용암 분출"),
        FLARE(Element.FIRE, Element.LIGHT, "태양 플레어"),
        EMBER_CURSE(Element.FIRE, Element.DARK, "잿불 낙인"),
        SHOCK(Element.WATER, Element.WIND, "연쇄 번개"),
        MUD(Element.WATER, Element.EARTH, "진흙"),
        RAINBOW(Element.WATER, Element.LIGHT, "무지개"),
        ABYSS(Element.WATER, Element.DARK, "심연"),
        SANDSTORM(Element.WIND, Element.EARTH, "모래 회오리"),
        FLASH(Element.WIND, Element.LIGHT, "섬광"),
        VOID_GUST(Element.WIND, Element.DARK, "공허 균열"),
        CRYSTAL(Element.EARTH, Element.LIGHT, "수정화"),
        BIND(Element.EARTH, Element.DARK, "매장"),
        ECLIPSE(Element.LIGHT, Element.DARK, "일식");
        public final Element a, b;
        public final String label;
        Reaction(Element a, Element b, String label) { this.a = a; this.b = b; this.label = label; }
        /** Null for the same element (no reaction). */
        public static Reaction of(Element x, Element y) {
            if (x == null || y == null || x == y) return null;
            for (Reaction r : values()) if ((r.a == x && r.b == y) || (r.a == y && r.b == x)) return r;
            return null;
        }
    }

    public sealed interface Frame permits Mark, Clear, Pop {}
    public record Mark(int entityId, Element element, int ticksLeft) implements Frame {
        public Mark { if (element == null || ticksLeft < 0 || ticksLeft > Short.MAX_VALUE) throw new IllegalArgumentException("mark"); }
    }
    public record Clear(int entityId) implements Frame {}
    public record Pop(int entityId, Reaction reaction) implements Frame {
        public Pop { if (reaction == null) throw new IllegalArgumentException("reaction"); }
    }

    private ElementProtocol() {}

    public static byte[] encode(Frame frame) {
        try {
            var b = new ByteArrayOutputStream(12);
            var o = new DataOutputStream(b);
            o.writeByte(VERSION);
            switch (frame) {
                case Mark m -> { o.writeByte(0); o.writeInt(m.entityId()); o.writeByte(m.element().ordinal()); o.writeShort(m.ticksLeft()); }
                case Clear c -> { o.writeByte(1); o.writeInt(c.entityId()); }
                case Pop p -> { o.writeByte(2); o.writeInt(p.entityId()); o.writeByte(p.reaction().ordinal()); }
            }
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Frame decode(byte[] raw) {
        try {
            if (raw == null || raw.length > MAX_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readUnsignedByte() != VERSION) throw new IOException("version");
            int type = i.readUnsignedByte(), id = i.readInt();
            Frame f = switch (type) {
                case 0 -> new Mark(id, pick(Element.values(), i.readUnsignedByte()), i.readUnsignedShort());
                case 1 -> new Clear(id);
                case 2 -> new Pop(id, pick(Reaction.values(), i.readUnsignedByte()));
                default -> throw new IOException("type");
            };
            if (i.available() != 0) throw new IOException("trailing");
            return f;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }

    private static <T> T pick(T[] values, int index) throws IOException {
        if (index < 0 || index >= values.length) throw new IOException("index");
        return values[index];
    }
}
