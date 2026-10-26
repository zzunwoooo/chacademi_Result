package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.TeleportPickProtocol;
import school.magiccodex.protocol.TeleportPickProtocol.*;

/** 텔포권 고르기 창 (오너 2026-10-26) 프레임·요청 왕복과 크기 제한. */
class TeleportPickProtocolTest {
    @Test void openCloseRoundTrip() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var o = TeleportPickProtocol.frame(TeleportPickProtocol.open(new Open(7, List.of(new Entry(a, "차카냥", "루미나", "학교"), new Entry(b, "별빛", "", "야생1")))));
        assertEquals(TeleportPickProtocol.OPEN, o.kind());
        assertEquals(7, o.open().token());
        assertEquals(2, o.open().entries().size());
        assertEquals(a, o.open().entries().get(0).id());
        assertEquals("루미나", o.open().entries().get(0).dormitory());
        assertEquals("야생1", o.open().entries().get(1).server());
        var c = TeleportPickProtocol.frame(TeleportPickProtocol.close(7));
        assertEquals(TeleportPickProtocol.CLOSE, c.kind());
        assertEquals(7, c.closeToken());
    }

    @Test void requestRoundTrip() {
        UUID t = UUID.randomUUID();
        var pick = TeleportPickProtocol.request(TeleportPickProtocol.request(new Request(TeleportPickProtocol.PICK, 9, t)));
        assertEquals(TeleportPickProtocol.PICK, pick.action());
        assertEquals(t, pick.target());
        var cancel = TeleportPickProtocol.request(TeleportPickProtocol.request(new Request(TeleportPickProtocol.CANCEL, 9, t)));
        assertNull(cancel.target());
    }

    @Test void limits() {
        assertThrows(IllegalArgumentException.class, () -> new Request(TeleportPickProtocol.PICK, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Request(TeleportPickProtocol.PICK, 0, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> TeleportPickProtocol.frame(new byte[]{0, 0, 0, 1, 9}));
        var many = new ArrayList<Entry>();
        String longName = "가".repeat(40);
        for (int i = 0; i < TeleportPickProtocol.MAX_ENTRIES; i++) many.add(new Entry(UUID.randomUUID(), longName, "베스티아즈", "야생2"));
        var big = TeleportPickProtocol.frame(TeleportPickProtocol.open(new Open(3, many)));
        assertEquals(TeleportPickProtocol.MAX_ENTRIES, big.open().entries().size());
        assertEquals(TeleportPickProtocol.MAX_NAME, big.open().entries().get(0).name().length());
        many.add(new Entry(UUID.randomUUID(), "x", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new Open(3, many));
    }
}
