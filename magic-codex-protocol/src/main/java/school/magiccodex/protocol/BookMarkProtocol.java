package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 책 블록 표시 (오너 2026-10-26, 메모 #69): 책이 연결된 블록의 보이는 면 앞에 홀로그램 책 표시, 바라보면 제목 카드.
 * 서버 → 클라 하나뿐 (채널 {@link #CHANNEL}). 그 플레이어가 있는 세계의 책 블록 전부를 한 번에 보낸다
 * (접속·세계 이동·책 연결 변경·책 읽음·새로고침 때, 상태가 바뀌었을 때만).
 * <pre>
 * u8 VERSION · UTF world · u16 n · n × MARK
 * MARK = i32 x · i32 y · i32 z · u8 face · UTF title · u8 state · i32 requiredIntelligence
 * </pre>
 * face: 0 아래 · 1 위 · 2 북(-z) · 3 남(+z) · 4 서(-x) · 5 동(+x) (마인크래프트 Direction 번호와 같음).
 * state: 0 아직 안 읽음 · 1 읽음(명령 실행됨) · 2 지능 부족.
 */
public final class BookMarkProtocol {
    public static final String CHANNEL = "magiccodex:book_marks";
    public static final int VERSION = 1, MAX_MARKS = 512, MAX_BYTES = 60_000;
    public static final int UNREAD = 0, READ = 1, LOCKED = 2;

    public record Mark(int x, int y, int z, int face, String title, int state, int required) {
        public Mark {
            Objects.requireNonNull(title);
            if (face < 0 || face > 5 || state < 0 || state > 2 || required < 0 || title.length() > BookProtocol.MAX_TITLE)
                throw new IllegalArgumentException("mark");
        }
    }
    public record Marks(String world, List<Mark> marks) {
        public Marks {
            Objects.requireNonNull(world);
            marks = List.copyOf(marks);
            if (world.length() > 128 || marks.size() > MAX_MARKS) throw new IllegalArgumentException("marks");
        }
        public static final Marks EMPTY = new Marks("", List.of());
    }

    public static byte[] encode(Marks m) {
        try {
            var b = new ByteArrayOutputStream(); var o = new DataOutputStream(b);
            o.writeByte(VERSION); o.writeUTF(m.world()); o.writeShort(m.marks().size());
            for (var k : m.marks()) {
                o.writeInt(k.x()); o.writeInt(k.y()); o.writeInt(k.z()); o.writeByte(k.face());
                o.writeUTF(k.title()); o.writeByte(k.state()); o.writeInt(k.required());
            }
            if (b.size() > MAX_BYTES) throw new IllegalArgumentException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Marks decode(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION) throw new IOException("version");
            String world = in.readUTF();
            int n = in.readUnsignedShort();
            if (n > MAX_MARKS) throw new IOException("count");
            var out = new ArrayList<Mark>(n);
            for (int i = 0; i < n; i++) out.add(new Mark(in.readInt(), in.readInt(), in.readInt(), in.readUnsignedByte(), in.readUTF(), in.readUnsignedByte(), in.readInt()));
            if (in.available() != 0) throw new IOException("trailing");
            return new Marks(world, out);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }

    private BookMarkProtocol() {}
}
