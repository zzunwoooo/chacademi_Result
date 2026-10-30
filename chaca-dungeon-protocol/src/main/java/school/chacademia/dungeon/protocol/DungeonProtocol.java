package school.chacademia.dungeon.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1인 던전 서버 ↔ 클라이언트 메시지 (서버 플러그인 chaca-dungeon-paper 와 클라이언트 모드 chacademi-dungeon 이 같이 쓴다).
 * 형식: u8 버전 · u8 종류 · 본문 (DataOutput, 큰 끝). 문자열은 writeUTF, 아이템은 Paper serializeAsBytes 바이트.
 * 서버는 클라이언트가 보낸 값 중 세션 번호만 쓴다 (던전 ID·기록·보상은 받지 않는다).
 */
public final class DungeonProtocol {
    private DungeonProtocol() {}

    public static final String S2C = "chacademi:dungeon";
    public static final String C2S = "chacademi:dungeon_act";
    /** 2 (2026-10-30): 입장창 보상을 묶음(기본·첫 클리어·확률)으로, 체력 자동 회복 없음 표시. 서버·클라 같이 바꿔야 함. */
    public static final int VERSION = 2;
    public static final int MAX_BYTES = 1 << 20;
    public static final int MAX_ITEMS = 54;

    // 서버 → 클라이언트
    public static final int K_ENTRY = 1, K_QUEUE = 2, K_RUN = 3, K_RESULT = 4, K_PORTALS = 5, K_CLOSE = 6;
    // 클라이언트 → 서버
    public static final int C_ENTER = 1, C_QUEUE_JOIN = 2, C_QUEUE_LEAVE = 3, C_EXIT = 4;

    /** 입장창 버튼 상태. */
    public static final int BTN_ENTER = 0, BTN_QUEUE = 1, BTN_QUEUED = 2, BTN_LOCKED = 3, BTN_IN_RUN = 4, BTN_READY = 5;
    /** 대기 상태. */
    public static final int Q_NONE = 0, Q_WAITING = 1, Q_READY = 2;

    /** 칭호 한 쪽 (접두 또는 접미). 없으면 이름이 빈 문자열. color = 0xRRGGBB. */
    public record Title(String name, int color) {
        public static final Title NONE = new Title("", 0xFFFFFF);
    }

    /** 서버 최고 기록 보유자. ms < 0 = 기록 없음. */
    public record Holder(long ms, String name, Title prefix, Title suffix) {
        public static final Holder NONE = new Holder(-1, "", Title.NONE, Title.NONE);
    }

    /** 보상 묶음 종류. */
    public static final int R_BASE = 0, R_FIRST = 1, R_RANDOM = 2;

    /**
     * 입장창 보상 한 묶음. done = 이미 받음(첫 클리어 보상을 받은 뒤). chances = 아이템마다 확률(만분율, 10000 = 100%),
     * 확률 묶음이 아니면 비어 있음.
     */
    public record RewardGroup(int kind, String label, boolean done, List<byte[]> items, List<Integer> chances) {}

    /** ticketFree = 첫 클리어 전이라 이번 입장은 입장권 없이 (ticketNeed 는 평소 필요한 장수). noRegen = 던전 안 체력 자동 회복 없음. */
    public record Entry(int session, String name, String subtitle, boolean cleared, long personalBestMs, Holder best,
                        int active, int max, int ticketNeed, int ticketHave, String ticketName, boolean ticketFree,
                        int button, String lockReason, boolean noRegen, List<RewardGroup> rewards) {}

    public record Queue(int state, String name, int position, int queued, int active, int max, long offerLeftMs, int session) {}

    /** 던전 안 상태. active=false 면 던전 HUD 를 내린다. timing=false 면 아직 도전 시작 전(시간 0). */
    public record Run(boolean active, String name, long elapsedMs, boolean timing, boolean finished,
                      int stage, int stages, String stageName, String goal, int current, int required, boolean locked) {
        public static final Run NONE = new Run(false, "", 0, false, false, 0, 0, "", "", 0, 0, false);
    }

