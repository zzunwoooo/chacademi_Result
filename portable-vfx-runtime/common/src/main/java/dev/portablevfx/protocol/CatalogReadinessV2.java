package dev.portablevfx.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded atomic readiness inventory, split below Minecraft's 32767-byte C2S ceiling. */
public final class CatalogReadinessV2 {
    public static final String CHANNEL = "portablevfx:catalog_ready_v2";
    public static final String EPOCH_CHANNEL = "portablevfx:catalog_epoch";
    public static final int VERSION = 2, MAX_EFFECTS = 4096, MAX_TOTAL_BYTES = 524288;
    public static final int HEADER_BYTES = 72, CHUNK_DATA_BYTES = 28000;
    public static final int MAX_PACKET_BYTES = HEADER_BYTES + CHUNK_DATA_BYTES;
    public static final int MAX_PARTS = (MAX_TOTAL_BYTES + CHUNK_DATA_BYTES - 1) / CHUNK_DATA_BYTES;
    public static final long TIMEOUT_TICKS = 200;
    private CatalogReadinessV2() { }

    public static byte[] encodeEpoch(UUID epoch) {
        if (epoch == null) throw new IllegalArgumentException("Epoch is required");
        return java.nio.ByteBuffer.allocate(16).putLong(epoch.getMostSignificantBits()).putLong(epoch.getLeastSignificantBits()).array();
    }
    public static UUID decodeEpoch(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length != 16) throw new ProtocolException("Invalid readiness epoch size");
        var b = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }
    public static List<byte[]> encode(UUID epoch, long transfer, Collection<String> ids) {
        if (epoch == null || transfer <= 0) throw new IllegalArgumentException("Epoch and positive transfer are required");
        byte[] inventory = encodeInventory(ids), digest = digest(inventory);
        int parts = parts(inventory.length);
        var result = new ArrayList<byte[]>(parts);
        try {
            for (int index = 0; index < parts; index++) {
                int offset = index * CHUNK_DATA_BYTES, length = Math.min(CHUNK_DATA_BYTES, inventory.length - offset);
                var bytes = new ByteArrayOutputStream(HEADER_BYTES + length);
                var out = new DataOutputStream(bytes);
                out.writeInt(VERSION); out.writeLong(epoch.getMostSignificantBits()); out.writeLong(epoch.getLeastSignificantBits());
                out.writeLong(transfer); out.writeShort(index); out.writeShort(parts);
                out.writeInt(inventory.length); out.writeInt(ids.size()); out.write(digest); out.write(inventory, offset, length);
                out.flush(); result.add(bytes.toByteArray());
            }
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return List.copyOf(result);
    }
    private static byte[] encodeInventory(Collection<String> ids) {
        if (ids == null || ids.size() > MAX_EFFECTS) throw new IllegalArgumentException("Invalid ready effect count");
        var sorted = new TreeSet<String>();
        int size = 4;
        for (String id : ids) {
            readyId(id);
            if (!sorted.add(id)) throw new IllegalArgumentException("Duplicate ready effect");
            size += 1 + id.length();
            if (size > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Ready inventory exceeds total budget");
        }
        try {
            var bytes = new ByteArrayOutputStream(size); var out = new DataOutputStream(bytes); out.writeInt(sorted.size());
            for (String id : sorted) { byte[] raw = id.getBytes(StandardCharsets.US_ASCII); out.writeByte(raw.length); out.write(raw); }
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void readyId(String id) {
        VfxProtocol.validateId(id);
        if (!id.startsWith("claude:")) throw new IllegalArgumentException("Ready effects must be Claude IDs");
    }
    private static Set<String> decodeInventory(byte[] bytes, int expectedCount) throws ProtocolException {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes)); int count = in.readInt();
            if (count < 0 || count > MAX_EFFECTS || count != expectedCount) throw new ProtocolException("Readiness count mismatch");
            var ids = new TreeSet<String>(); String previous = "";
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedByte();
                if (length < 1 || length > VfxProtocol.MAX_ID_BYTES) throw new ProtocolException("Invalid readiness ID size");
                byte[] raw = new byte[length]; in.readFully(raw);
                for (byte value : raw) if (value < 0) throw new ProtocolException("Non-ASCII readiness ID");
                String id = new String(raw, StandardCharsets.US_ASCII); readyId(id);
                if (id.compareTo(previous) <= 0) throw new ProtocolException("Readiness IDs must be sorted and unique");
                ids.add(id); previous = id;
            }
            if (in.available() != 0) throw new ProtocolException("Trailing readiness inventory data");
            return Set.copyOf(ids);
        } catch (ProtocolException error) { throw error; }
        catch (IOException | IllegalArgumentException error) { throw new ProtocolException("Malformed readiness inventory", error); }
    }
    private static int parts(int bytes) { return (bytes + CHUNK_DATA_BYTES - 1) / CHUNK_DATA_BYTES; }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Frame(UUID epoch, long transfer, int index, int parts, int total, int effects, byte[] digest, byte[] data) { }
    private static Frame frame(byte[] packet) throws ProtocolException {
        if (packet == null || packet.length < HEADER_BYTES + 1 || packet.length > MAX_PACKET_BYTES)
            throw new ProtocolException("Invalid readiness chunk size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(packet));
            if (in.readInt() != VERSION) throw new ProtocolException("Unsupported readiness chunk version");
            UUID epoch = new UUID(in.readLong(), in.readLong()); long transfer = in.readLong();
            int index = in.readUnsignedShort(), parts = in.readUnsignedShort(), total = in.readInt(), effects = in.readInt();
            if (transfer <= 0 || total < 4 || total > MAX_TOTAL_BYTES || effects < 0 || effects > MAX_EFFECTS
                    || parts < 1 || parts > MAX_PARTS || parts != parts(total) || index >= parts)
                throw new ProtocolException("Invalid readiness chunk bounds");
            int length = Math.min(CHUNK_DATA_BYTES, total - index * CHUNK_DATA_BYTES);
            if (packet.length != HEADER_BYTES + length) throw new ProtocolException("Readiness chunk length mismatch");
            byte[] digest = new byte[32]; in.readFully(digest); byte[] data = new byte[length]; in.readFully(data);
            if (in.available() != 0) throw new ProtocolException("Trailing readiness chunk data");
            return new Frame(epoch, transfer, index, parts, total, effects, digest, data);
        } catch (ProtocolException error) { throw error; }
        catch (IOException error) { throw new ProtocolException("Truncated readiness chunk", error); }
    }
    /** complete is null until every part of one validated transfer has arrived. */
    public record Outcome(boolean started, Set<String> complete) { }
    private static final Outcome IGNORED = new Outcome(false, null);

    /** One instance per authenticated player connection and server-issued catalog epoch. Not thread safe. */
    public static final class Receiver {
        private final UUID epoch;
        private long highestTransfer;
        private Assembly assembly;
        public Receiver(UUID epoch) { this.epoch = Objects.requireNonNull(epoch, "epoch"); }
        public boolean expire(long nowTick) {
            if (assembly != null && (nowTick < assembly.startedTick || nowTick - assembly.startedTick >= TIMEOUT_TICKS)) {
                assembly = null; return true;
            }
            return false;
        }
        public Outcome accept(byte[] packet, long nowTick) throws ProtocolException {
            try {
                if (nowTick < 0) throw new ProtocolException("Invalid readiness clock");
                expire(nowTick);
                Frame frame = frame(packet);
                if (!epoch.equals(frame.epoch)) throw new ProtocolException("Stale readiness epoch");
                if (frame.transfer < highestTransfer || frame.transfer == highestTransfer && assembly == null) return IGNORED;
                boolean started = frame.transfer > highestTransfer;
                if (started) {
                    highestTransfer = frame.transfer; assembly = new Assembly(frame, nowTick);
                }
                Assembly current = assembly;
                if (current == null) return IGNORED;
                if (current.parts != frame.parts || current.total != frame.total || current.effects != frame.effects
                        || !MessageDigest.isEqual(current.digest, frame.digest))
                    throw new ProtocolException("Conflicting readiness transfer metadata");
                int offset = frame.index * CHUNK_DATA_BYTES;
                if (current.received.get(frame.index)) {
                    for (int i = 0; i < frame.data.length; i++)
                        if (current.data[offset + i] != frame.data[i]) throw new ProtocolException("Conflicting readiness duplicate");
                    return IGNORED;
                }
                System.arraycopy(frame.data, 0, current.data, offset, frame.data.length);
                current.received.set(frame.index);
                if (current.received.cardinality() != current.parts) return new Outcome(started, null);
                if (!MessageDigest.isEqual(current.digest, digest(current.data))) throw new ProtocolException("Readiness digest mismatch");
                Set<String> complete = decodeInventory(current.data, current.effects); assembly = null;
                return new Outcome(started, complete);
            } catch (ProtocolException error) { assembly = null; throw error; }
        }
        private static final class Assembly {
            final int parts, total, effects;
            final byte[] digest, data;
            final BitSet received;
            final long startedTick;
            Assembly(Frame frame, long startedTick) {
                parts = frame.parts; total = frame.total; effects = frame.effects; digest = frame.digest.clone();
                data = new byte[total]; received = new BitSet(parts); this.startedTick = startedTick;
            }
        }
    }
}
