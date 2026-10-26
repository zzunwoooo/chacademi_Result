package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 책 읽기 창 (2026-10-23). The server opens a book (linked block right click or /컨텐츠관리 책 열기) and sends its text;
 * the client only shows it. The title, the page text and the page number are drawn by the mod (never baked in a PNG).
 * One page = one open spread (left side + right side).
 *
 * Closing (X button or ESC, owner 2026-10-23: 열고 닫기만 해도 읽음, ESC·X 동일) sends CLOSE with the token the server
 * gave on OPEN. The client never names a book or a command: the server looks the token up in its own open session,
 * and runs only the commands configured for that book, at most once per player and book (shared DB).
 *
 * S2C  int version, byte FRAME_OPEN, long token, UTF title, short n, n x (UTF left, UTF right)
 * C2S  int version, byte CLOSE, long token
 */
public final class BookProtocol {
    public static final String REQUEST = "magiccodex:book_request", RESPONSE = "magiccodex:book_response";
    public static final int VERSION = 1;
    public static final int CLOSE = 1;
    public static final int FRAME_OPEN = 1;
    public static final int MAX_PAGES = 40, MAX_TITLE = 40, MAX_SIDE = 1000;
    public static final int MAX_REQUEST_BYTES = 64, MAX_BYTES = 262144;

    /** Book ids (books.yml keys, book-blocks.yml values, DB rows): lower case ascii. */
    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_\\-]{1,48}"); }

    public record Page(String left, String right) {
        public Page {
            left = left == null ? "" : left; right = right == null ? "" : right;
            if (left.length() > MAX_SIDE || right.length() > MAX_SIDE) throw new IllegalArgumentException("book page too long");
        }
    }
    public record Open(long token, String title, List<Page> pages) {
        public Open {
            if (token == 0 || title == null || title.length() > MAX_TITLE || pages == null || pages.isEmpty() || pages.size() > MAX_PAGES)
                throw new IllegalArgumentException("book open");
            pages = List.copyOf(pages);
        }
    }
    public record Close(long token) {
        public Close { if (token == 0) throw new IllegalArgumentException("book close"); }
    }

    // ------------------------------------------------------------------ codec

    public static byte[] encode(Open o) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(FRAME_OPEN); d.writeLong(o.token()); d.writeUTF(o.title());
            d.writeShort(o.pages().size());
            for (Page p : o.pages()) { d.writeUTF(p.left()); d.writeUTF(p.right()); }
        });
    }
    public static Open decode(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            if (d.readUnsignedByte() != FRAME_OPEN) throw new IOException("frame");
            long token = d.readLong(); String title = d.readUTF();
            int n = d.readUnsignedShort(); if (n < 1 || n > MAX_PAGES) throw new IOException("pages");
            var pages = new ArrayList<Page>(n);
            for (int i = 0; i < n; i++) pages.add(new Page(d.readUTF(), d.readUTF()));
            return new Open(token, title, pages);
        });
    }

    public static byte[] request(Close c) {
        return write(MAX_REQUEST_BYTES, d -> { d.writeInt(VERSION); d.writeByte(CLOSE); d.writeLong(c.token()); });
    }
    public static Close request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            if (d.readUnsignedByte() != CLOSE) throw new IOException("action");
            return new Close(d.readLong());
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid book packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid book packet", e); }
    }

    private BookProtocol() {}
}
