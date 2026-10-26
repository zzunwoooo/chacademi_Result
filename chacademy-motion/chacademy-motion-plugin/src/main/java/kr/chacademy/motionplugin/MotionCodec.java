package kr.chacademy.motionplugin;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 모드(Fabric)의 패킷 코덱과 같은 바이트 형식으로 읽고 쓴다.
 * <ul>
 *   <li>문자열: VarInt(UTF-8 바이트 길이) + UTF-8 바이트 — 마인크래프트 ByteBufCodecs.STRING_UTF8</li>
 *   <li>UUID: long(상위) + long(하위), 빅엔디언 — 마인크래프트 UUIDUtil.STREAM_CODEC</li>
 * </ul>
 * Bukkit API 에 의존하지 않아서 단위 테스트로 바로 검증할 수 있다.
 */
public final class MotionCodec {
    public static final String CHANNEL_CAST = "chacademy:cast";
    public static final String CHANNEL_ANIM = "chacademy:anim";

    /** 모션 이름 최대 길이 (바이트). 이보다 길면 잘못된 패킷으로 본다. */
    public static final int MAX_NAME_BYTES = 64;

    private MotionCodec() {}

    /** chacademy:cast 내용 = [String animation] */
    public static String decodeCast(byte[] data) throws MalformedPacketException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        try {
            String name = readString(buf, MAX_NAME_BYTES);
            if (buf.hasRemaining()) throw new MalformedPacketException("남는 바이트 " + buf.remaining());
            return name;
        } catch (BufferUnderflowException e) {
            throw new MalformedPacketException("패킷이 너무 짧음");
        }
    }

    /** chacademy:anim 내용 = [UUID player][String animation] */
    public static byte[] encodeAnim(UUID player, String animation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + 1 + animation.length());
        writeLong(out, player.getMostSignificantBits());
        writeLong(out, player.getLeastSignificantBits());
        writeString(out, animation);
        return out.toByteArray();
    }

    /** 테스트와 디버깅용. */
    public static byte[] encodeCast(String animation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, animation);
        return out.toByteArray();
    }

    /** 테스트와 디버깅용. */
    public static Object[] decodeAnim(byte[] data) throws MalformedPacketException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        try {
            UUID id = new UUID(buf.getLong(), buf.getLong());
            return new Object[]{id, readString(buf, MAX_NAME_BYTES)};
        } catch (BufferUnderflowException e) {
            throw new MalformedPacketException("패킷이 너무 짧음");
        }
    }

    // ---- VarInt / String ----

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static int readVarInt(ByteBuffer buf) throws MalformedPacketException {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 35) throw new MalformedPacketException("VarInt 가 너무 김");
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    static String readString(ByteBuffer buf, int maxBytes) throws MalformedPacketException {
        int len = readVarInt(buf);
        if (len < 0 || len > maxBytes) throw new MalformedPacketException("문자열 길이 이상: " + len);
        if (len > buf.remaining()) throw new MalformedPacketException("문자열이 잘림");
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8)) & 0xFF);
    }

    public static final class MalformedPacketException extends Exception {
        public MalformedPacketException(String message) {
            super(message);
        }
    }
}
