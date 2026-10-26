package school.magiccodex.paper;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 사망 시 아공간 배낭 다른 쪽 떨어뜨리기 (오너 2026-10-26) — Bukkit 없이 시험할 수 있는 부분.
 *
 * <p>공유 DB 에서 비우기(CAS) 전에, 비울 쪽들의 원래 바이트를 서버 폴더 bag-death-drops/ 에 fsync 로 남긴다
 * ("증거 파일"). CAS 가 된 뒤 아이템을 죽은 자리에 다 떨어뜨리면 지운다. 서버가 그 사이에 멈추면 파일이 남아
 * 운영자가 확인할 수 있다 (자동으로 다시 떨어뜨리거나 되돌리지 않음 — 복제 방지).
 */
final class BagDeathDrops {
    private BagDeathDrops() {}

    /** 증거 한 건: 누구·몇 번째 전환·어디서 죽었는지·비운 쪽 바이트(지금 쪽은 빈 배열). */
    record Evidence(UUID owner, long seq, String where, byte[][] pages) {}

    /** 지금 쪽(active, 1~3)을 뺀 나머지 쪽 중 아이템이 하나라도 있는 쪽 번호(1~3). 깨진 쪽은 IllegalArgumentException. */
    static List<Integer> pagesToDrop(byte[][] pages, int active) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i < BagPageCodec.PAGES; i++) if (i != active - 1 && !BagPageCodec.isEmpty(pages[i])) out.add(i + 1);
        return out;
    }

    /** 떨어뜨린 뒤의 쪽들: 전부 빈 쪽 (지금 쪽 자리는 원래도 빈 배열 — 지금 쪽 아이템은 인벤토리에 있고 바닐라가 떨어뜨림). */
    static byte[][] emptied() { return new byte[][]{BagPageCodec.emptyPage(), BagPageCodec.emptyPage(), BagPageCodec.emptyPage()}; }

    static Path file(Path root, UUID owner, long seq) { return root.resolve(owner + "-" + seq + ".bin"); }

    static byte[] encode(Evidence e) {
        try {
            var b = new ByteArrayOutputStream(); var d = new DataOutputStream(b);
            d.writeByte(1); d.writeUTF(e.owner().toString()); d.writeLong(e.seq()); d.writeUTF(e.where() == null ? "" : e.where());
            for (int i = 0; i < BagPageCodec.PAGES; i++) { byte[] p = e.pages()[i] == null ? new byte[0] : e.pages()[i]; d.writeInt(p.length); d.write(p); }
            return b.toByteArray();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static Evidence decode(byte[] bytes) throws IOException {
        try (var d = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (d.readUnsignedByte() != 1) throw new IOException("version");
            UUID owner = UUID.fromString(d.readUTF());
            long seq = d.readLong();
            String where = d.readUTF();
            byte[][] pages = new byte[BagPageCodec.PAGES][];
            for (int i = 0; i < BagPageCodec.PAGES; i++) {
                int n = d.readInt();
                if (n < 0 || n > BagPageCodec.MAX_PAGE_BYTES || n > d.available()) throw new IOException("length");
                pages[i] = d.readNBytes(n);
                BagPageCodec.decodePage(pages[i]);
            }
            if (d.available() != 0) throw new IOException("trailing");
            return new Evidence(owner, seq, where, pages);
        } catch (RuntimeException ex) { throw new IOException("Invalid bag death evidence", ex); }
    }

    /** fsync + 원자적 이동으로 남긴다 (돌아오면 디스크에 있음). */
    static Path save(Path root, Evidence e) throws IOException {
        Files.createDirectories(root);
        Path target = file(root, e.owner(), e.seq()), tmp = Files.createTempFile(root, "drop-", ".tmp");
        try {
            Files.write(tmp, encode(e));
            try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) { ch.force(true); }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
        return target;
    }
}