    public record Result(boolean cleared, String name, String reason, long timeMs, long previousBestMs, Holder best,
                         boolean newPersonal, boolean newServer, boolean firstClear, String rewardNote, List<byte[]> given) {}

    public record Portal(String dimension, double x, double y, double z, float radius, String label) {}

    public sealed interface ToClient permits EntryMsg, QueueMsg, RunMsg, ResultMsg, PortalsMsg, CloseMsg {}
    public record EntryMsg(Entry entry) implements ToClient {}
    public record QueueMsg(Queue queue) implements ToClient {}
    public record RunMsg(Run run) implements ToClient {}
    public record ResultMsg(Result result) implements ToClient {}
    public record PortalsMsg(List<Portal> portals) implements ToClient {}
    public record CloseMsg() implements ToClient {}

    public record Action(int kind, int session) {}

    // ------------------------------------------------------------ encode

    public static byte[] encode(ToClient message) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            switch (message) {
                case EntryMsg m -> { out.writeByte(K_ENTRY); writeEntry(out, m.entry()); }
                case QueueMsg m -> {
                    var q = m.queue();
                    out.writeByte(K_QUEUE);
                    out.writeByte(q.state()); out.writeUTF(q.name()); out.writeInt(q.position()); out.writeInt(q.queued());
                    out.writeInt(q.active()); out.writeInt(q.max()); out.writeLong(q.offerLeftMs()); out.writeInt(q.session());
                }
                case RunMsg m -> {
                    var r = m.run();
                    out.writeByte(K_RUN);
                    out.writeBoolean(r.active()); out.writeUTF(r.name()); out.writeLong(r.elapsedMs()); out.writeBoolean(r.timing());
                    out.writeBoolean(r.finished()); out.writeInt(r.stage()); out.writeInt(r.stages()); out.writeUTF(r.stageName());
                    out.writeUTF(r.goal()); out.writeInt(r.current()); out.writeInt(r.required()); out.writeBoolean(r.locked());
                }
                case ResultMsg m -> {
                    var r = m.result();
                    out.writeByte(K_RESULT);
                    out.writeBoolean(r.cleared()); out.writeUTF(r.name()); out.writeUTF(r.reason()); out.writeLong(r.timeMs());
                    out.writeLong(r.previousBestMs()); writeHolder(out, r.best()); out.writeBoolean(r.newPersonal());
                    out.writeBoolean(r.newServer()); out.writeBoolean(r.firstClear()); out.writeUTF(r.rewardNote());
                    writeItems(out, r.given());
                }
                case PortalsMsg m -> {
                    out.writeByte(K_PORTALS);
                    out.writeShort(m.portals().size());
                    for (var p : m.portals()) {
                        out.writeUTF(p.dimension()); out.writeDouble(p.x()); out.writeDouble(p.y()); out.writeDouble(p.z());
                        out.writeFloat(p.radius()); out.writeUTF(p.label());
                    }
                }
                case CloseMsg m -> out.writeByte(K_CLOSE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static byte[] encode(Action action) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION); out.writeByte(action.kind()); out.writeInt(action.session());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(DataOutputStream out, Entry e) throws IOException {
        out.writeInt(e.session()); out.writeUTF(e.name()); out.writeUTF(e.subtitle()); out.writeBoolean(e.cleared());
        out.writeLong(e.personalBestMs()); writeHolder(out, e.best()); out.writeInt(e.active()); out.writeInt(e.max());
        out.writeInt(e.ticketNeed()); out.writeInt(e.ticketHave()); out.writeUTF(e.ticketName()); out.writeBoolean(e.ticketFree());
        out.writeByte(e.button()); out.writeUTF(e.lockReason()); out.writeBoolean(e.noRegen());
        out.writeByte(Math.min(e.rewards().size(), 8));
        for (var g : e.rewards().subList(0, Math.min(e.rewards().size(), 8))) {
            out.writeByte(g.kind()); out.writeUTF(g.label()); out.writeBoolean(g.done()); writeItems(out, g.items());
            out.writeShort(Math.min(g.chances().size(), MAX_ITEMS));
            for (int i = 0; i < Math.min(g.chances().size(), MAX_ITEMS); i++) out.writeShort(Math.clamp(g.chances().get(i), 0, 10000));
        }
    }

    private static List<RewardGroup> readGroups(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n > 8) throw new IOException("too many reward groups");
        var out = new ArrayList<RewardGroup>(n);
        for (int i = 0; i < n; i++) {
            int kind = in.readUnsignedByte();
            String label = in.readUTF();
            boolean done = in.readBoolean();
            var items = readItems(in);
            int c = in.readUnsignedShort();
            if (c > MAX_ITEMS) throw new IOException("too many chances");
            var chances = new ArrayList<Integer>(c);
            for (int k = 0; k < c; k++) chances.add(in.readUnsignedShort());
            out.add(new RewardGroup(kind, label, done, items, List.copyOf(chances)));
        }
        return List.copyOf(out);
    }

