package school.magiccodex.protocol;

import java.io.*;

/**
 * 확성기 (오픈 전 메모 #102, 오너 2026-10-26).
 *
 * <p>흐름: 확성기(ItemsAdder item:megaphone)를 손에 들고 우클릭 → 서버가 OPEN(토큰) → 클라이언트가 입력 창을 연다 →
 * 보내기 = SEND(토큰, 문장) / 취소 = CANCEL(토큰). 서버가 검사·1개 소모 후 모든 서버의 접속자에게 SHOW 를 보낸다
 * (화면 위쪽 공지 카드, 반대 손 아이템이 있으면 카드 위 가운데에 아이콘 + Ctrl 커서로 툴팁).
 * 클라이언트는 아이템을 고르거나 보내지 않는다: 아이템은 서버가 보낸 사람 손에서 직접 읽어 직렬화한다.
 *
 * <pre>
 * S2C  int version, byte OPEN,  long token, short maxChars
 * S2C  int version, byte SHOW,  UTF id, UTF sender, UTF text, int durationMs, int itemLength, itemLength bytes (Paper serializeAsBytes, 0 = 아이템 없음)
 * S2C  int version, byte CLOSE, long token
 * C2S  int version, byte SEND,  long token, UTF text
 * C2S  int version, byte CANCEL, long token
 * </pre>
 */
public final class MegaphoneProtocol {
    public static final String REQUEST = "magiccodex:megaphone_request", RESPONSE = "magiccodex:megaphone_response";
    public static final int VERSION = 1;
    public static final int OPEN = 1, SHOW = 2, CLOSE = 3;
    public static final int SEND = 1, CANCEL = 2;
    /** 문장 최대 글자 수 (설정 max-length 는 이 안에서). */
    public static final int MAX_TEXT = 200, MAX_SENDER = 32, MAX_ID = 64;
    /**
     * 아이템 데이터 상한. 상점 미리보기(ShopProtocol.MAX_PREVIEW_BYTES 32768)와 같은 방식이지만 절반:
     * 서버 간 전달(Velocity BungeeCord Forward)은 받는 서버에 "클라이언트 → 서버" 플러그인 메시지로 들어가 32767 바이트 제한을 받는다.
     */
    public static final int MAX_ITEM_BYTES = 16384;
    public static final int MAX_BYTES = MAX_ITEM_BYTES + 2048, MAX_REQUEST_BYTES = 1024;
    public static final int MIN_DURATION_MS = 2000, MAX_DURATION_MS = 30000;

    public record Open(long token, int maxChars) {
        public Open { if (token == 0 || maxChars < 1 || maxChars > MAX_TEXT) throw new IllegalArgumentException("megaphone open"); }
    }
    public record Show(String id, String sender, String text, int durationMs, byte[] item) {
        public Show {
            if (id == null || id.isEmpty() || id.length() > MAX_ID || sender == null || sender.length() > MAX_SENDER
                    || text == null || text.isEmpty() || text.length() > MAX_TEXT
                    || durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) throw new IllegalArgumentException("megaphone show");
            item = item == null ? new byte[0] : item;
            if (item.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("megaphone item");
        }
    }
    /** S2C 한 프레임: kind 에 따라 open / show / closeToken 중 하나. */
    public record Frame(int kind, Open open, Show show, long closeToken) {}
    /** C2S: action SEND(text 있음) 또는 CANCEL. */
    public record Request(int action, long token, String text) {
        public Request {
            text = text == null ? "" : text;
            if ((action != SEND && action != CANCEL) || token == 0 || text.length() > MAX_TEXT) throw new IllegalArgumentException("megaphone request");
        }
    }

    // ------------------------------------------------------------------ S2C

    public static byte[] open(Open o) {
        return write(MAX_BYTES, d -> { d.writeInt(VERSION); d.writeByte(OPEN); d.writeLong(o.token()); d.writeShort(o.maxChars()); });
    }
    public static byte[] show(Show s) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(SHOW); d.writeUTF(s.id()); d.writeUTF(s.sender()); d.writeUTF(s.text());
            d.writeInt(s.durationMs()); d.writeInt(s.item().length); d.write(s.item());
        });
    }
    public static byte[] close(long token) {
        return write(MAX_BYTES, d -> { d.writeInt(VERSION); d.writeByte(CLOSE); d.writeLong(token); });
    }
    public static Frame frame(byte[] bytes) {
        return read(bytes, MAX_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int kind = d.readUnsignedByte();
            return switch (kind) {
                case OPEN -> new Frame(OPEN, new Open(d.readLong(), d.readUnsignedShort()), null, 0);
                case SHOW -> {
                    String id = d.readUTF(), sender = d.readUTF(), text = d.readUTF();
                    int duration = d.readInt(), n = d.readInt();
                    if (n < 0 || n > MAX_ITEM_BYTES) throw new IOException("item");
                    byte[] item = d.readNBytes(n);
                    if (item.length != n) throw new IOException("short item");
                    yield new Frame(SHOW, null, new Show(id, sender, text, duration, item), 0);
                }
                case CLOSE -> new Frame(CLOSE, null, null, d.readLong());
                default -> throw new IOException("kind");
            };
        });
    }

    // ------------------------------------------------------------------ C2S

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.token());
            if (r.action() == SEND) d.writeUTF(r.text());
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            long token = d.readLong();
            return new Request(action, token, action == SEND ? d.readUTF() : "");
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid megaphone packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T value = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return value;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid megaphone packet", e); }
    }

    private MegaphoneProtocol() {}
}
