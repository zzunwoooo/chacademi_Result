package kr.chacademi.npcmodel.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * NPC 외형 패킷 계약 (ChacaNPC 서버 ↔ chacademi-npc-model 클라이언트 모드). 마인크래프트·Paper 의존 없음.
 *
 * <p>채널: 서버→클라 {@link #CHANNEL_S2C}, 클라→서버 {@link #CHANNEL_C2S}. 모든 메시지 = [버전 1바이트][종류 1바이트][본문].
 * 버전이 다르면 받는 쪽이 통째로 무시한다 (클라이언트는 기존 외형 유지, 서버는 그 플레이어에게 보내지 않음).</p>
 *
 * <p>서버는 NPC를 보고 있는 플레이어에게만, 추적 시작·모델 변경·동작 변경 때만 보낸다. 위치·회전은 바닐라 엔티티 동기화를 그대로 쓴다.
 * 엔티티는 (entityId, UUID) 둘 다 맞아야 연결되고, revision 이 낮은 패킷은 버린다 (재사용된 엔티티 번호에 예전 외형이 붙지 않게).</p>
 */
public final class NpcModelProtocol {
    public static final int VERSION = 2; // 2: APPEAR 에 고정 방향(yaw) 추가
    public static final String CHANNEL_S2C = "chacademi:npc_model";
    public static final String CHANNEL_C2S = "chacademi:npc_model_c";

    public static final int S2C_HELLO_ACK = 0, S2C_APPEAR = 1, S2C_STATE = 2, S2C_CLEAR = 3, S2C_RESET = 4;
    public static final int C2S_HELLO = 0;

    public static final int STATE_IDLE = 0, STATE_TALK = 1;
    public static final int MAX_MESSAGE = 256;
    public static final float MIN_SCALE = 0.25f, MAX_SCALE = 10f;
    public static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private NpcModelProtocol() {
    }

    // ---------------------------------------------------------------- 메시지

    public sealed interface Message permits HelloAck, Appear, State, Clear, Reset, Hello {
    }

    /** 서버 세션 번호: 서버 켜질 때마다 새 값. 바뀌면 클라이언트는 받은 매핑을 전부 버린다. */
    public record HelloAck(long session) implements Message {
    }

    /** yaw: 모델 고정 방향(마인크래프트 yaw, 0=남 90=서 180=북 270=동). NaN = 엔티티 몸 방향을 따라감. */
    public record Appear(int entityId, UUID uuid, int revision, String modelId, float scale, float yaw, int base, int greetSeq) implements Message {
        public Appear {
            if (!Float.isNaN(yaw) && !(yaw >= 0 && yaw < 360)) {
                throw new IllegalArgumentException("yaw");
            }
            if (!MODEL_ID.matcher(modelId).matches()) {
                throw new IllegalArgumentException("modelId");
            }
            if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) {
                throw new IllegalArgumentException("scale");
            }
        }
    }

    public record State(int entityId, UUID uuid, int revision, int base, int greetSeq) implements Message {
    }

    /** 이 엔티티의 커스텀 외형 해제 → 기존 외형. */
    public record Clear(int entityId, UUID uuid, int revision) implements Message {
    }

    /** 이 서버에서 받은 외형 전부 해제 (설정 새로고침 직후, 다시 APPEAR 가 온다). */
    public record Reset() implements Message {
    }

    public record Hello(int clientVersion) implements Message {
    }

    // ---------------------------------------------------------------- 인코딩

    public static byte[] encode(Message m) {
        Out o = new Out();
        o.b.write(VERSION);
        switch (m) {
            case HelloAck h -> {
                o.b.write(S2C_HELLO_ACK);
                o.longBE(h.session());
            }
            case Appear a -> {
                o.b.write(S2C_APPEAR);
                o.varInt(a.entityId());
                o.uuid(a.uuid());
                o.varInt(a.revision());
                o.string(a.modelId());
                o.floatBE(a.scale());
                o.floatBE(a.yaw());
                o.b.write(a.base());
                o.varInt(a.greetSeq());
            }
            case State s -> {
                o.b.write(S2C_STATE);
                o.varInt(s.entityId());
                o.uuid(s.uuid());
                o.varInt(s.revision());
                o.b.write(s.base());
                o.varInt(s.greetSeq());
            }
            case Clear c -> {
                o.b.write(S2C_CLEAR);
                o.varInt(c.entityId());
                o.uuid(c.uuid());
                o.varInt(c.revision());
            }
            case Reset r -> o.b.write(S2C_RESET);
            case Hello h -> {
                o.b.write(C2S_HELLO);
                o.varInt(h.clientVersion());
            }
        }
        return o.b.toByteArray();
    }

    /** 서버→클라 메시지 해석. 버전이 다르거나 깨졌으면 null. */
    public static Message decodeS2C(byte[] data) {
        try {
            In in = new In(data);
            if (data.length > MAX_MESSAGE || in.u8() != VERSION) {
                return null;
            }
            int type = in.u8();
            Message m = switch (type) {
                case S2C_HELLO_ACK -> new HelloAck(in.longBE());
                case S2C_APPEAR -> new Appear(in.varInt(), in.uuid(), in.varInt(), in.string(32), in.floatBE(), in.floatBE(), base(in.u8()), in.varInt());
                case S2C_STATE -> new State(in.varInt(), in.uuid(), in.varInt(), base(in.u8()), in.varInt());
                case S2C_CLEAR -> new Clear(in.varInt(), in.uuid(), in.varInt());
                case S2C_RESET -> new Reset();
                default -> null;
            };
            return in.done() ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 클라→서버 HELLO 해석. 버전 바이트가 다르면 Hello(그 버전) 으로 돌려줘서 서버가 로그를 남길 수 있게 한다. */
    public static Hello decodeHello(byte[] data) {
        try {
            In in = new In(data);
            if (data.length > MAX_MESSAGE) {
                return null;
            }
            int ver = in.u8();
            if (ver != VERSION) {
                return new Hello(ver);
            }
            if (in.u8() != C2S_HELLO) {
                return null;
            }
            Hello h = new Hello(in.varInt());
            return in.done() ? h : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int base(int v) {
        if (v != STATE_IDLE && v != STATE_TALK) {
            throw new IllegalArgumentException("state");
        }
        return v;
    }

    // ---------------------------------------------------------------- 바이트 도우미 (VarInt·UTF8·UUID 는 바닐라 ByteBufCodecs 와 같은 형식)

    static final class Out {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(64);

        void varInt(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("음수 varint");
            }
            while ((v & ~0x7F) != 0) {
                b.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b.write(v);
        }

        void string(String s) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            varInt(u.length);
            b.write(u, 0, u.length);
        }

        void longBE(long v) {
            for (int k = 7; k >= 0; k--) {
                b.write((int) (v >>> (k * 8)) & 0xFF);
            }
        }

        void uuid(UUID u) {
            longBE(u.getMostSignificantBits());
            longBE(u.getLeastSignificantBits());
        }

        void floatBE(float f) {
            int v = Float.floatToIntBits(f);
            for (int k = 3; k >= 0; k--) {
                b.write((v >>> (k * 8)) & 0xFF);
            }
        }
    }

    static final class In {
        final byte[] d;
        int i;

        In(byte[] d) {
            this.d = d;
        }

        int u8() {
            if (i >= d.length) {
                throw new IllegalArgumentException("짧음");
            }
            return d[i++] & 0xFF;
        }

        int varInt() {
            int v = 0;
            for (int s = 0; s < 35; s += 7) {
                int x = u8();
                v |= (x & 0x7F) << s;
                if ((x & 0x80) == 0) {
                    if (v < 0) {
                        throw new IllegalArgumentException("음수 varint");
                    }
                    return v;
                }
            }
            throw new IllegalArgumentException("varint 너무 김");
        }

        long longBE() {
            long v = 0;
            for (int k = 0; k < 8; k++) {
                v = (v << 8) | u8();
            }
            return v;
        }

        UUID uuid() {
            return new UUID(longBE(), longBE());
        }

        float floatBE() {
            int v = 0;
            for (int k = 0; k < 4; k++) {
                v = (v << 8) | u8();
            }
            return Float.intBitsToFloat(v);
        }

        String string(int maxChars) {
            int n = varInt();
            if (n > maxChars * 4 || i + n > d.length) {
                throw new IllegalArgumentException("문자열 길이");
            }
            String s = new String(d, i, n, StandardCharsets.UTF_8);
            i += n;
            if (s.length() > maxChars) {
                throw new IllegalArgumentException("문자열 길이");
            }
            return s;
        }

        boolean done() {
            return i == d.length;
        }
    }
}
