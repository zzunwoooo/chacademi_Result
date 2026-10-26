package school.magiccodex.portrait;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortraitProtocolTest {

    @Test
    void roundTripChunk() {
        byte[] data = new byte[PortraitProtocol.CHUNK_BYTES];
        data[0] = 7;
        data[data.length - 1] = 9;
        String sha = "a".repeat(64);
        var p = new PortraitProtocol.Packet(PortraitProtocol.S_CHUNK, sha, 3, "", false, data);
        var q = PortraitProtocol.decode(PortraitProtocol.encode(p));
        assertEquals(PortraitProtocol.S_CHUNK, q.op());
        assertEquals(sha, q.text());
        assertEquals(3, q.number());
        assertArrayEquals(data, q.data());
    }

    @Test
    void rejectsOversizeAndGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PortraitProtocol.encode(
                new PortraitProtocol.Packet(PortraitProtocol.S_CHUNK, "", 0, "", false, new byte[PortraitProtocol.CHUNK_BYTES + 1])));
        assertThrows(IllegalArgumentException.class, () -> PortraitProtocol.decode(new byte[]{1, 2, 3}));
        byte[] ok = PortraitProtocol.encode(PortraitProtocol.Packet.of(PortraitProtocol.C_HELLO, "", 1, ""));
        byte[] trailing = java.util.Arrays.copyOf(ok, ok.length + 1);
        assertThrows(IllegalArgumentException.class, () -> PortraitProtocol.decode(trailing));
        ok[0] = 99; // 버전
        byte[] bad = ok;
        assertThrows(IllegalArgumentException.class, () -> PortraitProtocol.decode(bad));
    }

    @Test
    void otherPortraitPacketsCarryTargetAndSha() {
        String id = "fedcba98-7654-4321-1234-56789abcdef0", sha = "b".repeat(64);
        assertEquals(java.util.UUID.fromString(id), PortraitProtocol.uuid(id));
        assertNull(PortraitProtocol.uuid("FEDCBA98-7654-4321-1234-56789ABCDEF0"));
        assertNull(PortraitProtocol.uuid("not-a-uuid"));
        assertNull(PortraitProtocol.uuid(null));
        var ask = PortraitProtocol.decode(PortraitProtocol.encode(PortraitProtocol.Packet.of(PortraitProtocol.C_OTHER, id, 0, sha)));
        assertEquals(PortraitProtocol.C_OTHER, ask.op());
        assertEquals(id, ask.text());
        assertEquals(sha, ask.extra());
        var same = PortraitProtocol.decode(PortraitProtocol.encode(new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_META, id, 0, sha, true, null)));
        assertTrue(same.flag());
        byte[] data = new byte[PortraitProtocol.CHUNK_BYTES];
        var chunk = PortraitProtocol.decode(PortraitProtocol.encode(new PortraitProtocol.Packet(PortraitProtocol.S_OTHER_CHUNK, id, 2, sha, false, data)));
        assertEquals(2, chunk.number());
        assertEquals(id, chunk.text());
        assertEquals(PortraitProtocol.CHUNK_BYTES, chunk.data().length);
    }

    @Test
    void promptLimits() {
        assertTrue(PortraitProtocol.validPrompt(""));
        assertTrue(PortraitProtocol.validPrompt("밤하늘 배경에 웃는 얼굴"));
        assertTrue(PortraitProtocol.validPrompt("가".repeat(100)));
        assertFalse(PortraitProtocol.validPrompt("가".repeat(101)));
        assertFalse(PortraitProtocol.validPrompt("a\nb"));
        assertFalse(PortraitProtocol.validPrompt("§c빨강"));
        assertFalse(PortraitProtocol.validPrompt(null));
        assertEquals(0, PortraitProtocol.chunks(0));
        assertEquals(1, PortraitProtocol.chunks(1));
        assertEquals(2, PortraitProtocol.chunks(PortraitProtocol.CHUNK_BYTES + 1));
        assertTrue(PortraitProtocol.validToken("0123456789abcdef0123456789abcdef"));
        assertFalse(PortraitProtocol.validToken("XYZ"));
    }
}
