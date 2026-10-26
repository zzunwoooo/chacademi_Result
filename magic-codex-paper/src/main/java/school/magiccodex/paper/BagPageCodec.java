package school.magiccodex.paper;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 아공간 배낭 page bytes and the write-ahead journal (2026-10-24). No Bukkit types, so it is unit-tested.
 *
 * A page is 27 slots; each slot is the Paper {@code ItemStack#serializeAsBytes()} bytes or empty for air.
 * The journal keeps the newest page state of a player on this server until the database has it, so a crash or a
 * database outage. Version 2 retains the target page and pairs it with a persistent player commit marker;
 * sharing a tick does not make the journal and player file atomic.
 */
final class BagPageCodec {
    private BagPageCodec() {}

    static final int SLOTS = 27, PAGES = 3;
    /** Upper bound for one page (27 shulker boxes full of written books stays far below this). */
    static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;

    static byte[] encodePage(List<byte[]> slots) {
        if (slots.size() != SLOTS) throw new IllegalArgumentException("A bag page has 27 slots");
        try {
            var out = new ByteArrayOutputStream();
            var d = new DataOutputStream(out);
            d.writeByte(1);
            d.writeByte(SLOTS);
            for (byte[] b : slots) { byte[] v = b == null ? new byte[0] : b; d.writeInt(v.length); d.write(v); }
            if (out.size() > MAX_PAGE_BYTES) throw new IllegalArgumentException("Bag page too large");
            return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Empty page bytes (never stored as null). */
    static byte[] emptyPage() { return new byte[0]; }

    static boolean isEmpty(byte[] page) {
        if (page == null || page.length == 0) return true;
        for (byte[] b : decodePage(page)) if (b.length > 0) return false;
        return true;
    }

    /** 27 entries; an empty array means air. Throws IllegalArgumentException for damaged bytes. */
    static List<byte[]> decodePage(byte[] page) {
        var out = new ArrayList<byte[]>(SLOTS);
        if (page == null || page.length == 0) { for (int i = 0; i < SLOTS; i++) out.add(new byte[0]); return out; }
        try {
            var d = new DataInputStream(new ByteArrayInputStream(page));
            if (d.readUnsignedByte() != 1 || d.readUnsignedByte() != SLOTS) throw new IOException("version");
            for (int i = 0; i < SLOTS; i++) {
                int n = d.readInt();
                if (n < 0 || n > d.available()) throw new IOException("length");
                out.add(d.readNBytes(n));
            }
            if (d.available() != 0) throw new IOException("trailing");
            return out;
        } catch (IOException e) { throw new IllegalArgumentException("Damaged bag page", e); }
    }

    static int itemCount(byte[] page) {
        int n = 0;
        for (byte[] b : decodePage(page)) if (b.length > 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ journal

    record Entry(UUID owner, String server, int active, long seq, byte[][] pages, byte[] incoming, Path file) { boolean recoverable() { return incoming != null; } }

    static final class Journal {
        private final Path root;
        Journal(Path root) throws IOException { this.root = root; Files.createDirectories(root); }

        private Path file(UUID owner, String server) { return root.resolve(owner + "_" + server + ".bin"); }

        /** Durable before returning (fsync + atomic move). Main thread; a page is small. */
        synchronized void save(UUID owner, String server, int active, long seq, byte[][] pages) throws IOException {
            save(owner, server, active, seq, pages, null);
        }

        /** Version 2 retains the incoming page until a later switch supersedes it. */
        synchronized void save(UUID owner, String server, int active, long seq, byte[][] pages, byte[] incoming) throws IOException {
            var bytes = new ByteArrayOutputStream();
            try (var d = new DataOutputStream(bytes)) {
                d.writeByte(incoming == null ? 1 : 2); d.writeUTF(owner.toString()); d.writeUTF(server); d.writeByte(active); d.writeLong(seq);
                for (int i = 0; i < PAGES; i++) { byte[] p = pages[i] == null ? new byte[0] : pages[i]; d.writeInt(p.length); d.write(p); }
            }
            if (incoming != null) {
                if (incoming.length > MAX_PAGE_BYTES) throw new IOException("Incoming page too large");
                try (var d = new DataOutputStream(bytes)) { d.writeInt(incoming.length); d.write(incoming); }
            }
            Path target = file(owner, server), tmp = Files.createTempFile(root, "bag-", ".tmp");
            try {
                Files.write(tmp, bytes.toByteArray());
                try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) { ch.force(true); }
                try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
        }

        synchronized Entry read(UUID owner, String server) throws IOException {
            Path f = file(owner, server);
            return Files.exists(f) ? read(f) : null;
        }

        private Entry read(Path f) throws IOException {
            if (Files.isSymbolicLink(f) || Files.size(f) > (PAGES + 1) * (long) MAX_PAGE_BYTES + 512) throw new IOException("Invalid bag journal file");
            try (var d = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(f)))) {
                int version = d.readUnsignedByte();
                if (version != 1 && version != 2) throw new IOException("version");
                UUID owner = UUID.fromString(d.readUTF());
                String server = d.readUTF();
                int active = d.readUnsignedByte();
                long seq = d.readLong();
                byte[][] pages = new byte[PAGES][];
                for (int i = 0; i < PAGES; i++) { int n = d.readInt(); if (n < 0 || n > d.available()) throw new IOException("length"); pages[i] = d.readNBytes(n); }
                if (active < 1 || active > PAGES || !f.getFileName().toString().equals(owner + "_" + server + ".bin")) throw new IOException("record");
                byte[] incoming = null;
                if (version == 2) { int n = d.readInt(); if (n < 0 || n > MAX_PAGE_BYTES || n > d.available()) throw new IOException("incoming length"); incoming = d.readNBytes(n); decodePage(incoming); }
                if (d.available() != 0) throw new IOException("trailing");
                return new Entry(owner, server, active, seq, pages, incoming, f);
            } catch (RuntimeException e) { throw new IOException("Invalid bag journal record", e); }
        }

        synchronized List<Entry> pending() throws IOException {
            var out = new ArrayList<Entry>();
            try (var files = Files.newDirectoryStream(root, "*.bin")) { for (Path f : files) out.add(read(f)); }
            return out;
        }

        /** Removes the record only when it is still the one that was stored (a newer switch keeps its own). */
        synchronized void acknowledge(UUID owner, String server, long seq) throws IOException {
            Entry e = read(owner, server);
            if (e != null && !e.recoverable() && e.seq() == seq) Files.deleteIfExists(e.file());
        }
    }
}
