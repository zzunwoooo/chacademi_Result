package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

// [기능:alchemy-overhaul 시작] 연금 쪽지·연금 수첩 (2026-10-31, 새 파일)
/**
 * 연금 수첩 (2026-10-31): 연금 쪽지를 읽어 모은 조합식 힌트. 서버가 힌트 문장을 만들어 보내고, 클라이언트는 보여 주기만 한다.
 *
 * C2S request: int version, byte action (OPEN).
 * S2C state:   int version, String focus (방금 읽은 조합식 ID, 없으면 ""), short n,
 *              n x (String id, String title, byte grade 1~4, bool discovered, String firstBy, byte lines, lines x String).
 */
public final class AlchemyNoteProtocol {
    public static final String REQUEST = "magiccodex:alchemy_note_request", RESPONSE = "magiccodex:alchemy_note_response";
    public static final int VERSION = 1;
    public static final int OPEN = 0;
    public static final int MAX_ENTRIES = 512, MAX_LINES = 12, MAX_LINE = 120, MAX_TITLE = 40, MAX_ID = 64, MAX_BYTES = 262144;

    public record Entry(String id, String title, int grade, boolean discovered, String firstBy, List<String> lines) {
        public Entry {
            if (id == null || id.isEmpty() || id.length() > MAX_ID || title == null || grade < 1 || grade > 4 || lines == null || lines.size() > MAX_LINES)
                throw new IllegalArgumentException("alchemy note entry");
            title = cut(title, MAX_TITLE); firstBy = firstBy == null ? "" : cut(firstBy, MAX_TITLE);
            lines = lines.stream().map(l -> cut(l == null ? "" : l, MAX_LINE)).toList();
        }
    }
    public record State(String focus, List<Entry> entries) {
        public State {
            if (entries == null || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("alchemy note state");
            focus = focus == null ? "" : focus; entries = List.copyOf(entries);
        }
    }

    private AlchemyNoteProtocol() {}

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    public static byte[] request(int action) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION); out.writeByte(action); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static int request(byte[] data) {
        if (data == null || data.length > 64) throw new IllegalArgumentException("alchemy note request");
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != VERSION) throw new IllegalArgumentException("alchemy note version");
            int action = in.readUnsignedByte();
            if (action != OPEN) throw new IllegalArgumentException("alchemy note action");
            return action;
        } catch (IOException e) { throw new IllegalArgumentException("alchemy note request", e); }
    }

    public static byte[] encode(State s) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION); out.writeUTF(s.focus()); out.writeShort(s.entries().size());
            for (Entry e : s.entries()) {
                out.writeUTF(e.id()); out.writeUTF(e.title()); out.writeByte(e.grade()); out.writeBoolean(e.discovered()); out.writeUTF(e.firstBy());
                out.writeByte(e.lines().size()); for (String l : e.lines()) out.writeUTF(l);
            }
            out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("alchemy note state too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static State decode(byte[] data) {
        if (data == null || data.length > MAX_BYTES) throw new IllegalArgumentException("alchemy note state");
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != VERSION) throw new IllegalArgumentException("alchemy note version");
            String focus = in.readUTF(); int n = in.readUnsignedShort();
            if (n > MAX_ENTRIES) throw new IllegalArgumentException("alchemy note entries");
            var entries = new ArrayList<Entry>(n);
            for (int i = 0; i < n; i++) {
                String id = in.readUTF(), title = in.readUTF(); int grade = in.readUnsignedByte(); boolean discovered = in.readBoolean(); String firstBy = in.readUTF();
                int lines = in.readUnsignedByte(); if (lines > MAX_LINES) throw new IllegalArgumentException("alchemy note lines");
                var list = new ArrayList<String>(lines); for (int j = 0; j < lines; j++) list.add(in.readUTF());
                entries.add(new Entry(id, title, grade, discovered, firstBy, list));
            }
            return new State(focus, entries);
        } catch (IOException e) { throw new IllegalArgumentException("alchemy note state", e); }
    }
}
// [기능:alchemy-overhaul 끝]
