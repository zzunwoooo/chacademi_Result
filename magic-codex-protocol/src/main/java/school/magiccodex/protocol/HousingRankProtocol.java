package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 하우징 랭킹 (오픈 전 메모 #51, 승인 레퍼런스 "하우징_랭킹" 2026-10-25). 기존 하우징 채널과 따로 쓰는 추가 채널이라 예전 패킷·방문 권한은 그대로.
 * <pre>
 * 클라 → 서버: u8 1 · u8 kind
 *   1 QUERY  i64 generation · u8 metric · i32 page
 *   2 VISIT  UUID owner                      (방 이동 — 서버가 기존 친구 방문 규칙으로 다시 판단)
 * 서버 → 클라: u8 1 · u8 kind
 *   1 OPEN   u8 metric                       (/하우징랭킹)
 *   2 PAGE   i64 generation · u8 metric · i32 page · i32 pages · UTF error · u8 n · n × ROW
 *            ROW = i32 rank · UUID owner · UTF name · i32 today · i32 total · i32 hearts · u8 visit
 *   3 NOTICE UTF text                        (방 이동 거절 등)
 * </pre>
 * metric: 0 일일 방문 · 1 누적 방문 · 2 하트. 한 쪽 6줄. visit: 0 친구 전용(못 감) · 1 방 이동 · 2 내 방.
 */
public final class HousingRankProtocol {
    public static final String CHANNEL = "chacademi:housing_rank";
    public static final int VERSION = 1, PAGE_SIZE = 6, MAX_BYTES = 8000, MAX_PAGE = 100000;
    public static final int DAILY = 0, TOTAL = 1, HEARTS = 2;
    public static final int VISIT_LOCKED = 0, VISIT_OPEN = 1, VISIT_SELF = 2;

    public sealed interface ToServer permits Query, Visit {}
    public sealed interface ToClient permits Open, Page, Notice {}

    public record Query(long generation, int metric, int page) implements ToServer {
        public Query { if (generation < 1 || metric < 0 || metric > 2 || page < 0 || page > MAX_PAGE) throw new IllegalArgumentException("query"); }
    }
    public record Visit(UUID owner) implements ToServer { public Visit { Objects.requireNonNull(owner); } }
    public record Open(int metric) implements ToClient { public Open { if (metric < 0 || metric > 2) throw new IllegalArgumentException("metric"); } }
    public record Row(int rank, UUID owner, String name, int today, int total, int hearts, int visit) {
        public Row {
            Objects.requireNonNull(owner);
            if (rank < 1 || name == null || name.length() > 32 || today < 0 || total < 0 || hearts < 0 || visit < 0 || visit > 2) throw new IllegalArgumentException("row");
        }
    }
    public record Page(long generation, int metric, int page, int pages, List<Row> rows, String error) implements ToClient {
        public Page {
            rows = List.copyOf(rows);
            if (metric < 0 || metric > 2 || page < 0 || pages < 1 || page >= pages || pages > MAX_PAGE + 1 || rows.size() > PAGE_SIZE || error == null || error.length() > 200)
                throw new IllegalArgumentException("page");
        }
    }
    public record Notice(String text) implements ToClient { public Notice { if (text == null || text.isBlank() || text.length() > 200) throw new IllegalArgumentException("notice"); } }

    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] bytes(int kind, Writer w) {
        try {
            var b = new ByteArrayOutputStream(); var out = new DataOutputStream(b);
            out.writeByte(VERSION); out.writeByte(kind); w.write(out);
            if (b.size() > MAX_BYTES) throw new IllegalArgumentException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    private static void uuid(DataOutputStream o, UUID id) throws IOException { o.writeLong(id.getMostSignificantBits()); o.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    public static byte[] encode(ToServer m) {
        return switch (m) {
            case Query q -> bytes(1, o -> { o.writeLong(q.generation()); o.writeByte(q.metric()); o.writeInt(q.page()); });
            case Visit v -> bytes(2, o -> uuid(o, v.owner()));
        };
    }
    public static byte[] encode(ToClient m) {
        return switch (m) {
            case Open op -> bytes(1, o -> o.writeByte(op.metric()));
            case Page p -> bytes(2, o -> {
                o.writeLong(p.generation()); o.writeByte(p.metric()); o.writeInt(p.page()); o.writeInt(p.pages()); o.writeUTF(p.error()); o.writeByte(p.rows().size());
                for (var r : p.rows()) { o.writeInt(r.rank()); uuid(o, r.owner()); o.writeUTF(r.name()); o.writeInt(r.today()); o.writeInt(r.total()); o.writeInt(r.hearts()); o.writeByte(r.visit()); }
            });
            case Notice n -> bytes(3, o -> o.writeUTF(n.text()));
        };
    }

    private static DataInputStream input(byte[] b) throws IOException {
        if (b == null || b.length < 2 || b.length > MAX_BYTES) throw new IOException("size");
        var in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readUnsignedByte() != VERSION) throw new IOException("version");
        return in;
    }
    private static void done(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("trailing"); }

    public static ToServer toServer(byte[] b) {
        try (var in = input(b)) {
            ToServer m = switch (in.readUnsignedByte()) {
                case 1 -> new Query(in.readLong(), in.readUnsignedByte(), in.readInt());
                case 2 -> new Visit(uuid(in));
                default -> throw new IOException("kind");
            };
            done(in); return m;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }
    public static ToClient toClient(byte[] b) {
        try (var in = input(b)) {
            ToClient m = switch (in.readUnsignedByte()) {
                case 1 -> new Open(in.readUnsignedByte());
                case 2 -> {
                    long generation = in.readLong(); int metric = in.readUnsignedByte(), page = in.readInt(), pages = in.readInt(); String error = in.readUTF();
                    int n = in.readUnsignedByte(); if (n > PAGE_SIZE) throw new IOException("rows");
                    var rows = new ArrayList<Row>(); var seen = new HashSet<UUID>();
                    for (int i = 0; i < n; i++) {
                        var r = new Row(in.readInt(), uuid(in), in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readUnsignedByte());
                        if (!seen.add(r.owner())) throw new IOException("duplicate");
                        rows.add(r);
                    }
                    yield new Page(generation, metric, page, pages, rows, error);
                }
                case 3 -> new Notice(in.readUTF());
                default -> throw new IOException("kind");
            };
            done(in); return m;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }

    /** 표시용 숫자 (1,284). */
    public static String number(int v) { return String.format(Locale.ROOT, "%,d", v); }

    private HousingRankProtocol() {}
}
