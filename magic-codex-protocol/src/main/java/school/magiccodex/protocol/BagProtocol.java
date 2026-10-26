package school.magiccodex.protocol;

import java.io.*;

/**
 * 아공간 배낭 pages (2026-10-24). The bag shows 27 main slots per page; page 1 is the vanilla inventory, pages 2..3
 * are opened with the 가방 확장권 item. Switching a page swaps the 27 main slots with the page stored on the server
 * (hotbar never moves). Only the wanted page number crosses the trust boundary; the server decides everything.
 */
public final class BagProtocol {
    private BagProtocol() {}

    public static final String REQUEST = "magiccodex:bag_request", RESPONSE = "magiccodex:bag_response";
    public static final int MAX_BYTES = 1024, PAGES = 3, MAX_NOTICE = 120;
    public static final int HELLO = 0, SWITCH = 1;

    /** action HELLO (page ignored, 0) or SWITCH to page 1..PAGES. */
    public record Request(int action, int page) {
        public Request {
            if (action != HELLO && action != SWITCH) throw new IllegalArgumentException("Invalid bag action");
            if (action == SWITCH ? page < 1 || page > PAGES : page != 0) throw new IllegalArgumentException("Invalid bag page");
        }
    }

    /**
     * unlocked: pages the player may use (1..PAGES). active: page whose items are in the inventory now.
     * ready=false while the server is still reading the player's pages (switching refused).
     */
    public record State(int unlocked, int active, boolean ready, String notice) {
        public State {
            if (unlocked < 1 || unlocked > PAGES || active < 1 || active > PAGES) throw new IllegalArgumentException("Invalid bag state");
            notice = notice == null ? "" : notice;
            if (notice.length() > MAX_NOTICE) throw new IllegalArgumentException("Bag notice too long");
        }
    }

    public static byte[] encode(Request r) {
        return write(d -> { d.writeByte(1); d.writeByte(r.action()); d.writeByte(r.page()); });
    }

    public static Request decodeRequest(byte[] b) {
        return read(b, d -> { if (d.readUnsignedByte() != 1) throw new IOException(); return new Request(d.readUnsignedByte(), d.readUnsignedByte()); });
    }

    public static byte[] encode(State s) {
        return write(d -> { d.writeByte(1); d.writeByte(s.unlocked()); d.writeByte(s.active()); d.writeBoolean(s.ready()); d.writeUTF(s.notice()); });
    }

    public static State decodeState(byte[] b) {
        return read(b, d -> {
            if (d.readUnsignedByte() != 1) throw new IOException();
            int unlocked = d.readUnsignedByte(), active = d.readUnsignedByte();
            boolean ready = d.readBoolean();
            return new State(unlocked, active, ready, d.readUTF());
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }

    private static byte[] write(Writer w) {
        try {
            var b = new ByteArrayOutputStream();
            w.run(new DataOutputStream(b));
            if (b.size() > MAX_BYTES) throw new IOException();
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("Invalid bag packet", e); }
    }

    private static <T> T read(byte[] b, Reader<T> r) {
        try {
            if (b == null || b.length > MAX_BYTES) throw new IOException();
            var d = new DataInputStream(new ByteArrayInputStream(b));
            T out = r.run(d);
            if (d.available() != 0) throw new IOException();
            return out;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid bag packet", e); }
    }
}
