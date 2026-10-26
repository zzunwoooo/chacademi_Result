package school.magiccodex.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 큐디치 (arena server plugin ChacaKyudichi ↔ MagicCodex client), channel {@value #CHANNEL}.
 * Every message: u8 version, u8 kind. Strict: unknown kinds, out-of-range values and trailing bytes throw IllegalArgumentException.
 *
 * <pre>
 * S2C 1 MATCH   u8 phase · u8 myTeam(0 A,1 B,2 관전) · u16 scoreA · u16 scoreB · i32 timeLeftMs · u8 flags · UTF arena
 *               · u8 n · n×(UUID · UTF nick · u8 team · u8 flags)        flags MATCH: 1 스니치 등장 2 연장 4 테스트 8 포기투표중 16 1:1 대전
 *                                                                        flags player: 1 사망 2 퀘이플 소유 4 준비완료
 * S2C 2 KEY     u8 action · u16 cooldownMs · u16 holdLeftMs              action: 0 없음 1 퀘이플 잡기 2 퀘이플 던지기 3 블러저 쳐내기 4 스니치 잡기
 * S2C 3 BALLS   u8 n · n×(u8 kind · u8 index · f32 x · f32 y · f32 z · u8 flags)   kind 0 퀘이플 1 블러저 2 스니치; flags 1 소유됨 2 나를 노림
 * S2C 4 FEED    u8 type · u8 team · UTF actor · UTF target · i16 points  type 1 골 2 처치 3 스니치 4 연장 시작 5 포기
 * S2C 5 PING    u8 kind · UUID sender · UTF nick · f32 x · f32 y · f32 z · u8 hasTarget [· UUID target]
 * S2C 6 RESULT  u8 outcome(0 패 1 승 2 무 3 무효) · u16 scoreA · u16 scoreB · i16 delta · i32 rating · u8 tierBefore · u8 tierAfter
 *               · u8 n · n×(UUID · UTF nick · u8 team · u8 goals · u8 kills · u8 deaths · u8 snitch · u8 mvp)
 * S2C 7 SHRINES u8 n · n×(u8 type · f32 x · f32 y · f32 z)              type 0 체력 1 마나 2 안정성 3 가속
 * S2C 8 ROSTER  u8 n · n×(UUID · i8 house(-1 없음) · UTF broomId · i32 rating)   준비 화면용, 경기 시작·입장 때
 * S2C 9 BOUNDS  f32 minX · f32 minY · f32 minZ · f32 maxX · f32 maxY · f32 maxZ   경기장 경계 (가까이 가면 반투명 제한선, 2026-10-20)
 * C2S 1 PING    u8 kind · f32 x · f32 y · f32 z · u8 hasTarget [· UUID target]
 * C2S 2 FORFEIT u8 yes
 * C2S 3 READY   u8 ready
 * C2S 4 AIM     f32 x · f32 y · f32 z   공중 조준점 (바로 뒤 시전 하나에만 쓰인다; 휠로 고른 거리)
 * </pre>
 * Ping kinds: 0 위치 1 아군 2 적 3 경고 4 가는 중 5 도움 6 힐 줘 7 퀘이플 8 블러저 9 스니치 10 체력 성소 11 마나 성소 12 안정성 성소 13 골대 14 가속 성소.
 */
public final class KyudichiProtocol {
    public static final String CHANNEL = "chacademi:kyudichi";
    public static final int VERSION = 1, MAX_BYTES = 8192;
    public static final int MATCH = 1, KEY = 2, BALLS = 3, FEED = 4, PING = 5, RESULT = 6, SHRINES = 7, ROSTER = 8, BOUNDS = 9;
    public static final int C_PING = 1, C_FORFEIT = 2, C_READY = 3, C_AIM = 4, C_SPECTATE_LEAVE = 5;
    public static final int PING_KINDS = 15;

    private KyudichiProtocol() {}

    public sealed interface Message permits Match, Key, Balls, Feed, Ping, Result, Shrines, Roster, Bounds {}
    public record Person(UUID id, String nick, int team, int flags) {}
    public record Match(int phase, int myTeam, int scoreA, int scoreB, int timeLeftMs, int flags, String arena, List<Person> players) implements Message {}
    public record Key(int action, int cooldownMs, int holdLeftMs) implements Message {}
    public record Ball(int kind, int index, float x, float y, float z, int flags) {}
    public record Balls(List<Ball> balls) implements Message {}
    public record Feed(int type, int team, String actor, String target, int points) implements Message {}
    public record Ping(int kind, UUID sender, String nick, float x, float y, float z, UUID target) implements Message {}
    public record Line(UUID id, String nick, int team, int goals, int kills, int deaths, int snitch, boolean mvp) {}
    public record Result(int outcome, int scoreA, int scoreB, int delta, int rating, int tierBefore, int tierAfter, List<Line> lines) implements Message {}
    public record Shrine(int type, float x, float y, float z) {}
    public record Shrines(List<Shrine> shrines) implements Message {}
    public record Entry(UUID id, int house, String broom, int rating) {}
    public record Roster(List<Entry> entries) implements Message {}
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) implements Message {
        public Bounds {
            for (float v : new float[]{minX, minY, minZ, maxX, maxY, maxZ}) if (!Float.isFinite(v)) throw new IllegalArgumentException("bounds");
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) throw new IllegalArgumentException("bounds");
        }
    }

    /** Client requests. */
    public sealed interface Request permits PingRequest, Forfeit, Ready, Aim, SpectateLeave {}
    public record PingRequest(int kind, float x, float y, float z, UUID target) implements Request {}
    public record Forfeit(boolean yes) implements Request {}
    public record Ready(boolean ready) implements Request {}
    public record Aim(float x, float y, float z) implements Request {}
    /** 관전 화면의 "나가기" 버튼 (2026-10-20 명령어 정리: /큐디치 관전 나가기 대신). */
    public record SpectateLeave() implements Request {}

    // ------------------------------------------------------------------ encode
    public static byte[] encode(Message m) {
        return write(o -> {
            o.writeByte(VERSION);
            switch (m) {
                case Match x -> {
                    o.writeByte(MATCH); u8(o, x.phase, 6); u8(o, x.myTeam, 2); u16(o, x.scoreA); u16(o, x.scoreB);
                    o.writeInt(Math.max(0, x.timeLeftMs)); u8(o, x.flags, 255); utf(o, x.arena, 32); count(o, x.players.size(), 16);
                    for (Person p : x.players) { uuid(o, p.id); utf(o, p.nick, 32); u8(o, p.team, 2); u8(o, p.flags, 7); }
                }
                case Key x -> { o.writeByte(KEY); u8(o, x.action, 4); u16(o, x.cooldownMs); u16(o, x.holdLeftMs); }
                case Balls x -> {
                    o.writeByte(BALLS); count(o, x.balls.size(), 8);
                    for (Ball b : x.balls) { u8(o, b.kind, 2); u8(o, b.index, 7); f(o, b.x); f(o, b.y); f(o, b.z); u8(o, b.flags, 3); }
                }
                case Feed x -> { o.writeByte(FEED); u8(o, x.type, 5); u8(o, x.team, 2); utf(o, x.actor, 32); utf(o, x.target, 32); i16(o, x.points); }
                case Ping x -> {
                    o.writeByte(PING); u8(o, x.kind, PING_KINDS - 1); uuid(o, x.sender); utf(o, x.nick, 32); f(o, x.x); f(o, x.y); f(o, x.z);
                    o.writeByte(x.target == null ? 0 : 1); if (x.target != null) uuid(o, x.target);
                }
                case Result x -> {
                    o.writeByte(RESULT); u8(o, x.outcome, 3); u16(o, x.scoreA); u16(o, x.scoreB); i16(o, x.delta); o.writeInt(x.rating);
                    u8(o, x.tierBefore, 8); u8(o, x.tierAfter, 8); count(o, x.lines.size(), 16);
                    for (Line l : x.lines) {
                        uuid(o, l.id); utf(o, l.nick, 32); u8(o, l.team, 1);
                        u8(o, Math.min(255, l.goals), 255); u8(o, Math.min(255, l.kills), 255); u8(o, Math.min(255, l.deaths), 255); u8(o, Math.min(255, l.snitch), 255);
                        o.writeByte(l.mvp ? 1 : 0);
                    }
                }
                case Shrines x -> {
                    o.writeByte(SHRINES); count(o, x.shrines.size(), 16);
                    for (Shrine s : x.shrines) { u8(o, s.type, 3); f(o, s.x); f(o, s.y); f(o, s.z); }
                }
                case Roster x -> {
                    o.writeByte(ROSTER); count(o, x.entries.size(), 16);
                    for (Entry e : x.entries) { uuid(o, e.id); o.writeByte(Math.clamp(e.house, -1, 3)); utf(o, e.broom, 64); o.writeInt(e.rating); }
                }
                case Bounds x -> { o.writeByte(BOUNDS); f(o, x.minX); f(o, x.minY); f(o, x.minZ); f(o, x.maxX); f(o, x.maxY); f(o, x.maxZ); }
            }
        });
    }

    public static byte[] encode(Request r) {
        return write(o -> {
            o.writeByte(VERSION);
            switch (r) {
                case PingRequest x -> {
                    o.writeByte(C_PING); u8(o, x.kind, PING_KINDS - 1); f(o, x.x); f(o, x.y); f(o, x.z);
                    o.writeByte(x.target == null ? 0 : 1); if (x.target != null) uuid(o, x.target);
                }
                case Forfeit x -> { o.writeByte(C_FORFEIT); o.writeByte(x.yes ? 1 : 0); }
                case Ready x -> { o.writeByte(C_READY); o.writeByte(x.ready ? 1 : 0); }
                case Aim x -> { o.writeByte(C_AIM); f(o, x.x); f(o, x.y); f(o, x.z); }
                case SpectateLeave x -> o.writeByte(C_SPECTATE_LEAVE);
            }
        });
    }

    // ------------------------------------------------------------------ decode
    public static Message decode(byte[] bytes) {
        return read(bytes, i -> {
            int kind = i.readUnsignedByte();
            return switch (kind) {
                case MATCH -> {
                    int phase = r8(i, 6), team = r8(i, 2), a = i.readUnsignedShort(), b = i.readUnsignedShort(), t = i.readInt();
                    if (t < 0) throw new IllegalArgumentException("time");
                    int flags = i.readUnsignedByte(); String arena = rutf(i, 32); int n = r8(i, 16);
                    var ps = new ArrayList<Person>();
                    for (int k = 0; k < n; k++) ps.add(new Person(ruuid(i), rutf(i, 32), r8(i, 2), r8(i, 7)));
                    yield new Match(phase, team, a, b, t, flags, arena, List.copyOf(ps));
                }
                case KEY -> new Key(r8(i, 4), i.readUnsignedShort(), i.readUnsignedShort());
                case BALLS -> {
                    int n = r8(i, 8); var bs = new ArrayList<Ball>();
                    for (int k = 0; k < n; k++) bs.add(new Ball(r8(i, 2), r8(i, 7), rf(i), rf(i), rf(i), r8(i, 3)));
                    yield new Balls(List.copyOf(bs));
                }
                case FEED -> new Feed(r8(i, 5), r8(i, 2), rutf(i, 32), rutf(i, 32), i.readShort());
                case PING -> {
                    int k = r8(i, PING_KINDS - 1); UUID s = ruuid(i); String nick = rutf(i, 32); float x = rf(i), y = rf(i), z = rf(i);
                    yield new Ping(k, s, nick, x, y, z, rflag(i) ? ruuid(i) : null);
                }
                case RESULT -> {
                    int out = r8(i, 3), a = i.readUnsignedShort(), b = i.readUnsignedShort(), d = i.readShort(), rating = i.readInt(), tb = r8(i, 8), ta = r8(i, 8), n = r8(i, 16);
                    var ls = new ArrayList<Line>();
                    for (int k = 0; k < n; k++) ls.add(new Line(ruuid(i), rutf(i, 32), r8(i, 1), i.readUnsignedByte(), i.readUnsignedByte(), i.readUnsignedByte(), i.readUnsignedByte(), rflag(i)));
                    yield new Result(out, a, b, d, rating, tb, ta, List.copyOf(ls));
                }
                case SHRINES -> {
                    int n = r8(i, 16); var ss = new ArrayList<Shrine>();
                    for (int k = 0; k < n; k++) ss.add(new Shrine(r8(i, 3), rf(i), rf(i), rf(i)));
                    yield new Shrines(List.copyOf(ss));
                }
                case ROSTER -> {
                    int n = r8(i, 16); var es = new ArrayList<Entry>();
                    for (int k = 0; k < n; k++) {
                        UUID id = ruuid(i); int h = i.readByte(); if (h < -1 || h > 3) throw new IllegalArgumentException("house");
                        es.add(new Entry(id, h, rutf(i, 64), i.readInt()));
                    }
                    yield new Roster(List.copyOf(es));
                }
                case BOUNDS -> new Bounds(rf(i), rf(i), rf(i), rf(i), rf(i), rf(i));
                default -> throw new IllegalArgumentException("kind " + kind);
            };
        });
    }

    public static Request decodeRequest(byte[] bytes) {
        return read(bytes, i -> {
            int kind = i.readUnsignedByte();
            return switch (kind) {
                case C_PING -> {
                    int k = r8(i, PING_KINDS - 1); float x = rf(i), y = rf(i), z = rf(i);
                    yield new PingRequest(k, x, y, z, rflag(i) ? ruuid(i) : null);
                }
                case C_FORFEIT -> new Forfeit(rflag(i));
                case C_READY -> new Ready(rflag(i));
                case C_AIM -> new Aim(rf(i), rf(i), rf(i));
                case C_SPECTATE_LEAVE -> new SpectateLeave();
                default -> throw new IllegalArgumentException("kind " + kind);
            };
        });
    }

    // ------------------------------------------------------------------ helpers
    private interface Writer { void write(DataOutputStream o) throws IOException; }
    private interface Reader<T> { T read(DataInputStream i) throws IOException; }

    private static byte[] write(Writer w) {
        var bytes = new ByteArrayOutputStream(256);
        try (var o = new DataOutputStream(bytes)) { w.write(o); } catch (IOException e) { throw new UncheckedIOException(e); }
        if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("too large");
        return bytes.toByteArray();
    }
    private static <T> T read(byte[] bytes, Reader<T> r) {
        if (bytes == null || bytes.length < 2 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        try (var i = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (i.readUnsignedByte() != VERSION) throw new IllegalArgumentException("version");
            T t = r.read(i);
            if (i.available() != 0) throw new IllegalArgumentException("trailing bytes");
            return t;
        } catch (IOException e) { throw new IllegalArgumentException("truncated", e); }
    }
    private static void u8(DataOutputStream o, int v, int max) throws IOException { if (v < 0 || v > max) throw new IllegalArgumentException("value " + v); o.writeByte(v); }
    private static void count(DataOutputStream o, int v, int max) throws IOException { u8(o, v, max); }
    private static void u16(DataOutputStream o, int v) throws IOException { o.writeShort(Math.clamp(v, 0, 65535)); }
    private static void i16(DataOutputStream o, int v) throws IOException { o.writeShort(Math.clamp(v, Short.MIN_VALUE, Short.MAX_VALUE)); }
    private static void f(DataOutputStream o, float v) throws IOException { if (!Float.isFinite(v)) throw new IllegalArgumentException("float"); o.writeFloat(v); }
    private static void uuid(DataOutputStream o, UUID u) throws IOException { o.writeLong(u.getMostSignificantBits()); o.writeLong(u.getLeastSignificantBits()); }
    private static void utf(DataOutputStream o, String s, int max) throws IOException {
        String v = s == null ? "" : s;
        if (v.length() > max) v = v.substring(0, max);
        o.writeUTF(v);
    }
    private static int r8(DataInputStream i, int max) throws IOException { int v = i.readUnsignedByte(); if (v > max) throw new IllegalArgumentException("value " + v); return v; }
    private static float rf(DataInputStream i) throws IOException { float v = i.readFloat(); if (!Float.isFinite(v)) throw new IllegalArgumentException("float"); return v; }
    private static UUID ruuid(DataInputStream i) throws IOException { return new UUID(i.readLong(), i.readLong()); }
    private static boolean rflag(DataInputStream i) throws IOException { int v = i.readUnsignedByte(); if (v > 1) throw new IllegalArgumentException("flag"); return v == 1; }
    private static String rutf(DataInputStream i, int max) throws IOException { String s = i.readUTF(); if (s.length() > max) throw new IllegalArgumentException("text"); return s; }
}
