package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * Status effects (debuffs) on entities (server MagicMythic -> client MagicCodex). Display only: the server applies
 * every rule. Sent to clients that registered the channel, for entities near them (themselves included).
 *
 * v1 frame: byte version=1, byte type, int entityId, then
 *   SET:   byte count (0..MAX_ENTRIES), count x { byte status, ushort ticksLeft, ushort totalTicks }
 *          The complete current list for that entity (replaces the previous one). ticksLeft = INFINITE: no end.
 *   CLEAR: nothing (entity has no status any more)
 */
public final class StatusProtocol {
    public static final String CHANNEL = "magiccodex:status";
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 16;
    public static final int INFINITE = 0xFFFF;
    public static final int MAX_BYTES = 7 + MAX_ENTRIES * 5;

    /**
     * Order is part of the wire format. id = icon file (assets/magiccodex/textures/status/<id>.png),
     * color = ring color taken from the owner's reference sheet.
     */
    public enum Status {
        STUN("stun", "기절", 0xF2C46A),
        ROOT("root", "속박", 0x6FE3A8),
        DISARM("disarm", "무장해제", 0xF0788C),
        SLOW("slow", "둔화", 0x7FA6F2),
        BURN("burn", "화상", 0xFF8A4C),
        RECOVERY_SEAL("recovery_seal", "회복봉인", 0xF07AB4),
        MAGIC_WEAKNESS("magic_weakness", "약화", 0xC88CFF),
        GRAVITY("gravity", "중력", 0x8FA8FF),
        MOVEMENT_LOCK("movement_lock", "고정", 0x52DDE8),
        POISON("poison", "독", 0x8EE87A),
        PHYSICAL_WEAKNESS("physical_weakness", "나약함", 0xF2C46A),
        BLINDNESS("blindness", "실명", 0xC88CFF),
        POTION_SEAL("potion_seal", "물약봉인", 0x52DDE8),
        MANA_BURN("mana_burn", "마나번", 0xB98CFF),
        SLEEP("sleep", "수면", 0xB98CFF),
        FEAR("fear", "공포", 0xCB9EFB);

        public final String id, label;
        public final int color;
        Status(String id, String label, int color) { this.id = id; this.label = label; this.color = color; }
        public static Status of(String raw) {
            if (raw == null) return null;
            String v = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Status s : values()) if (s.id.equals(v) || s.name().equalsIgnoreCase(v) || s.label.equals(raw.trim())) return s;
            return null;
        }
    }

    public record Entry(Status status, int ticksLeft, int totalTicks) {
        public Entry {
            if (status == null || ticksLeft < 0 || ticksLeft > INFINITE || totalTicks < 0 || totalTicks > INFINITE) throw new IllegalArgumentException("status");
        }
        public boolean infinite() { return ticksLeft == INFINITE; }
    }

    public sealed interface Frame permits Set, Clear {}
    public record Set(int entityId, List<Entry> entries) implements Frame {
        public Set {
            entries = List.copyOf(entries);
            if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("entries");
        }
    }
    public record Clear(int entityId) implements Frame {}

    private StatusProtocol() {}

    public static int clampTicks(long ticks) { return (int) Math.clamp(ticks, 0, INFINITE - 1); }

    public static byte[] encode(Frame frame) {
        try {
            var b = new ByteArrayOutputStream(32);
            var o = new DataOutputStream(b);
            o.writeByte(VERSION);
            switch (frame) {
                case Set s -> {
                    o.writeByte(0); o.writeInt(s.entityId()); o.writeByte(s.entries().size());
                    for (Entry e : s.entries()) { o.writeByte(e.status().ordinal()); o.writeShort(e.ticksLeft()); o.writeShort(e.totalTicks()); }
                }
                case Clear c -> { o.writeByte(1); o.writeInt(c.entityId()); }
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
                case 0 -> {
                    int n = i.readUnsignedByte();
                    if (n > MAX_ENTRIES) throw new IOException("count");
                    var list = new ArrayList<Entry>(n);
                    for (int k = 0; k < n; k++) {
                        int s = i.readUnsignedByte();
                        if (s >= Status.values().length) throw new IOException("status");
                        list.add(new Entry(Status.values()[s], i.readUnsignedShort(), i.readUnsignedShort()));
                    }
                    yield new Set(id, list);
                }
                case 1 -> new Clear(id);
                default -> throw new IOException("type");
            };
            if (i.available() != 0) throw new IOException("trailing");
            return f;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
}
