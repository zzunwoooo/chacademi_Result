package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 기숙사 점수 홀로그램 (오픈 전 메모 #106, 오너 2026-10-26: "게시판 없이 단순하게 홀로그램 느낌으로").
 * 서버 → 클라 하나뿐 (채널 {@link #CHANNEL}). 그 플레이어가 있는 세계의 홀로그램 위치 전부 + 네 기숙사 점수를 한 번에 보낸다
 * (접속·세계 이동·위치 추가/삭제·점수가 바뀌었을 때만. 서버는 학교 기록 메모리 캐시만 읽고 DB 를 따로 치지 않는다).
 * <pre>
 * u8 VERSION · UTF world · u8 range · 4 × i64 score (SchoolProtocol.HOUSES 순서) · u8 n · n × (f64 x · f64 y · f64 z · u8 facing)
 * </pre>
 * x·y·z = 홀로그램 가운데. range = 클라가 그리는 거리(블록).
 * facing = 앞면이 바라보는 방향, 90도 단위 고정 (오너 수정: 빌보드 아님): 0 남(+z) · 1 서(-x) · 2 북(-z) · 3 동(+x)
 * (마인크래프트 yaw/90 과 같은 번호). 앞면 쪽에서만 읽힌다.
 */
public final class HouseBoardProtocol {
    public static final String CHANNEL = "magiccodex:house_board";
    public static final int VERSION = 1, HOUSES = 4, MAX_BOARDS = 32, MAX_BYTES = 4_096, MIN_RANGE = 8, MAX_RANGE = 96;

    public static final int SOUTH = 0, WEST = 1, NORTH = 2, EAST = 3;
    public static final List<String> FACING_NAMES = List.of("남", "서", "북", "동");

    public record Board(double x, double y, double z, int facing) {
        public Board {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || facing < 0 || facing > 3) throw new IllegalArgumentException("board");
        }
    }

    /** yaw(도) → 가장 가까운 90도 방향 번호 (0 남 · 1 서 · 2 북 · 3 동). */
    public static int facingOf(float yaw) {
        if (!Float.isFinite(yaw)) return SOUTH;
        return Math.floorMod(Math.round(yaw / 90f), 4);
    }
    /** "동·서·남·북" (또는 east/west/south/north) → 방향 번호, 모르면 -1. */
    public static int facingOf(String name) {
        if (name == null) return -1;
        int i = FACING_NAMES.indexOf(name.strip());
        if (i >= 0) return i;
        return List.of("south", "west", "north", "east").indexOf(name.strip().toLowerCase(Locale.ROOT));
    }
    /** 앞면 바깥 방향 (x, z). */
    public static int[] normal(int facing) {
        return switch (facing) { case WEST -> new int[]{-1, 0}; case NORTH -> new int[]{0, -1}; case EAST -> new int[]{1, 0}; default -> new int[]{0, 1}; };
    }
    public record State(String world, int range, List<Long> scores, List<Board> boards) {
        public State {
            Objects.requireNonNull(world);
            scores = List.copyOf(scores);
            boards = List.copyOf(boards);
            if (world.length() > 128 || scores.size() != HOUSES || boards.size() > MAX_BOARDS || range < MIN_RANGE || range > MAX_RANGE)
                throw new IllegalArgumentException("state");
        }
        public static final State EMPTY = new State("", 48, List.of(0L, 0L, 0L, 0L), List.of());
    }

    public static byte[] encode(State s) {
        try {
            var b = new ByteArrayOutputStream(); var o = new DataOutputStream(b);
            o.writeByte(VERSION); o.writeUTF(s.world()); o.writeByte(s.range());
            for (long v : s.scores()) o.writeLong(v);
            o.writeByte(s.boards().size());
            for (var k : s.boards()) { o.writeDouble(k.x()); o.writeDouble(k.y()); o.writeDouble(k.z()); o.writeByte(k.facing()); }
            if (b.size() > MAX_BYTES) throw new IllegalArgumentException("size");
            return b.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static State decode(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != VERSION) throw new IOException("version");
            String world = in.readUTF();
            int range = in.readUnsignedByte();
            var scores = new ArrayList<Long>(HOUSES);
            for (int i = 0; i < HOUSES; i++) scores.add(in.readLong());
            int n = in.readUnsignedByte();
            if (n > MAX_BOARDS) throw new IOException("count");
            var boards = new ArrayList<Board>(n);
            for (int i = 0; i < n; i++) boards.add(new Board(in.readDouble(), in.readDouble(), in.readDouble(), in.readUnsignedByte()));
            if (in.available() != 0) throw new IOException("trailing");
            return new State(world, range, scores, boards);
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException(e); }
    }

    /** 점수 높은 순 기숙사 번호. 같은 점수면 기숙사 번호 순 (줄이 흔들리지 않게). */
    public static int[] order(List<Long> scores) {
        Integer[] idx = {0, 1, 2, 3};
        Arrays.sort(idx, (a, b) -> { int c = Long.compare(scores.get(b), scores.get(a)); return c != 0 ? c : Integer.compare(a, b); });
        int[] out = new int[HOUSES];
        for (int i = 0; i < HOUSES; i++) out[i] = idx[i];
        return out;
    }

    /** 기숙사별 순위 (1부터). 같은 점수는 같은 순위, 다음 순위는 건너뜀 (1·1·3·4). */
    public static int[] ranks(List<Long> scores) {
        int[] out = new int[HOUSES];
        for (int h = 0; h < HOUSES; h++) {
            int above = 0;
            for (int o = 0; o < HOUSES; o++) if (scores.get(o) > scores.get(h)) above++;
            out[h] = above + 1;
        }
        return out;
    }

    /** 기숙사별 줄 번호 (0 = 맨 위). {@link #order} 의 역. */
    public static int[] slots(List<Long> scores) {
        int[] order = order(scores), out = new int[HOUSES];
        for (int i = 0; i < HOUSES; i++) out[order[i]] = i;
        return out;
    }

    private HouseBoardProtocol() {}
}
