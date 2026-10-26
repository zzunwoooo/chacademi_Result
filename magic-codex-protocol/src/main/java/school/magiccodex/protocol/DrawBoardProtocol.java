package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 운영자 전용 「행운의 뽑기판」 100칸 (오너 디자인 납품 2026-10-26, FINAL_PATCHES §44).
 *
 * <ul>
 *   <li>{@link #STATE} 서버 → 클라: 판 상태({@link State}) 또는 등록 아이템 목록 조각({@link Catalog}). 앞 4바이트(magic)로 구분 ({@link #kind}).</li>
 *   <li>{@link #ACTION} 클라 → 서버: 칸 뒤집기·닫기·관리(등록·삭제·새로 섞기·새로고침) ({@link Action}).</li>
 * </ul>
 * 판 자체(등록 100개·섞인 배치·공개 칸·회차)는 서버의 공유 DB(codex_draw_board / codex_draw_item)에 있다.
 * 클라에는 공개된 칸의 아이템 번호(목록 순번)만 간다 — 숨은 칸의 아이템은 보내지 않는다.
 * 아이템 그림 바이트는 서버 → 클라 방향으로만 가고, 등록은 "내 인벤토리 몇 번 칸"만 보낸다 (상점·상자 관리 창과 같은 방식).
 */
public final class DrawBoardProtocol {
    private DrawBoardProtocol() {}

    public static final String STATE = "magiccodex:draw_board", ACTION = "magiccodex:draw_board_action";
    /** 10 × 10 = 100칸. 등록 아이템도 정확히 100개여야 판이 시작된다. */
    public static final int SIZE = 10, CELLS = SIZE * SIZE;
    /** 패킷 상한 · 미리보기 아이템 1개 상한(넘으면 재질만) · 목록 조각 하나에 담는 바이트 목표. */
    public static final int MAX_BYTES = 60000, MAX_ITEM = 3072, CATALOG_BUDGET = 48000;
    /** 100칸이 모두 공개된 뒤 다음 판으로 넘어가기 전 그대로 보여 주는 시간 (마지막 아이템 보기). */
    public static final int HOLD_MS = 1500;

    /** State.screen: 0 = 이미 열린 창 갱신, 1 = 뽑기판 창 열기, 2 = 관리 창 열기. */
    public static final int SCREEN_NONE = 0, SCREEN_BOARD = 1, SCREEN_ADMIN = 2;
    /** Action.action */
    public static final int CLOSE = 0, FLIP = 1, ADD = 2, REMOVE = 3, RESHUFFLE = 4, REFRESH = 5;
    public static final int KIND_STATE = 1, KIND_CATALOG = 2;

    private static final int STATE_MAGIC = 0x44425331, CATALOG_MAGIC = 0x44424331, ACTION_MAGIC = 0x44424131;

    /** 공개된 칸 하나: cell 0~99 (가로줄 우선), item = 등록 목록 순번 (Catalog 순서). */
    public record Cell(int cell, int item) {}
    /**
     * session = 이 창 열기 번호, sequence = 이 상태를 만든 내 요청 번호(다른 곳 변경·주기 갱신이면 0), revision = 판 DB 버전,
     * round = 회차, itemsRevision = 등록 목록 버전 (Catalog 와 짝), registered = 등록 개수, ready = 100개라 판이 있음,
     * complete = 100칸 모두 공개되어 잠깐 보여 주는 중 (곧 다음 판), message = 한 줄 안내(비면 없음).
     */
    public record State(int screen, long session, long sequence, long revision, int round, long itemsRevision, int registered,
                        boolean ready, boolean complete, List<Cell> revealed, String message) {}
    /** 등록 아이템 하나: id(삭제용), preview = 그릴 ItemStack 바이트 (못 만들면 빈 배열). */
    public record Entry(String id, byte[] preview) {}
    /** 등록 목록 조각: start 번부터 entries 개, 전체 total 개. */
    public record Catalog(long session, long itemsRevision, int total, int start, List<Entry> entries) {}
    /**
     * FLIP: round·cell·reveal(true = 공개, false = 다시 가리기). ADD: slot(내 인벤토리 0~35)·slotItem(그 칸 아이템 id, 서버가 다시 확인).
     * REMOVE: id. RESHUFFLE·REFRESH·CLOSE: 값 없음.
     */
    public record Action(int action, long session, long sequence, int round, int cell, boolean reveal, int slot, String slotItem, String id) {}

    // ------------------------------------------------------------------ 공통

    private interface Write { void run(DataOutputStream out) throws IOException; }

    private static byte[] pack(int magic, Write w) {
        try {
            var b = new ByteArrayOutputStream();
            var o = new DataOutputStream(b);
            o.writeInt(magic);
            w.run(o);
            o.flush();
            if (b.size() > MAX_BYTES) throw new IOException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    private static DataInputStream input(byte[] b, int magic) throws IOException {
        if (b == null || b.length < 4 || b.length > MAX_BYTES) throw new IOException("size");
        var i = new DataInputStream(new ByteArrayInputStream(b));
        if (i.readInt() != magic) throw new IOException("version");
        return i;
    }

    private static String text(DataInputStream i, int max) throws IOException {
        String s = i.readUTF();
        if (s.length() > max || s.codePoints().anyMatch(Character::isISOControl)) throw new IOException("text");
        return s;
    }

    /** STATE 채널 패킷 종류: {@link #KIND_STATE}, {@link #KIND_CATALOG}, 모르면 0. */
    public static int kind(byte[] b) {
        if (b == null || b.length < 4) return 0;
        int magic = (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
        return magic == STATE_MAGIC ? KIND_STATE : magic == CATALOG_MAGIC ? KIND_CATALOG : 0;
    }

    /** 아이템 id 모양 (서버가 만든 UUID 문자열). */
    public static boolean itemId(String id) { return id != null && id.matches("[0-9a-f-]{1,36}"); }

    // ------------------------------------------------------------------ STATE

    public static byte[] encode(State s) {
        return pack(STATE_MAGIC, o -> {
            o.writeByte(s.screen); o.writeLong(s.session); o.writeLong(s.sequence); o.writeLong(s.revision); o.writeInt(s.round);
            o.writeLong(s.itemsRevision); o.writeShort(s.registered); o.writeBoolean(s.ready); o.writeBoolean(s.complete);
            o.writeByte(s.revealed.size());
            for (var c : s.revealed) { o.writeByte(c.cell); o.writeByte(c.item); }
            o.writeUTF(s.message);
        });
    }

    public static State state(byte[] bytes) {
        try {
            var i = input(bytes, STATE_MAGIC);
            int screen = i.readUnsignedByte();
            long session = i.readLong(), sequence = i.readLong(), revision = i.readLong();
            int round = i.readInt();
            long items = i.readLong();
            int registered = i.readUnsignedShort();
            boolean ready = i.readBoolean(), complete = i.readBoolean();
            int n = i.readUnsignedByte();
            if (screen > SCREEN_ADMIN || session <= 0 || sequence < 0 || round < 0 || registered > CELLS || n > CELLS || (!ready && n > 0)) throw new IOException("state");
            var cells = new ArrayList<Cell>(n);
            var seen = new boolean[CELLS];
            for (int x = 0; x < n; x++) {
                int cell = i.readUnsignedByte(), item = i.readUnsignedByte();
                if (cell >= CELLS || item >= CELLS || seen[cell]) throw new IOException("cell");
                seen[cell] = true;
                cells.add(new Cell(cell, item));
            }
            String message = text(i, 240);
            if (i.available() != 0) throw new IOException("trailing");
            return new State(screen, session, sequence, revision, round, items, registered, ready, complete, List.copyOf(cells), message);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("draw board state", e); }
    }

    // ------------------------------------------------------------------ CATALOG

    public static byte[] encode(Catalog c) {
        return pack(CATALOG_MAGIC, o -> {
            o.writeLong(c.session); o.writeLong(c.itemsRevision); o.writeShort(c.total); o.writeShort(c.start);
            o.writeByte(c.entries.size());
            for (var e : c.entries) { o.writeUTF(e.id); o.writeShort(e.preview.length); o.write(e.preview); }
        });
    }

    public static Catalog catalog(byte[] bytes) {
        try {
            var i = input(bytes, CATALOG_MAGIC);
            long session = i.readLong(), items = i.readLong();
            int total = i.readUnsignedShort(), start = i.readUnsignedShort(), n = i.readUnsignedByte();
            if (session <= 0 || total > CELLS || start > total || start + n > total) throw new IOException("catalog");
            var entries = new ArrayList<Entry>(n);
            for (int x = 0; x < n; x++) {
                String id = text(i, 36);
                if (!itemId(id)) throw new IOException("id");
                int len = i.readUnsignedShort();
                if (len > MAX_ITEM) throw new IOException("item");
                byte[] b = i.readNBytes(len);
                if (b.length != len) throw new IOException("item");
                entries.add(new Entry(id, b));
            }
            if (i.available() != 0) throw new IOException("trailing");
            return new Catalog(session, items, total, start, List.copyOf(entries));
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("draw board catalog", e); }
    }

    /** 미리보기 목록을 패킷 조각으로 나눈다 (조각마다 바이트 합이 budget 이하, 단 한 개는 언제나 들어감). */
    public static List<Catalog> split(long session, long itemsRevision, List<Entry> all, int budget) {
        var out = new ArrayList<Catalog>();
        int start = 0;
        while (start < all.size()) {
            int end = start, size = 0;
            while (end < all.size() && (end == start || size + all.get(end).preview.length + 48 <= budget) && end - start < 255) { size += all.get(end).preview.length + 48; end++; }
            out.add(new Catalog(session, itemsRevision, all.size(), start, List.copyOf(all.subList(start, end))));
            start = end;
        }
        if (out.isEmpty()) out.add(new Catalog(session, itemsRevision, 0, 0, List.of()));
        return out;
    }

    // ------------------------------------------------------------------ ACTION

    public static byte[] encode(Action a) {
        return pack(ACTION_MAGIC, o -> {
            o.writeByte(a.action); o.writeLong(a.session); o.writeLong(a.sequence); o.writeInt(a.round); o.writeByte(a.cell + 1);
            o.writeBoolean(a.reveal); o.writeByte(a.slot + 1); o.writeUTF(a.slotItem); o.writeUTF(a.id);
        });
    }

    public static Action action(byte[] bytes) {
        try {
            var i = input(bytes, ACTION_MAGIC);
            var a = new Action(i.readUnsignedByte(), i.readLong(), i.readLong(), i.readInt(), i.readUnsignedByte() - 1, i.readBoolean(),
                    i.readUnsignedByte() - 1, text(i, 80), text(i, 36));
            if (i.available() != 0 || a.action > REFRESH || a.session <= 0 || a.sequence <= 0 || a.round < 0
                    || a.cell < -1 || a.cell >= CELLS || a.slot < -1 || a.slot > 35) throw new IOException("action");
            if (a.action == FLIP && a.cell < 0) throw new IOException("cell");
            if (a.action == ADD && (a.slot < 0 || a.slotItem.isBlank())) throw new IOException("slot");
            if (a.action == REMOVE && !itemId(a.id)) throw new IOException("id");
            return a;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("draw board action", e); }
    }
}
