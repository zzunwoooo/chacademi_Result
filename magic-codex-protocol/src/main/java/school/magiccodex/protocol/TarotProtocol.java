package school.magiccodex.protocol;

import java.io.*;

/**
 * 마법 힌트권 「운명의 타로」 창 (오너 디자인 납품 2026-10-26, FINAL_PATCHES §41). 서버 → 클라이언트 한 방향.
 * 서버가 별(1~3)·힌트 문장·카드 그림 번호(0~19)를 미리 정하고 힌트권 1개를 쓴 뒤 이 묶음을 보낸다.
 * 클라이언트는 보여 주기만 한다: 카드 세 장 중 무엇을 골라도 결과는 같다 (고르기는 연출).
 * 그림 번호는 힌트·별과 상관없는 겉모습 (오너 명세: 그림으로 주문을 정하지 않음).
 * 이 채널을 듣지 않는 클라이언트(모드 없음·예전 모드)에게는 보내지 않고 예전 알림·채팅으로 보여 준다.
 *
 * <pre>
 * S2C  int version, byte stars(1~3), byte illustration(0~19), UTF line
 * </pre>
 */
public final class TarotProtocol {
    public static final String CHANNEL = "magiccodex:tarot_hint";
    public static final int VERSION = 1;
    public static final int ILLUSTRATIONS = 20, MAX_LINE = 400, MAX_BYTES = 2048;

    /** 보여 줄 카드 한 장. line 은 줄바꿈 없이 MAX_LINE 자 안으로 자른다. */
    public record Card(int stars, int illustration, String line) {
        public Card {
            if (stars < 1 || stars > 3) throw new IllegalArgumentException("tarot stars");
            if (illustration < 0 || illustration >= ILLUSTRATIONS) throw new IllegalArgumentException("tarot illustration");
            line = clip(line);
            if (line.isEmpty()) throw new IllegalArgumentException("tarot line");
        }
    }

    static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > MAX_LINE ? t.substring(0, MAX_LINE) : t;
    }

    public static byte[] encode(Card c) {
        try {
            var b = new ByteArrayOutputStream();
            var d = new DataOutputStream(b);
            d.writeInt(VERSION); d.writeByte(c.stars()); d.writeByte(c.illustration()); d.writeUTF(c.line());
            if (b.size() > MAX_BYTES) throw new IOException("too large");
            return b.toByteArray();
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid tarot packet", e); }
    }

    public static Card decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("size");
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            if (d.readInt() != VERSION) throw new IOException("version");
            var card = new Card(d.readUnsignedByte(), d.readUnsignedByte(), d.readUTF());
            if (d.available() != 0) throw new IOException("trailing");
            return card;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid tarot packet", e); }
    }

    private TarotProtocol() {}
}