    private static void writeHolder(DataOutputStream out, Holder h) throws IOException {
        out.writeLong(h.ms()); out.writeUTF(h.name());
        out.writeUTF(h.prefix().name()); out.writeInt(h.prefix().color());
        out.writeUTF(h.suffix().name()); out.writeInt(h.suffix().color());
    }

    private static void writeItems(DataOutputStream out, List<byte[]> items) throws IOException {
        int n = Math.min(items.size(), MAX_ITEMS);
        out.writeShort(n);
        for (int i = 0; i < n; i++) { out.writeInt(items.get(i).length); out.write(items.get(i)); }
    }

    // ------------------------------------------------------------ decode (잘못된 값이면 IOException)

    public static ToClient decodeToClient(byte[] data) throws IOException {
        if (data == null || data.length < 2 || data.length > MAX_BYTES) throw new IOException("bad size");
        var in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != VERSION) throw new IOException("bad version");
        int kind = in.readUnsignedByte();
        ToClient out = switch (kind) {
            case K_ENTRY -> new EntryMsg(new Entry(in.readInt(), in.readUTF(), in.readUTF(), in.readBoolean(), in.readLong(),
                    readHolder(in), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readUTF(), in.readBoolean(),
                    in.readUnsignedByte(), in.readUTF(), in.readBoolean(), readGroups(in)));
            case K_QUEUE -> new QueueMsg(new Queue(in.readUnsignedByte(), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readLong(), in.readInt()));
            case K_RUN -> new RunMsg(new Run(in.readBoolean(), in.readUTF(), in.readLong(), in.readBoolean(), in.readBoolean(),
                    in.readInt(), in.readInt(), in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readBoolean()));
            case K_RESULT -> new ResultMsg(new Result(in.readBoolean(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong(),
                    readHolder(in), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readUTF(), readItems(in)));
            case K_PORTALS -> {
                int n = in.readUnsignedShort();
                if (n > 64) throw new IOException("too many portals");
                var list = new ArrayList<Portal>(n);
                for (int i = 0; i < n; i++)
                    list.add(new Portal(in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readUTF()));
                yield new PortalsMsg(List.copyOf(list));
            }
            case K_CLOSE -> new CloseMsg();
            default -> throw new IOException("unknown kind " + kind);
        };
        if (in.available() != 0) throw new IOException("trailing bytes");
        return out;
    }

    public static Action decodeAction(byte[] data) throws IOException {
        if (data == null || data.length != 6) throw new IOException("bad size");
        var in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != VERSION) throw new IOException("bad version");
        int kind = in.readUnsignedByte();
        if (kind < C_ENTER || kind > C_EXIT) throw new IOException("unknown action");
        return new Action(kind, in.readInt());
    }

    private static Holder readHolder(DataInputStream in) throws IOException {
        return new Holder(in.readLong(), in.readUTF(), new Title(in.readUTF(), in.readInt()), new Title(in.readUTF(), in.readInt()));
    }

    private static List<byte[]> readItems(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        if (n > MAX_ITEMS) throw new IOException("too many items");
        var list = new ArrayList<byte[]>(n);
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            if (len < 0 || len > 65536) throw new IOException("bad item size");
            byte[] b = new byte[len];
            in.readFully(b);
            list.add(b);
        }
        return List.copyOf(list);
    }
}
