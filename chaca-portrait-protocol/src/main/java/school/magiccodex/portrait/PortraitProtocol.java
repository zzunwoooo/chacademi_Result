package school.magiccodex.portrait;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 플레이어 일러스트 패킷 계약 (ChacaPortrait 서버 플러그인 ↔ MagicCodex Fabric UI).
 * 서버는 본인 일러스트 PNG를 조각(CHUNK)으로 보내고, 클라이언트는 SHA-256을 확인한 뒤 디스크에 캐시한다.
 * 다시 그리기 아이템(ItemsAdder item:cutscene_ticket, 컷신 변경권)을 쓰면 서버가 PROMPT_OPEN을 보내고 클라이언트가 입력창을 연다.
 * 상대 정보창은 C_OTHER 로 다른 사람의 일러스트를 요청하고 S_OTHER_META/S_OTHER_CHUNK 로 받는다 (대상 UUID 로 구분, 2026-10-26).
 *
 * <p>이 소스는 magic-codex-fabric 과 chaca-portrait-paper 에만 포함된다.
 */
public final class PortraitProtocol {

    public static final int VERSION = 1;

    public static final String REQUEST = "magiccodex:portrait_request";
    public static final String RESPONSE = "magiccodex:portrait_response";

    /** 조각 하나의 크기. 서버→클라이언트 커스텀 패킷 상한(1MiB)보다 충분히 작게. */
    public static final int CHUNK_BYTES = 192 * 1024;
    public static final int MAX_PACKET_BYTES = CHUNK_BYTES + 2048;
    /** 일러스트 PNG 최대 크기. */
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_IMAGE_SIDE = 2048;
    public static final int MAX_PROMPT_CHARS = 100;
    public static final int MAX_PROMPT_BYTES = 400;
    public static final int MAX_STATUS_CHARS = 200;

    // 클라이언트 → 서버
    /** number = 프로토콜 버전, text = 클라이언트가 이 서버 계정용으로 캐시한 일러스트 SHA-256 (없으면 ""). */
    public static final int C_HELLO = 0;
    /** text = 다시 그리기 토큰, extra = 추가 요청 문장, flag = 진행(true) / 취소(false). */
    public static final int C_PROMPT = 1;
    /**
     * 상대 일러스트 요청 (정보창 v4, 2026-10-26): text = 대상 UUID, extra = 클라이언트가 가진 그 사람 그림 SHA-256 (없으면 "").
     * 같으면 서버는 '그대로'만 알린다. 옛 서버는 모르는 op 라 무시한다 (클라이언트는 시간이 지나면 3D 스킨으로 둔다).
     */
    public static final int C_OTHER = 2;

    // 서버 → 클라이언트
    /** number = 서버 프로토콜 버전. */
    public static final int S_HELLO_ACK = 0;
    /** text = SHA-256 ("" = 일러스트 없음), number = 전체 바이트, extra = 조각 수. */
    public static final int S_META = 1;
    /** text = SHA-256, number = 조각 번호, data = 바이트. */
    public static final int S_CHUNK = 2;
    /** text = 토큰, extra = 안내 문장. */
    public static final int S_PROMPT_OPEN = 3;
    /** extra = 안내 문장 (채팅·토스트). */
    public static final int S_STATUS = 4;
    /** text = 토큰. 입력 시간이 지나 서버가 입력창을 닫음. */
    public static final int S_PROMPT_CLOSE = 5;
    /**
     * 상대 일러스트 머리: text = 대상 UUID, extra = SHA-256 ("" = 그림 없음), number = 전체 바이트 (0 = 보낼 조각 없음),
     * flag = '그대로' (클라이언트가 보낸 SHA 와 같아 다시 보내지 않음). 원본 PNG 를 줄이지 않고 그대로 보낸다.
     */
    public static final int S_OTHER_META = 6;
    /** 상대 일러스트 조각: text = 대상 UUID, extra = SHA-256, number = 조각 번호, data = 바이트. */
    public static final int S_OTHER_CHUNK = 7;
    /** 서버가 지금 다른 그림을 보내느라 바쁨: text = 대상 UUID. 클라이언트는 잠시 뒤 다시 요청한다. */
    public static final int S_OTHER_BUSY = 8;

    private static final String SHA_RE = "[a-f0-9]{64}";
    private static final String TOKEN_RE = "[a-f0-9]{32}";

    private PortraitProtocol() {
    }

    /** 공통 패킷. 쓰지 않는 칸은 빈 문자열·0·빈 배열. */
    public record Packet(int op, String text, long number, String extra, boolean flag, byte[] data) {
        public Packet {
            text = text == null ? "" : text;
            extra = extra == null ? "" : extra;
            data = data == null ? new byte[0] : data;
        }

        public static Packet of(int op, String text, long number, String extra) {
            return new Packet(op, text, number, extra, false, null);
        }
    }

    public static boolean validSha(String s) {
        return s != null && s.matches(SHA_RE);
    }

    public static boolean validToken(String s) {
        return s != null && s.matches(TOKEN_RE);
    }

    /** 상대 일러스트 요청·응답의 대상 UUID (소문자 36자 표준 형식만). null = 형식이 틀림. */
    public static java.util.UUID uuid(String s) {
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return null;
        }
        return java.util.UUID.fromString(s);
    }

    /** 추가 요청 문장 검사: 100 코드포인트·UTF-8 400바이트 이하, 제어문자·§ 금지. 빈 문장은 허용(기본 그림). */
    public static boolean validPrompt(String s) {
        if (s == null) {
            return false;
        }
        if (s.codePointCount(0, s.length()) > MAX_PROMPT_CHARS
                || s.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            return false;
        }
        return s.codePoints().noneMatch(c -> c < 0x20 || c == 0x7F || c == '§' || (c >= 0x80 && c < 0xA0));
    }

    public static byte[] encode(Packet p) {
        if (p.data().length > CHUNK_BYTES) {
            throw new IllegalArgumentException("chunk too large");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + p.data().length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeByte(p.op());
            writeString(out, p.text(), 256);
            out.writeLong(p.number());
            writeString(out, p.extra(), 1200);
            out.writeBoolean(p.flag());
            out.writeInt(p.data().length);
            out.write(p.data());
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("packet too large");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Packet decode(byte[] raw) {
        if (raw == null || raw.length < 4 || raw.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("bad length");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("version");
            }
            int op = in.readUnsignedByte();
            String text = readString(in, 256);
            long number = in.readLong();
            String extra = readString(in, 1200);
            boolean flag = in.readBoolean();
            int n = in.readInt();
            if (n < 0 || n > CHUNK_BYTES || n > in.available()) {
                throw new IllegalArgumentException("data length");
            }
            byte[] data = in.readNBytes(n);
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing");
            }
            return new Packet(op, text, number, extra, flag, data);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 조각 수 (올림). */
    public static int chunks(int totalBytes) {
        return totalBytes <= 0 ? 0 : (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    private static void writeString(DataOutputStream out, String s, int maxBytes) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > maxBytes) {
            throw new IllegalArgumentException("string too long");
        }
        out.writeShort(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int n = in.readUnsignedShort();
        if (n > maxBytes || n > in.available()) {
            throw new IllegalArgumentException("string length");
        }
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }
}
