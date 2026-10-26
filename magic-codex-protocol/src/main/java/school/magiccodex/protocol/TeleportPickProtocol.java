package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 텔포권 유저 고르기 창 (오너 2026-10-26). 텔포권(ItemsAdder item:friend_teleport)을 우클릭하면 서버가 지금 접속 중인
 * 학교·야생 유저 목록(아레나 접속자는 아예 없음)을 토큰과 함께 보내고, 클라이언트가 친구 목록 창 모양의 고르기 창을 연다.
 * 한 명 고르기 = PICK(토큰, 그 유저 UUID) / 닫기 = CANCEL(토큰). 서버가 다시 검사하고 이동이 시작되면 CLOSE(토큰).
 * 클라이언트는 목록을 만들지 않는다: 서버가 보낸 사람만 고를 수 있고, 서버는 고른 UUID 가 보낸 목록에 있었는지 다시 본다.
 *
 * <pre>
 * S2C  int version, byte OPEN,  long token, short n, n × (long msb, long lsb, UTF name, UTF dormitory, UTF server)
 * S2C  int version, byte CLOSE, long token
 * C2S  int version, byte PICK,  long token, long msb, long lsb
 * C2S  int version, byte CANCEL, long token
 * </pre>
 */
public final class TeleportPickProtocol {
    public static final String REQUEST = "magiccodex:tp_pick_request", RESPONSE = "magiccodex:tp_pick_response";
    public static final int VERSION = 1;
    public static final int OPEN = 1, CLOSE = 2;
    public static final int PICK = 1, CANCEL = 2;
    public static final int MAX_ENTRIES = 300, MAX_NAME = 32, MAX_DORM = 16, MAX_SERVER = 16;
    /** 서버 → 클라이언트 묶음 상한 (300명 × 한글 닉네임 최대여도 넉넉히; 클라이언트행 플러그인 메시지 한도 1MB 안). */
    public static final int MAX_BYTES = 120_000, MAX_REQUEST_BYTES = 64;

    /** 고를 수 있는 유저 한 명: 한글 닉네임, 기숙사 이름(없으면 ""), 지금 있는 서버 이름(학교·야생1 …). */
    public record Entry(UUID id, String name, String dormitory, String server) {
        public Entry {
            if (id == null) throw new IllegalArgumentException("tp entry id");
            name = clip(name, MAX_NAME); dormitory = clip(dormitory, MAX_DORM); server = clip(server, MAX_SERVER);
        }
    }
    public record Open(long token, List<Entry> entries) {
        public Open {
            if (token == 0 || entries == null || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("tp open");
            entries = List.copyOf(entries);
        }
    }
    /** S2C 한 프레임: OPEN 이면 open, CLOSE 면 closeToken. */
    public record Frame(int kind, Open open, long closeToken) {}
    /** C2S: PICK(target 있음) 또는 CANCEL. */
    public record Request(int action, long token, UUID target) {
        public Request {
            if ((action != PICK && action != CANCEL) || token == 0 || (action == PICK && target == null)) throw new IllegalArgumentException("tp request");
            if (action == CANCEL) target = null;
        }
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ S2C

    public static byte[] open(Open o) {
        return write(MAX_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(OPEN); d.writeLong(o.token()); d.writeShort(o.entries().size());
            for (var e : o.entries()) {
                d.writeLong(e.id().getMostSignificantBits()); d.writeLong(e.id().getLeastSignificantBits());
                d.writeUTF(e.name()); d.writeUTF(e.dormitory()); d.writeUTF(e.server());
            }
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
                case OPEN -> {
                    long token = d.readLong();
                    int n = d.readUnsignedShort();
                    if (n > MAX_ENTRIES) throw new IOException("entries");
                    var list = new ArrayList<Entry>(n);
                    for (int i = 0; i < n; i++) list.add(new Entry(new UUID(d.readLong(), d.readLong()), d.readUTF(), d.readUTF(), d.readUTF()));
                    yield new Frame(OPEN, new Open(token, list), 0);
                }
                case CLOSE -> new Frame(CLOSE, null, d.readLong());
                default -> throw new IOException("kind");
            };
        });
    }

    // ------------------------------------------------------------------ C2S

    public static byte[] request(Request r) {
        return write(MAX_REQUEST_BYTES, d -> {
            d.writeInt(VERSION); d.writeByte(r.action()); d.writeLong(r.token());
            if (r.action() == PICK) { d.writeLong(r.target().getMostSignificantBits()); d.writeLong(r.target().getLeastSignificantBits()); }
        });
    }
    public static Request request(byte[] bytes) {
        return read(bytes, MAX_REQUEST_BYTES, d -> {
            if (d.readInt() != VERSION) throw new IOException("version");
            int action = d.readUnsignedByte();
            long token = d.readLong();
            return new Request(action, token, action == PICK ? new UUID(d.readLong(), d.readLong()) : null);
        });
    }

    private interface Writer { void run(DataOutputStream d) throws IOException; }
    private interface Reader<T> { T run(DataInputStream d) throws IOException; }
    private static byte[] write(int max, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); w.run(new DataOutputStream(b));
            if (b.size() > max) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid teleport pick packet", e); }
    }
    private static <T> T read(byte[] bytes, int max, Reader<T> r) {
        try {
            if (bytes == null || bytes.length > max) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            T out = r.run(d);
            if (d.available() != 0) throw new IOException("trailing");
            return out;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid teleport pick packet", e); }
    }

    private TeleportPickProtocol() {}
}
