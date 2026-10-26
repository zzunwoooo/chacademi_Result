package kr.chacademi.npcmodel.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpcModelProtocolTest {
    final UUID u = UUID.fromString("0f8a3c2e-1111-2222-3333-444455556666");

    @Test
    void roundTrip() {
        for (NpcModelProtocol.Message m : new NpcModelProtocol.Message[]{
                new NpcModelProtocol.HelloAck(-1234567890123L),
                new NpcModelProtocol.Appear(123456, u, 3, "luka", 1.05f, Float.NaN, NpcModelProtocol.STATE_TALK, 9),
                new NpcModelProtocol.Appear(5, u, 1, "wizard_statue", 3f, 270f, NpcModelProtocol.STATE_IDLE, 0),
                new NpcModelProtocol.State(7, u, 3, NpcModelProtocol.STATE_IDLE, 10),
                new NpcModelProtocol.Clear(7, u, 4),
                new NpcModelProtocol.Reset()}) {
            assertEquals(m, NpcModelProtocol.decodeS2C(NpcModelProtocol.encode(m)));
        }
        assertEquals(new NpcModelProtocol.Hello(1), NpcModelProtocol.decodeHello(NpcModelProtocol.encode(new NpcModelProtocol.Hello(1))));
    }

    @Test
    void versionMismatchAndGarbageAreIgnored() {
        byte[] b = NpcModelProtocol.encode(new NpcModelProtocol.State(7, u, 3, 0, 1));
        b[0] = 9;
        assertNull(NpcModelProtocol.decodeS2C(b), "다른 버전");
        assertNull(NpcModelProtocol.decodeS2C(new byte[]{1}), "짧음");
        assertNull(NpcModelProtocol.decodeS2C(new byte[]{1, 99}), "모르는 종류");
        byte[] extra = java.util.Arrays.copyOf(NpcModelProtocol.encode(new NpcModelProtocol.Reset()), 3);
        assertNull(NpcModelProtocol.decodeS2C(extra), "남는 바이트");
        byte[] hello = NpcModelProtocol.encode(new NpcModelProtocol.Hello(1));
        hello[0] = 9;
        assertEquals(new NpcModelProtocol.Hello(9), NpcModelProtocol.decodeHello(hello), "서버가 버전 불일치를 로그로 남길 수 있게");
    }

    @Test
    void rejectsBadModelIdAndScale() {
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "../x", 1, Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "luka", 10.5f, Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "luka", 0.2f, Float.NaN, 0, 0));
        new NpcModelProtocol.Appear(1, u, 1, "luka", 10, Float.NaN, 0, 0); // 10배까지 허용
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "luka", 1, 360f, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "luka", 1, -1f, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new NpcModelProtocol.Appear(1, u, 1, "luka", 1, Float.POSITIVE_INFINITY, 0, 0));
        // 잘못된 모델 ID 를 담은 바이트도 해석 단계에서 버린다
        byte[] b = NpcModelProtocol.encode(new NpcModelProtocol.Appear(1, u, 1, "luka", 1, Float.NaN, 0, 0));
        int at = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("luka");
        b[at] = 'L';
        assertNull(NpcModelProtocol.decodeS2C(b));
    }
}
