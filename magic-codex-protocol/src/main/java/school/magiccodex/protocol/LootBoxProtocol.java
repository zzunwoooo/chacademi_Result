package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 뽑기 상자 (API 상자, 2026-10-26). 서버가 추첨·소모·지급을 모두 끝낸 뒤에만 연출을 보낸다.
 *
 * <ul>
 *   <li>{@link #PLAY} 서버 → 클라: 연출 시작. 결과 ItemStack 은 서버가 실제로 지급 확정한 것 그대로(직렬화 바이트).</li>
 *   <li>{@link #DONE} 클라 → 서버: 연출이 끝났다는 알림(선택). 지급과 무관하며 서버는 무시해도 된다.</li>
 *   <li>{@link #ADMIN_REQUEST}/{@link #ADMIN_RESPONSE}: 운영자 상자 관리 창. 아이템 바이트는 서버 → 클라 방향으로만 간다
 *       (등록은 "내 인벤토리 몇 번 칸"만 보내고 서버가 실제 칸을 다시 읽는다 — 상점 관리 창과 같은 방식).</li>
 * </ul>
 * 등급: 0 일반 · 1 고급 · 2 유일 · 3 전설.
 */
public final class LootBoxProtocol {
    private LootBoxProtocol() {}

    public static final String PLAY = "magiccodex:lootbox_play", DONE = "magiccodex:lootbox_done",
            ADMIN_REQUEST = "magiccodex:lootbox_admin_request", ADMIN_RESPONSE = "magiccodex:lootbox_admin_response";
    /** MAX_BYTES = 관리 창·DONE 패킷 상한, PLAY_MAX_BYTES = 연출 패킷 상한(결과 10개의 실제 ItemStack 원본을 줄이지 않고 담기 위해 크게). */
    public static final int MAX_BYTES = 60000, PLAY_MAX_BYTES = 393216, MAX_ITEM = 32768, MAX_RESULTS = 10;
    public static final int COMMON = 0, RARE = 1, UNIQUE = 2, LEGENDARY = 3;
    /** 관리 창 동작. */
    public static final int LIST = 0, ADD = 1, EDIT = 2, DELETE = 3, RENAME = 4, REKEY = 5, DELETE_BOX = 6, CLOSE = 7;
    public static final int PAGE = 12;

    private static final int PLAY_MAGIC = 0x4C425031, DONE_MAGIC = 0x4C424431, ADMIN_MAGIC = 0x4C424131;

    // ------------------------------------------------------------------ 연출

    /** 결과 한 칸: 실제 지급 확정 ItemStack(서버 직렬화 바이트)과 그 보상의 등급. */
    public record Result(int grade, byte[] item, String name) {}
    /**
     * opening = 이 개봉 건 번호(표시·DONE 짝 맞추기용), grade = 대표 연출 등급(결과 중 가장 높은 등급), notice = 아래 한 줄 안내,
     * speed = 연출 속도 % (loot-boxes.yml animation.speed, 50~200).
     */
    public record Play(long opening, String box, String title, int grade, List<Result> results, String notice, int speed) {}
    public record Done(long opening) {}

    // ------------------------------------------------------------------ 관리 창

    /**
     * box = 상자 id (비면 첫 상자), index = 보상 순번(EDIT·DELETE), grade·amount·weight = 보상 값, slot = 고른 내 인벤토리 칸
     * (0~35, -1 = 주 손), slotItem = 그 칸 아이템 id(서버가 같은지 다시 확인), text = 새 이름(RENAME), offset = 보상 목록 쪽.
     */
    public record Request(int action, long sequence, long session, String box, long revision, int index, int grade, int amount,
                          int weight, int slot, String slotItem, String text, int offset) {}
    public record Box(String id, String title, int rewards, boolean valid) {}
    /** 보상 한 줄. spec = yml 에 적힌 아이템 원본 표기(사람이 읽기용 요약), preview = 미리보기 ItemStack 바이트(없으면 빈 배열). */
    public record Reward(int index, int grade, int amount, int weight, String spec, byte[] preview) {}
    /**
     * key = 열쇠 설명 한 줄, keyPreview = 열쇠 아이템 미리보기, totalWeight = 이 상자 모든 보상 가중치 합(확률 % 계산),
     * gradeWeights = 등급별 가중치 합 [일반, 고급, 유일, 전설].
     */
    public record Response(long sequence, long session, String box, long revision, String message, List<Box> boxes,
                           String title, String key, byte[] keyPreview, long totalWeight, long[] gradeWeights,
                           List<Reward> rewards, int offset, int nextOffset) {}

    // ------------------------------------------------------------------ 공통

    private interface Write { void run(DataOutputStream out) throws IOException; }

    private static byte[] pack(int magic, Write w) { return pack(magic, MAX_BYTES, w); }

    private static byte[] pack(int magic, int max, Write w) {
        try {
            var b = new ByteArrayOutputStream();
            var o = new DataOutputStream(b);
            o.writeInt(magic);
            w.run(o);
            o.flush();
            if (b.size() > max) throw new IOException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    private static DataInputStream input(byte[] b, int magic) throws IOException { return input(b, magic, MAX_BYTES); }

    private static DataInputStream input(byte[] b, int magic, int max) throws IOException {
        if (b == null || b.length < 4 || b.length > max) throw new IOException("size");
        var i = new DataInputStream(new ByteArrayInputStream(b));
        if (i.readInt() != magic) throw new IOException("version");
        return i;
    }

    private static String text(DataInputStream i, int max) throws IOException {
        String s = i.readUTF();
        if (s.length() > max) throw new IOException("text");
        return s;
    }

    private static byte[] bytes(DataInputStream i, int max) throws IOException {
        int n = i.readInt();
        if (n < 0 || n > max) throw new IOException("bytes");
        byte[] b = i.readNBytes(n);
        if (b.length != n) throw new IOException("bytes");
        return b;
    }

    private static void bytes(DataOutputStream o, byte[] b) throws IOException { o.writeInt(b.length); o.write(b); }

    static boolean grade(int g) { return g >= COMMON && g <= LEGENDARY; }

    // ------------------------------------------------------------------ PLAY / DONE

    public static byte[] encode(Play p) {
        return pack(PLAY_MAGIC, PLAY_MAX_BYTES, o -> {
            o.writeLong(p.opening); o.writeUTF(p.box); o.writeUTF(p.title); o.writeByte(p.grade);
            o.writeByte(p.results.size());
            for (var r : p.results) { o.writeByte(r.grade); bytes(o, r.item); o.writeUTF(r.name); }
            o.writeUTF(p.notice); o.writeShort(p.speed);
        });
    }

    public static Play play(byte[] bytes) {
        try {
            var i = input(bytes, PLAY_MAGIC, PLAY_MAX_BYTES);
            long opening = i.readLong();
            String box = text(i, 48), title = text(i, 80);
            int grade = i.readUnsignedByte(), n = i.readUnsignedByte();
            if (!grade(grade) || n < 1 || n > MAX_RESULTS) throw new IOException("play");
            var results = new ArrayList<Result>();
            for (int x = 0; x < n; x++) {
                int g = i.readUnsignedByte();
                if (!grade(g)) throw new IOException("grade");
                byte[] item = bytes(i, MAX_ITEM);
                if (item.length == 0) throw new IOException("item");
                results.add(new Result(g, item, text(i, 120)));
            }
            String notice = text(i, 240);
            int speed = i.readUnsignedShort();
            if (i.available() != 0 || speed < 50 || speed > 200) throw new IOException("trailing");
            return new Play(opening, box, title, grade, List.copyOf(results), notice, speed);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("lootbox play", e); }
    }

    public static byte[] encode(Done d) { return pack(DONE_MAGIC, o -> o.writeLong(d.opening)); }

    public static Done done(byte[] bytes) {
        try {
            var i = input(bytes, DONE_MAGIC);
            var d = new Done(i.readLong());
            if (i.available() != 0) throw new IOException("trailing");
            return d;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("lootbox done", e); }
    }

    // ------------------------------------------------------------------ ADMIN

    public static byte[] encode(Request r) {
        return pack(ADMIN_MAGIC, o -> {
            o.writeByte(r.action); o.writeLong(r.sequence); o.writeLong(r.session); o.writeUTF(r.box); o.writeLong(r.revision);
            o.writeInt(r.index); o.writeByte(r.grade); o.writeInt(r.amount); o.writeInt(r.weight); o.writeByte(r.slot + 1);
            o.writeUTF(r.slotItem); o.writeUTF(r.text); o.writeInt(r.offset);
        });
    }

    public static Request request(byte[] bytes) {
        try {
            var i = input(bytes, ADMIN_MAGIC);
            var r = new Request(i.readUnsignedByte(), i.readLong(), i.readLong(), text(i, 48), i.readLong(), i.readInt(),
                    i.readUnsignedByte(), i.readInt(), i.readInt(), i.readUnsignedByte() - 1, text(i, 80), text(i, 40), i.readInt());
            if (i.available() != 0 || r.action < LIST || r.action > CLOSE || r.sequence <= 0 || r.session <= 0
                    || !r.box.matches("[a-z0-9_-]{0,48}") || r.index < -1 || r.index > 9999 || !grade(r.grade)
                    || r.amount < 0 || r.amount > 64 || r.weight < 0 || r.weight > 1_000_000 || r.slot < -1 || r.slot > 35
                    || r.offset < 0 || r.offset > 100000
                    || r.slotItem.codePoints().anyMatch(Character::isISOControl) || r.text.codePoints().anyMatch(Character::isISOControl))
                throw new IOException("request");
            if ((r.action == ADD || r.action == EDIT) && (r.amount < 1 || r.weight < 1)) throw new IOException("values");
            if ((r.action == EDIT || r.action == DELETE) && r.index < 0) throw new IOException("index");
            if (r.action == RENAME && r.text.isBlank()) throw new IOException("name");
            return r;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("lootbox admin request", e); }
    }

    public static byte[] encode(Response r) {
        return pack(ADMIN_MAGIC, o -> {
            o.writeLong(r.sequence); o.writeLong(r.session); o.writeUTF(r.box); o.writeLong(r.revision); o.writeUTF(r.message);
            o.writeShort(r.boxes.size());
            for (var b : r.boxes) { o.writeUTF(b.id); o.writeUTF(b.title); o.writeInt(b.rewards); o.writeBoolean(b.valid); }
            o.writeUTF(r.title); o.writeUTF(r.key); bytes(o, r.keyPreview); o.writeLong(r.totalWeight);
            for (int g = 0; g < 4; g++) o.writeLong(r.gradeWeights[g]);
            o.writeByte(r.rewards.size());
            for (var w : r.rewards) { o.writeInt(w.index); o.writeByte(w.grade); o.writeInt(w.amount); o.writeInt(w.weight); o.writeUTF(w.spec); bytes(o, w.preview); }
            o.writeInt(r.offset); o.writeInt(r.nextOffset);
        });
    }

    public static Response response(byte[] bytes) {
        try {
            var i = input(bytes, ADMIN_MAGIC);
            long seq = i.readLong(), session = i.readLong();
            String box = text(i, 48);
            long revision = i.readLong();
            String message = text(i, 240);
            int n = i.readUnsignedShort();
            if (n > 200) throw new IOException("boxes");
            var boxes = new ArrayList<Box>();
            for (int x = 0; x < n; x++) boxes.add(new Box(text(i, 48), text(i, 80), i.readInt(), i.readBoolean()));
            String title = text(i, 80), key = text(i, 160);
            byte[] keyPreview = bytes(i, MAX_ITEM);
            long total = i.readLong();
            long[] grades = new long[4];
            for (int g = 0; g < 4; g++) grades[g] = i.readLong();
            n = i.readUnsignedByte();
            if (n > PAGE) throw new IOException("rewards");
            var rewards = new ArrayList<Reward>();
            for (int x = 0; x < n; x++) {
                int index = i.readInt(), g = i.readUnsignedByte(), amount = i.readInt(), weight = i.readInt();
                if (!grade(g)) throw new IOException("grade");
                rewards.add(new Reward(index, g, amount, weight, text(i, 160), bytes(i, MAX_ITEM)));
            }
            int offset = i.readInt(), next = i.readInt();
            if (i.available() != 0 || session <= 0 || seq < 0 || total < 0 || offset < 0 || next < -1) throw new IOException("response");
            return new Response(seq, session, box, revision, message, List.copyOf(boxes), title, key, keyPreview, total, grades,
                    List.copyOf(rewards), offset, next);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("lootbox admin response", e); }
    }
}
