package school.magiccodex.protocol;

import java.io.*;
import java.util.*;

/**
 * 아티팩트 동행 연출 (2026-10-26, 그룹 D): 서버는 "누가 무엇을 장착했는지"와 짧은 사건만 보내고, 모양은 각 클라가 그린다.
 * <ul>
 *   <li>STATE: 플레이어별 표시 플래그 (정령·거울 쌍둥이·앵무새). 바뀔 때 + 받는 사람이 같은 세계에 들어올 때만.</li>
 *   <li>SPIRIT_SHOT / TWIN_ECHO: 정령 불덩이·쌍둥이 따라 쓰기 (시전자 UUID, 대상 엔티티 ID).</li>
 *   <li>BUBBLE: 앵무새 말풍선 (서버가 {@value #MAX_BUBBLE}자로 자른 채팅).</li>
 * </ul>
 */
public final class ArtifactFxProtocol {
    public static final String CHANNEL = "magiccodex:artifact_fx";
    public static final int VERSION = 1, MAX_BYTES = 8192, MAX_STATES = 128, MAX_BUBBLE = 20;
    public static final int SPIRIT = 1, TWIN = 2, PARROT = 4, ALL = SPIRIT | TWIN | PARROT;
    public static final int STATE = 1, SPIRIT_SHOT = 2, TWIN_ECHO = 3, BUBBLE = 4;

    public sealed interface Message permits States, Shot, Bubble {}
    /** replace=true: 이 목록이 받는 사람 세계의 전체 상태 (세계 진입 때). false: 일부만 갱신. */
    public record States(boolean replace, Map<UUID, Integer> flags) implements Message {
        public States { if (flags == null || flags.size() > MAX_STATES) throw new IllegalArgumentException("states"); for (int f : flags.values()) if (f < 0 || f > ALL) throw new IllegalArgumentException("flag"); flags = Map.copyOf(flags); }
    }
    /** kind = SPIRIT_SHOT 또는 TWIN_ECHO. */
    public record Shot(int kind, UUID caster, int target) implements Message {
        public Shot { if ((kind != SPIRIT_SHOT && kind != TWIN_ECHO) || caster == null || target < 0) throw new IllegalArgumentException("shot"); }
    }
    public record Bubble(UUID speaker, String text) implements Message {
        public Bubble { if (speaker == null || text == null || text.isBlank() || text.codePointCount(0, text.length()) > MAX_BUBBLE + 1) throw new IllegalArgumentException("bubble"); }
    }

    /** 채팅을 말풍선 글자로: 서식·제어 문자를 빼고 {@value #MAX_BUBBLE}자가 넘으면 자르고 "…". 남는 게 없으면 "". */
    public static String bubbleText(String raw) {
        if (raw == null) return "";
        var sb = new StringBuilder();
        raw.replaceAll("§.?", "").codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != 0xA7)
                .forEach(sb::appendCodePoint);
        String s = sb.toString().strip().replaceAll("\\s+", " ");
        if (s.codePointCount(0, s.length()) <= MAX_BUBBLE) return s;
        return s.substring(0, s.offsetByCodePoints(0, MAX_BUBBLE)).stripTrailing() + "…";
    }

    public static byte[] encode(Message m) {
        try {
            var b = new ByteArrayOutputStream(); var d = new DataOutputStream(b);
            d.writeByte(VERSION);
            switch (m) {
                case States s -> {
                    d.writeByte(STATE); d.writeBoolean(s.replace()); d.writeShort(s.flags().size());
                    for (var e : s.flags().entrySet()) { uuid(d, e.getKey()); d.writeByte(e.getValue()); }
                }
                case Shot s -> { d.writeByte(s.kind()); uuid(d, s.caster()); d.writeInt(s.target()); }
                case Bubble s -> { d.writeByte(BUBBLE); uuid(d, s.speaker()); d.writeUTF(s.text()); }
            }
            byte[] out = b.toByteArray();
            if (out.length > MAX_BYTES) throw new IllegalArgumentException("size");
            return out;
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static Message decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("size");
        try {
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            if (d.readUnsignedByte() != VERSION) throw new IllegalArgumentException("version");
            int type = d.readUnsignedByte();
            Message m = switch (type) {
                case STATE -> {
                    boolean replace = d.readBoolean(); int n = d.readUnsignedShort();
                    if (n > MAX_STATES) throw new IllegalArgumentException("count");
                    var map = new HashMap<UUID, Integer>();
                    for (int i = 0; i < n; i++) map.put(uuid(d), d.readUnsignedByte());
                    yield new States(replace, map);
                }
                case SPIRIT_SHOT, TWIN_ECHO -> new Shot(type, uuid(d), d.readInt());
                case BUBBLE -> new Bubble(uuid(d), d.readUTF());
                default -> throw new IllegalArgumentException("type");
            };
            if (d.available() != 0) throw new IllegalArgumentException("trailing");
            return m;
        } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    private static void uuid(DataOutputStream d, UUID u) throws IOException { d.writeLong(u.getMostSignificantBits()); d.writeLong(u.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream d) throws IOException { return new UUID(d.readLong(), d.readLong()); }
    private ArtifactFxProtocol() {}
}
