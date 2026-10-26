package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * Target health HUD (server MagicCodexBridge -> client MagicCodex). The server picks the targets and sends what
 * the HUD shows. Up to two bars, top first (owner 2026-10-19):
 *  - a boss is near: [boss, the normal mob under the crosshair (optional)] - the boss stays on top even while
 *    fighting normal mobs;
 *  - otherwise: [last combat partner for 10 s, else the entity under the crosshair].
 * Status icons are not in here: the client takes them from {@link StatusProtocol} / {@link ElementProtocol}
 * by entity id.
 *
 * v1 frame: byte version=1, byte count (0..MAX_TARGETS, 0 = hide), count x SHOW
 *   SHOW: int entityId, long uuidMost, long uuidLeast, byte kind, byte flags, UTF name, UTF translationKey,
 *         float health, float maxHealth, float mana (-1 = no mana bar), float maxMana,
 *         float damageDealt (by the receiving player to this target), float tameChance (0..1, -1 = hidden)
 */
public final class TargetHudProtocol {
    public static final String CHANNEL = "magiccodex:target_hud";
    /**
     * Client -> server aim ray (owner 2026-10-19: "띄우는 건 내 에임 기준, 플레이어 시선 말고"). The shoulder camera sits
     * beside the head, so the bar must follow the crosshair ray, not the head ray.
     * v1: byte version=1, float dx, dy, dz (camera position minus the player's eyes, blocks), float yaw, float pitch (degrees).
     * Sent on change (at most every 2 ticks) and every second otherwise; the server falls back to the eye ray when it is stale.
     */
    public static final String AIM_CHANNEL = "magiccodex:target_aim";
    public static final int AIM_BYTES = 21;
    /** Largest accepted camera offset from the eyes (shoulder camera distance is well inside this). */
    public static final float MAX_AIM_OFFSET = 8;
    public record Aim(float dx, float dy, float dz, float yaw, float pitch) {
        public Aim {
            if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || dx * dx + dy * dy + dz * dz > MAX_AIM_OFFSET * MAX_AIM_OFFSET || Math.abs(pitch) > 90.001f) throw new IllegalArgumentException("aim");
        }
    }
    public static byte[] encodeAim(Aim a) {
        try {
            var b = new ByteArrayOutputStream(AIM_BYTES); var o = new DataOutputStream(b);
            o.writeByte(VERSION); o.writeFloat(a.dx()); o.writeFloat(a.dy()); o.writeFloat(a.dz()); o.writeFloat(a.yaw()); o.writeFloat(a.pitch());
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    public static Aim decodeAim(byte[] raw) {
        try {
            if (raw == null || raw.length != AIM_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readUnsignedByte() != VERSION) throw new IOException("version");
            return new Aim(i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat());
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 512;
    public static final int MAX_NAME = 48;
    public static final int MAX_TARGETS = 2;

    public static final int KIND_VANILLA = 0, KIND_CUSTOM = 1, KIND_PLAYER = 2;
    public static final int FLAG_SHINY = 1, FLAG_BOSS = 2, FLAG_COMBAT = 4;

    /** The bars to show, top first. Empty = hide. */
    public record Frame(List<Show> targets) {
        public Frame {
            targets = List.copyOf(targets);
            if (targets.size() > MAX_TARGETS) throw new IllegalArgumentException("targets");
        }
        public static Frame hide() { return new Frame(List.of()); }
        public boolean empty() { return targets.isEmpty(); }
    }
    public record Show(int entityId, UUID uuid, int kind, int flags, String name, String translationKey,
                       float health, float maxHealth, float mana, float maxMana, float damage, float tameChance) {
        public Show {
            if (uuid == null || kind < 0 || kind > 2 || flags < 0 || flags > 255) throw new IllegalArgumentException("target");
            name = name == null ? "" : name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
            translationKey = translationKey == null ? "" : translationKey.length() > 96 ? "" : translationKey;
            if (!Float.isFinite(health) || !Float.isFinite(maxHealth) || maxHealth <= 0) throw new IllegalArgumentException("health");
            health = Math.clamp(health, 0, maxHealth);
            if (!Float.isFinite(mana) || !Float.isFinite(maxMana)) throw new IllegalArgumentException("mana");
            if (!Float.isFinite(damage) || damage < 0) damage = 0;
            if (!Float.isFinite(tameChance)) tameChance = -1;
            tameChance = tameChance < 0 ? -1 : Math.min(1, tameChance);
        }
        public boolean shiny() { return (flags & FLAG_SHINY) != 0; }
        public boolean boss() { return (flags & FLAG_BOSS) != 0; }
        public boolean combat() { return (flags & FLAG_COMBAT) != 0; }
        public boolean hasMana() { return mana >= 0 && maxMana > 0; }
    }

    private TargetHudProtocol() {}

    public static byte[] encode(Frame frame) {
        try {
            var b = new ByteArrayOutputStream(96);
            var o = new DataOutputStream(b);
            o.writeByte(VERSION);
            o.writeByte(frame.targets().size());
            for (Show s : frame.targets()) {
                o.writeInt(s.entityId());
                o.writeLong(s.uuid().getMostSignificantBits()); o.writeLong(s.uuid().getLeastSignificantBits());
                o.writeByte(s.kind()); o.writeByte(s.flags()); o.writeUTF(s.name()); o.writeUTF(s.translationKey());
                o.writeFloat(s.health()); o.writeFloat(s.maxHealth()); o.writeFloat(s.mana()); o.writeFloat(s.maxMana());
                o.writeFloat(s.damage()); o.writeFloat(s.tameChance());
            }
            if (b.size() > MAX_BYTES) throw new IOException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Frame decode(byte[] raw) {
        try {
            if (raw == null || raw.length > MAX_BYTES) throw new IOException("size");
            var i = new DataInputStream(new ByteArrayInputStream(raw));
            if (i.readUnsignedByte() != VERSION) throw new IOException("version");
            int n = i.readUnsignedByte();
            if (n > MAX_TARGETS) throw new IOException("count");
            var list = new ArrayList<Show>(n);
            for (int k = 0; k < n; k++)
                list.add(new Show(i.readInt(), new UUID(i.readLong(), i.readLong()), i.readUnsignedByte(), i.readUnsignedByte(),
                    i.readUTF(), i.readUTF(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat()));
            Frame f = new Frame(list);
            if (i.available() != 0) throw new IOException("trailing");
            return f;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
}
