package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.MegaphoneProtocol;
import school.magiccodex.protocol.MegaphoneProtocol.*;

/** 확성기 (메모 #102) 프레임·요청 왕복과 크기 제한. */
class MegaphoneProtocolTest {
    @Test void openShowCloseRoundTrip() {
        var o = MegaphoneProtocol.frame(MegaphoneProtocol.open(new Open(42, 80)));
        assertEquals(MegaphoneProtocol.OPEN, o.kind());
        assertEquals(42, o.open().token());
        assertEquals(80, o.open().maxChars());

        byte[] item = {1, 2, 3, 4};
        var s = MegaphoneProtocol.frame(MegaphoneProtocol.show(new Show("id-1", "차카냥", "광장에서 만나요", 6000, item)));
        assertEquals(MegaphoneProtocol.SHOW, s.kind());
        assertEquals("차카냥", s.show().sender());
        assertEquals("광장에서 만나요", s.show().text());
        assertEquals(6000, s.show().durationMs());
        assertArrayEquals(item, s.show().item());

        var none = MegaphoneProtocol.frame(MegaphoneProtocol.show(new Show("id-2", "차카냥", "아이템 없음", 6000, null)));
        assertEquals(0, none.show().item().length);

        var c = MegaphoneProtocol.frame(MegaphoneProtocol.close(42));
        assertEquals(MegaphoneProtocol.CLOSE, c.kind());
        assertEquals(42, c.closeToken());
    }

    @Test void requestRoundTrip() {
        var send = MegaphoneProtocol.request(MegaphoneProtocol.request(new Request(MegaphoneProtocol.SEND, 7, "안녕하세요")));
        assertEquals(MegaphoneProtocol.SEND, send.action());
        assertEquals(7, send.token());
        assertEquals("안녕하세요", send.text());
        var cancel = MegaphoneProtocol.request(MegaphoneProtocol.request(new Request(MegaphoneProtocol.CANCEL, 7, "무시됨")));
        assertEquals(MegaphoneProtocol.CANCEL, cancel.action());
        assertEquals("", cancel.text());
    }

    @Test void limitsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new Open(0, 80));
        assertThrows(IllegalArgumentException.class, () -> new Open(1, MegaphoneProtocol.MAX_TEXT + 1));
        assertThrows(IllegalArgumentException.class, () -> new Show("id", "a", "", 6000, null));
        assertThrows(IllegalArgumentException.class, () -> new Show("id", "a", "b", 500, null));
        assertThrows(IllegalArgumentException.class, () -> new Show("id", "a", "b", 6000, new byte[MegaphoneProtocol.MAX_ITEM_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new Request(MegaphoneProtocol.SEND, 1, "x".repeat(MegaphoneProtocol.MAX_TEXT + 1)));
        assertThrows(IllegalArgumentException.class, () -> new Request(9, 1, ""));
        // 최대 아이템 크기도 한 프레임에 들어간다
        var big = MegaphoneProtocol.frame(MegaphoneProtocol.show(new Show("id", "가".repeat(MegaphoneProtocol.MAX_SENDER), "나".repeat(MegaphoneProtocol.MAX_TEXT), 30000, new byte[MegaphoneProtocol.MAX_ITEM_BYTES])));
        assertEquals(MegaphoneProtocol.MAX_ITEM_BYTES, big.show().item().length);
        // 뒤에 남는 바이트·모르는 종류는 거부
        byte[] ok = MegaphoneProtocol.close(1);
        byte[] trailing = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> MegaphoneProtocol.frame(trailing));
        byte[] unknown = ok.clone(); unknown[4] = 9;
        assertThrows(IllegalArgumentException.class, () -> MegaphoneProtocol.frame(unknown));
    }
}
